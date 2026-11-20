package com.ias.notification;

import com.ias.trust.Problem;
import java.util.*;

/** Frozen INT-1432 producer ownership and immutable event binding checks. */
final class FeeEventClassifier {
    enum Kind { NONE, APPROVAL, LABOR_OTHER, MONEY, PART }
    private static final Set<String> APPROVAL=Set.of("ticketId","roundId","feeGroupId","generation","completionId","applicationId","quoteId","quoteVersion","companyId","stateCode");
    private static final Set<String> TICKET=Set.of("ticketId","roundId","executionId","feeGroupId","generation","completionId","lineId","processingRecordId","amountCents","companyId","assignmentId","kind");
    private static final Set<String> PART=Set.of("ticketId","roundId","executionId","feeGroupId","generation","lineId","warehouseId","partId","quantity","amountCents","companyId");

    static Kind validate(DomainEvent event) {
        Kind kind=switch(event.producer()+"\n"+event.type()) {
            case "approval\napproval.fee_completed" -> Kind.APPROVAL;
            case "ticket\nticket.fee_execution_recorded" -> Kind.LABOR_OTHER;
            case "finance\nfinance.collection_finalized", "finance\nfinance.refund_finalized" -> Kind.MONEY;
            case "stock\ninventory.consumed" -> Kind.PART;
            default -> Kind.NONE;
        };
        if(isFeeType(event.type())&&kind==Kind.NONE)throw new Problem(403,"PRODUCER_NOT_AUTHORIZED");
        switch(kind){case APPROVAL->approval(event);case LABOR_OTHER->ticket(event);case PART->part(event);case MONEY->money(event);default->{} }
        return kind;
    }
    private static boolean isFeeType(String type){return Set.of("approval.fee_completed","ticket.fee_execution_recorded","finance.collection_finalized","finance.refund_finalized","inventory.consumed").contains(type);}
    private static void approval(DomainEvent e){exact(e,APPROVAL);uuids(e,"ticketId","roundId","feeGroupId","completionId","applicationId","quoteId");positive(e,"generation","quoteVersion","companyId");if(!"COMPLETED".equals(e.payload().get("stateCode")))invalid();}
    private static void ticket(DomainEvent e){exact(e,TICKET);uuids(e,"ticketId","roundId","executionId","feeGroupId","completionId","lineId","processingRecordId","assignmentId");positive(e,"generation","amountCents","companyId");if(!Set.of("LABOR","OTHER").contains(e.payload().get("kind")))invalid();if(!e.aggregateId().equals(e.payload().get("executionId")))invalid();}
    private static void part(DomainEvent e){exact(e,PART);uuids(e,"ticketId","roundId","executionId","feeGroupId","lineId");positive(e,"generation","warehouseId","partId","quantity","amountCents","companyId");if(!e.eventId().equals(e.correlationId())||!e.aggregateId().equals(e.payload().get("executionId"))||!e.aggregateVersion().equals(e.payload().get("generation")))invalid();}
    private static void money(DomainEvent e){if(!e.eventId().equals(e.correlationId()))invalid();new MoneyFinalizedEvent(e.type().equals("finance.collection_finalized")?MoneyFinalizedEvent.COLLECTION:MoneyFinalizedEvent.REFUND,moneyPayload(e));}
    private static Map<String,Object> moneyPayload(DomainEvent e){var p=new LinkedHashMap<String,Object>();p.put("schemaVersion","1");p.put("operationId",e.eventId().toString());p.put("tenantId",e.tenantId());for(var x:e.payload().entrySet())p.put(x.getKey().equals("moneyOccurredAt")?"occurredAt":x.getKey(),x.getValue());p.putIfAbsent("originalPaymentId",null);return p;}
    private static void exact(DomainEvent e,Set<String> keys){if(!e.payload().keySet().equals(keys))invalid();}
    private static void uuids(DomainEvent e,String...keys){for(String key:keys)try{String value=e.payload().get(key);if(!UUID.fromString(value).toString().equals(value))invalid();}catch(Exception failure){invalid();}}
    private static void positive(DomainEvent e,String...keys){for(String key:keys)if(e.payload().get(key)==null||!e.payload().get(key).matches("[1-9][0-9]{0,18}"))invalid();}
    private static void invalid(){throw new Problem(400,"INVALID_FEE_EVENT");}
    private FeeEventClassifier(){}
}
