package com.ias.approval;

import com.ias.trust.Chain;
import com.ias.notification.DomainEvent;
import com.ias.notification.JdbcDelivery;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import static com.ias.approval.FeeApproval.*;

/** Same-connection immutable owner facts; never an authority adapter. */
final class FeeApprovalPersistence {
    static Application current(Connection c,Application app)throws SQLException{
        var term=termination(c,app.basis().tenantId(),app.id());
        return term==null?app:new Application(app.id(),app.applicantId(),app.basis(),State.TERMINATED,Long.parseLong(term.get("resultingVersion").toString()),app.confirmation(),app.supervisorId(),app.financeId(),"QUOTE_SUPERSEDED");
    }
    static Map<String,Object> termination(Connection c,long tenant,UUID app)throws SQLException{return object(c,"SELECT payload FROM fee_approval_termination WHERE tenant_id=? AND application_id=?",tenant,app);}
    static void persisted(Connection c,Actor actor,Command cmd,Application old,Application next,UUID event,Map<String,Object> finance,Map<String,Object> ticket,List<Map<String,Object>> lines,String coverage)throws SQLException{
        var now=utc();
        if(old==null)update(c,"INSERT INTO fee_approval_binding(tenant_id,application_id,coverage_digest,quote_context,ticket_context,lines_json,recorded_at) VALUES(?,?,?,?,?,?,?)",actor.tenantId(),next.id(),coverage,json(finance),json(ticket),json(lines),now);
        if(cmd.action()==Action.APPROVE||cmd.action()==Action.REJECT){
            String node=old.state()==State.PENDING_SUPERVISOR?"SUPERVISOR":"FINANCE";
            update(c,"INSERT INTO fee_approval_decision(tenant_id,application_id,node,receipt_id,actor_id,decision,resulting_version,recorded_at) VALUES(?,?,?,?,?,?,?,?)",actor.tenantId(),next.id(),node,cmd.operationId(),actor.userId(),cmd.action(),next.version(),now);
        }
        if(next.state()==State.COMPLETED){
            var supervisor=scalar(c,"SELECT receipt_id FROM fee_approval_decision WHERE tenant_id=? AND application_id=? AND node='SUPERVISOR' AND decision='APPROVE'",actor.tenantId(),next.id());
            var financeReceipt=scalar(c,"SELECT receipt_id FROM fee_approval_decision WHERE tenant_id=? AND application_id=? AND node='FINANCE' AND decision='APPROVE'",actor.tenantId(),next.id());
            if(supervisor==null||next.basis().financialReviewRequired()&&financeReceipt==null)throw fail(503,"COMPLETION_NODE_FACT_MISSING");
            var b=next.basis();var payload=new TreeMap<String,Object>();
            payload.put("tenantId",Long.toString(b.tenantId()));payload.put("actorUserId",Long.toString(actor.userId()));payload.put("companyId",Long.toString(b.companyId()));payload.put("ticketId",b.ticketId().toString());payload.put("roundId",b.roundId().toString());payload.put("faultRelationId",b.faultRelationId().toString());payload.put("groupId",b.groupId().toString());payload.put("quoteId",b.quoteId().toString());payload.put("quoteVersion",Long.toString(b.quoteVersion()));payload.put("quoteDigest",b.quoteDigest());payload.put("applicationId",next.id().toString());payload.put("generation",Long.toString(b.generation()));payload.put("state","COMPLETED");payload.put("completionId",event.toString());payload.put("supervisorReceiptId",supervisor);payload.put("financeReceiptId",financeReceipt);payload.put("financialReviewRequired",b.financialReviewRequired());payload.put("coverageDigest",coverage);payload.put("lines",lines);
            if("2".equals(finance.get("coverageVersion")))payload.put("coverageVersion","2");
            update(c,"INSERT INTO fee_approval_completion(tenant_id,application_id,completion_id,company_id,group_id,payload,recorded_at) VALUES(?,?,?,?,?,?,?)",actor.tenantId(),next.id(),event,b.companyId(),b.groupId(),json(payload),now);
            var notificationPayload=new TreeMap<String,String>();notificationPayload.put("ticketId",b.ticketId().toString());notificationPayload.put("roundId",b.roundId().toString());notificationPayload.put("feeGroupId",b.groupId().toString());notificationPayload.put("generation",Long.toString(b.generation()));notificationPayload.put("completionId",event.toString());notificationPayload.put("applicationId",next.id().toString());notificationPayload.put("quoteId",b.quoteId().toString());notificationPayload.put("quoteVersion",Long.toString(b.quoteVersion()));notificationPayload.put("companyId",Long.toString(b.companyId()));notificationPayload.put("stateCode","COMPLETED");
            var notification=new DomainEvent(event,"approval.fee_completed",1,Long.toString(b.tenantId()),next.id().toString(),Long.toString(next.version()),now.toInstant(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),"approval",cmd.operationId(),notificationPayload);
            JdbcDelivery.appendOnOwnerTransaction(c,notification,Set.of("notification"));
        }
    }
    /** Called only after W full binding verified and new owner fence persisted under this same generation lock. */
    static void terminate(Connection c,ApprovalFinanceGeneration.Binding fence)throws SQLException{
        var apps=new ArrayList<Application>();try(var p=c.prepareStatement("SELECT snapshot FROM fee_approval_application WHERE tenant_id=? AND ticket_id=? ORDER BY id FOR UPDATE")){bind(p,fence.tenantId(),fence.ticketId());try(var r=p.executeQuery()){while(r.next())apps.add(Chain.JSON.readValue(r.getString(1),Application.class));}}
        for(var app:apps){var b=app.basis();
            if(!Long.toString(b.companyId()).equals(fence.companyId())||!b.groupId().equals(fence.groupId())||b.generation()>=Long.parseLong(fence.generation())||Set.of(State.COMPLETED,State.REJECTED,State.WITHDRAWN,State.TERMINATED).contains(app.state())||termination(c,b.tenantId(),app.id())!=null)continue;
            var receipt=UUID.randomUUID();var now=utc();long version=Math.incrementExact(app.version());
            var payload=new TreeMap<String,Object>();payload.put("applicationId",app.id().toString());payload.put("tenantId",fence.tenantId());payload.put("ticketId",fence.ticketId().toString());payload.put("companyId",fence.companyId());payload.put("groupId",fence.groupId().toString());payload.put("oldQuoteId",b.quoteId().toString());payload.put("oldQuoteVersion",Long.toString(b.quoteVersion()));payload.put("oldGeneration",Long.toString(b.generation()));payload.put("freezeId",fence.freezeId().toString());payload.put("generation",fence.generation());payload.put("sourceVersion",Long.toString(app.version()));payload.put("resultingVersion",Long.toString(version));payload.put("receiptId",receipt.toString());payload.put("basisDigest",sha(json(b)));payload.put("binding",ApprovalFinanceGeneration.wire(fence));payload.put("state","TERMINATED");payload.put("reason","QUOTE_SUPERSEDED");String digest=sha(json(payload));
            update(c,"INSERT INTO fee_approval_termination(tenant_id,application_id,freeze_id,generation,resulting_version,receipt_id,payload,recorded_at) VALUES(?,?,?,?,?,?,?,?)",b.tenantId(),app.id(),fence.freezeId(),fence.generation(),version,receipt,json(payload),now);
            var projected=current(c,app);
            update(c,"INSERT INTO fee_approval_operation(tenant_id,actor_id,operation_id,application_id,command_digest,command_json,receipt,created_at) VALUES(?,?,?,?,?,?,?,?)",b.tenantId(),fence.actorUserId(),receipt,app.id(),digest,json(payload),json(projected),now);
            update(c,"INSERT INTO fee_approval_audit(event_id,tenant_id,actor_id,operation_id,application_id,action,resulting_version,command_digest,occurred_at) VALUES(?,?,?,?,?,?,?,?,?)",receipt,b.tenantId(),fence.actorUserId(),receipt,app.id(),"TERMINATE",version,digest,now);
            update(c,"INSERT INTO fee_approval_outbox(event_id,tenant_id,application_id,aggregate_version,event_type,envelope,created_at) VALUES(?,?,?,?,?,?,?)",receipt,b.tenantId(),app.id(),version,"FEE_APPROVAL_TERMINATED",json(payload),now);
        }
    }
    static List<Object> coverage(Connection c,ApprovalFinanceGeneration.Binding fence)throws SQLException{
        var rows=new ArrayList<Object>();try(var p=c.prepareStatement("SELECT snapshot FROM fee_approval_application WHERE tenant_id=? AND ticket_id=? ORDER BY id")){bind(p,fence.tenantId(),fence.ticketId());try(var r=p.executeQuery()){while(r.next()){
            Application app=Chain.JSON.readValue(r.getString(1),Application.class);var row=new TreeMap<String,Object>();row.put("feeApplication",Chain.JSON.convertValue(app,Map.class));row.put("termination",termination(c,app.basis().tenantId(),app.id()));row.put("completion",object(c,"SELECT payload FROM fee_approval_completion WHERE tenant_id=? AND application_id=?",app.basis().tenantId(),app.id()));
            var ops=new ArrayList<Object>();try(var op=c.prepareStatement("SELECT actor_id,operation_id,command_digest,receipt FROM fee_approval_operation WHERE tenant_id=? AND application_id=? ORDER BY actor_id,operation_id")){bind(op,app.basis().tenantId(),app.id());try(var rr=op.executeQuery()){while(rr.next())ops.add(Map.of("actorId",rr.getString(1),"operationId",rr.getString(2),"commandDigest",rr.getString(3),"receipt",map(rr.getString(4))));}}row.put("feeOperations",ops);rows.add(row);
        }}}return rows;
    }
    @SuppressWarnings("unchecked")static Map<String,Object> map(String s){return Chain.JSON.readValue(s,Map.class);}
    static Map<String,Object> object(Connection c,String sql,Object...values)throws SQLException{String s=scalar(c,sql,values);return s==null?null:map(s);}
    static String scalar(Connection c,String sql,Object...values)throws SQLException{try(var p=c.prepareStatement(sql)){bind(p,values);try(var r=p.executeQuery()){return r.next()?r.getString(1):null;}}}
    static int update(Connection c,String sql,Object...values)throws SQLException{try(var p=c.prepareStatement(sql)){bind(p,values);return p.executeUpdate();}}
    static void bind(PreparedStatement p,Object...values)throws SQLException{for(int i=0;i<values.length;i++){Object v=values[i];p.setObject(i+1,v instanceof UUID||v instanceof Enum<?>?v.toString():v);}}
    static String json(Object value){return Chain.JSON.writeValueAsString(value);}
    static String sha(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    static LocalDateTime utc(){return LocalDateTime.ofInstant(Instant.now(),ZoneOffset.UTC);}
}
