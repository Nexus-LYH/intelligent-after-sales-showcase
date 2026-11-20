package com.ias.approval;

import com.ias.notification.DomainEvent;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import static com.ias.approval.PartsApproval.*;

/** Read-only projections; caller authorization precedes all public lookups. */
public final class ApprovalQueries {
    private final DataSource source;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public ApprovalQueries(DataSource source){this.source=source;}
    public String executionChargeMode(String tenant,UUID application){try(var c=source.getConnection();var s=c.prepareStatement("SELECT charge_mode FROM approval_execution_policy WHERE tenant_id=? AND application_id=?")){s.setString(1,tenant);s.setString(2,application.toString());try(var r=s.executeQuery()){return r.next()?r.getString(1):"UNKNOWN";}}catch(SQLException e){throw new ApprovalFailure(503,"EXECUTION_POLICY_UNAVAILABLE");}}
    public record Page(List<ApprovalView> items,long total,int page,int size){}
    public Page list(String tenant,String ticket,int page,int size){
        try(var c=source.getConnection()){
            long total;
            try(var s=c.prepareStatement("SELECT COUNT(*) FROM approval_source_reference WHERE tenant_id=? AND ticket_id=?")){s.setString(1,tenant);s.setString(2,ticket);try(var r=s.executeQuery()){r.next();total=r.getLong(1);}}
            var items=new ArrayList<ApprovalView>();
            try(var s=c.prepareStatement("SELECT p.snapshot FROM parts_application p JOIN approval_source_reference b ON b.tenant_id=p.tenant_id AND b.application_id=p.id WHERE b.tenant_id=? AND b.ticket_id=? ORDER BY p.created_at DESC,p.id DESC LIMIT ? OFFSET ?")){
                s.setString(1,tenant);s.setString(2,ticket);s.setInt(3,size);s.setInt(4,(page-1)*size);
                try(var r=s.executeQuery()){while(r.next())items.add(ApprovalView.of(JSON.readValue(r.getString(1),Application.class)));}
            }
            return new Page(List.copyOf(items),total,page,size);
        }catch(SQLException failure){throw new ApprovalFailure(503,"DEPENDENCY_UNAVAILABLE");}
    }
    public record Fact(DomainEvent event,Application application,String operationId,String commandDigest,String roundId,String companyId){}
    public Fact fact(UUID eventId,String tenant,UUID applicationId,String type,String version){
        try(var c=source.getConnection();var s=c.prepareStatement("SELECT e.envelope_json,p.snapshot,b.operation_id,b.command_digest,b.round_id,b.company_id FROM event_outbox e JOIN parts_application p ON p.tenant_id=e.tenant_id AND p.id=? JOIN approval_source_reference b ON b.tenant_id=p.tenant_id AND b.application_id=p.id WHERE e.event_id=? AND e.tenant_id=?")){
            s.setString(1,applicationId.toString());s.setString(2,eventId.toString());s.setString(3,tenant);
            try(var r=s.executeQuery()){
                if(!r.next())throw new ApprovalFailure(404,"TARGET_NOT_ACCESSIBLE");
                var event=DomainEvent.decode(r.getString(1));
                if(!event.aggregateId().equals(applicationId.toString())||!event.type().equals(type)||!event.aggregateVersion().equals(version)||!event.producer().equals("approval"))throw new ApprovalFailure(409,"EVENT_FACT_CONFLICT");
                return new Fact(event,JSON.readValue(r.getString(2),Application.class),r.getString(3),r.getString(4),r.getString(5),r.getString(6));
            }
        }catch(SQLException failure){throw new ApprovalFailure(503,"DEPENDENCY_UNAVAILABLE");}
    }
    public DomainEvent feeCompletion(UUID eventId,String tenant,UUID applicationId,String type,String version){
        try(var c=source.getConnection();var s=c.prepareStatement("SELECT e.envelope_json,f.event_type,c.completion_id FROM event_outbox e JOIN fee_approval_outbox f ON f.event_id=e.event_id AND f.tenant_id=e.tenant_id JOIN fee_approval_completion c ON c.tenant_id=f.tenant_id AND c.application_id=f.application_id AND c.completion_id=f.event_id WHERE e.event_id=? AND e.tenant_id=? AND f.application_id=?")){
            s.setString(1,eventId.toString());s.setString(2,tenant);s.setString(3,applicationId.toString());try(var r=s.executeQuery()){
                if(!r.next())throw new ApprovalFailure(404,"TARGET_NOT_ACCESSIBLE");var event=DomainEvent.decode(r.getString(1));
                if(!"FEE_APPROVAL_COMPLETED".equals(r.getString(2))||!event.eventId().equals(eventId)||!event.aggregateId().equals(applicationId.toString())||!event.type().equals(type)||!event.aggregateVersion().equals(version)||!event.producer().equals("approval")||!event.eventId().toString().equals(r.getString(3)))throw new ApprovalFailure(409,"EVENT_FACT_CONFLICT");return event;
            }
        }catch(SQLException failure){throw new ApprovalFailure(503,"DEPENDENCY_UNAVAILABLE");}
    }
    public Map<String,Object> context(Fact fact){
        var e=fact.event();var s=fact.application().submission();
        return Map.ofEntries(Map.entry("eventId",e.eventId().toString()),Map.entry("tenantId",e.tenantId()),Map.entry("approvalId",e.aggregateId()),Map.entry("ticketId",s.ticketId()),Map.entry("companyId",fact.companyId()),Map.entry("departmentId",s.departmentId()),Map.entry("applicantUserId",s.applicantId()),Map.entry("eventType",e.type()),Map.entry("aggregateVersion",e.aggregateVersion()),Map.entry("state",switch(e.type()){case "approval.submitted"->"PENDING";case "approval.approved"->"APPROVED";case "approval.rejected"->"REJECTED";case "approval.withdrawn"->"WITHDRAWN";default->throw new ApprovalFailure(409,"EVENT_FACT_CONFLICT");}),Map.entry("checkedAt",Instant.now().toString()));
    }
    public void requireDeliveryReady(){
        try(var c=source.getConnection();var s=c.createStatement()){
            try(var r=s.executeQuery("SELECT COUNT(*) FROM approval_outbox a LEFT JOIN event_outbox e ON e.event_id=a.event_id LEFT JOIN approval_source_reference b ON b.application_id=a.aggregate_id AND b.tenant_id=a.tenant_id WHERE e.event_id IS NULL OR b.application_id IS NULL")){r.next();if(r.getLong(1)!=0)throw new IllegalStateException("LEGACY_EVENT_SOURCE_BACKFILL_REQUIRED");}
            try(var r=s.executeQuery("SELECT COUNT(*) FROM event_outbox e LEFT JOIN event_delivery d ON d.event_id=e.event_id AND d.consumer_id='ticket' WHERE e.event_type IN ('approval.approved','approval.rejected','approval.withdrawn') AND d.event_id IS NULL")){r.next();if(r.getLong(1)!=0)throw new IllegalStateException("TERMINAL_DELIVERY_BACKFILL_REQUIRED");}
        }catch(SQLException failure){throw new IllegalStateException("DELIVERY_SCHEMA_NOT_READY");}
    }
}
