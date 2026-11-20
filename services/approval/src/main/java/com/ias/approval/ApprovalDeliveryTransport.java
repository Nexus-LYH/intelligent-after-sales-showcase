package com.ias.approval;

import com.ias.notification.*;
import com.ias.trust.Chain;
import java.util.*;

/** Sends committed owner facts only; no browser session or temporary authority is retained in jobs. */
public final class ApprovalDeliveryTransport implements JdbcDelivery.Transport {
    private final ApprovalQueries queries;
    public ApprovalDeliveryTransport(ApprovalQueries queries){this.queries=queries;}
    @Override public void send(String consumer,DomainEvent event){
        String trace=UUID.randomUUID().toString();long deadline=System.currentTimeMillis()+10000;
        if(consumer.equals("notification")){
            if(event.type().equals("approval.fee_completed")){var owner=queries.feeCompletion(event.eventId(),event.tenantId(),UUID.fromString(event.aggregateId()),event.type(),event.aggregateVersion());if(!owner.sha().equals(event.sha()))throw new ApprovalFailure(409,"EVENT_FACT_CONFLICT");}
            else {var fact=queries.fact(event.eventId(),event.tenantId(),UUID.fromString(event.aggregateId()),event.type(),event.aggregateVersion());if(!fact.event().sha().equals(event.sha()))throw new ApprovalFailure(409,"EVENT_FACT_CONFLICT");}
            var reply=Chain.call("approval-notification","/internal/notification/events",Map.of("event",Chain.parse(event.encode().getBytes(java.nio.charset.StandardCharsets.UTF_8))),trace,deadline);
            if(!event.eventId().toString().equals(reply.get("eventId"))||!Set.of("COMMITTED","COMMITTED_DUPLICATE").contains(reply.get("ack")))throw new ApprovalFailure(503,"DELIVERY_ACK_UNCONFIRMED");
        }else if(consumer.equals("ticket")){
            var fact=queries.fact(event.eventId(),event.tenantId(),UUID.fromString(event.aggregateId()),event.type(),event.aggregateVersion());if(!fact.event().sha().equals(event.sha()))throw new ApprovalFailure(409,"EVENT_FACT_CONFLICT");
            String state=switch(event.type()){case "approval.approved"->"APPROVED";case "approval.rejected"->"REJECTED";case "approval.withdrawn"->"WITHDRAWN";default->throw new ApprovalFailure(409,"UNSUPPORTED_SETTLEMENT");};
            var body=Map.<String,Object>of("eventId",event.eventId().toString(),"tenantId",event.tenantId(),"ticketId",fact.application().submission().ticketId(),"operationId",fact.operationId(),"commandDigest",fact.commandDigest(),"approvalId",event.aggregateId(),"decisionOperationId",event.correlationId().toString(),"state",state);
            var reply=Chain.call("approval-ticket","/internal/ticket/approval-references/settle",body,trace,deadline);
            if(!event.eventId().toString().equals(reply.get("eventId"))||!fact.operationId().equals(reply.get("operationId"))||!state.equals(reply.get("state")))throw new ApprovalFailure(503,"DELIVERY_ACK_UNCONFIRMED");
        }else throw new ApprovalFailure(403,"CONSUMER_NOT_SUBSCRIBED");
    }
}
