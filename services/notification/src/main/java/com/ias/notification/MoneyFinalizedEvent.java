package com.ias.notification;

import com.ias.trust.Problem;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Strict adapter for the frozen BE-F V8 terminal payload. It carries no recipient authority. */
public record MoneyFinalizedEvent(String eventType,Map<String,Object> payload) {
    static final String COLLECTION="FINANCE_COLLECTION_FINALIZED",REFUND="FINANCE_REFUND_FINALIZED";
    static final Set<String> FIELDS=Set.of("schemaVersion","operationId","tenantId","companyId","groupId","billId","completionId","accountVersion","action","businessId","originalPaymentId","amountCents","authorityDigest","executionReceiptsDigest","occurredAt");
    static final Set<String> COLLECTION_ACTIONS=Set.of("COLLECTION_RECORDED","COLLECTION_REVERSED","COLLECTION_REBOOKED");
    public MoneyFinalizedEvent {
        if(!Set.of(COLLECTION,REFUND).contains(eventType)||payload==null||!payload.keySet().equals(FIELDS))throw invalid();
        payload=Collections.unmodifiableMap(new TreeMap<>(payload));validate(payload,eventType);
    }
    public DomainEvent domainEvent(){
        var p=new TreeMap<String,String>();for(String key:List.of("companyId","groupId","billId","completionId","accountVersion","action","businessId","amountCents","authorityDigest","executionReceiptsDigest"))p.put(key,text(payload,key));
        if(payload.get("originalPaymentId")!=null)p.put("originalPaymentId",text(payload,"originalPaymentId"));p.put("moneyOccurredAt",text(payload,"occurredAt"));
        UUID operation=uuid(payload.get("operationId"));Instant occurred=instant(payload.get("occurredAt")).truncatedTo(ChronoUnit.MILLIS);
        return new DomainEvent(operation,eventType.equals(COLLECTION)?"finance.collection_finalized":"finance.refund_finalized",1,text(payload,"tenantId"),text(payload,"groupId"),text(payload,"accountVersion"),occurred,"finance",operation,p);
    }
    private static void validate(Map<String,Object> p,String event){
        if(!"1".equals(p.get("schemaVersion")))throw invalid();
        for(String key:List.of("operationId","groupId","billId","completionId","businessId"))uuid(p.get(key));
        if(p.get("originalPaymentId")!=null)uuid(p.get("originalPaymentId"));
        positive(p,"tenantId",false);positive(p,"companyId",false);positive(p,"accountVersion",false);positive(p,"amountCents",true);
        String action=text(p,"action");if(event.equals(COLLECTION)?!COLLECTION_ACTIONS.contains(action):!"REFUND_VOUCHER_RECORDED".equals(action))throw invalid();
        for(String key:List.of("authorityDigest","executionReceiptsDigest"))if(!text(p,key).matches("[0-9a-f]{64}"))throw invalid();
        instant(p.get("occurredAt"));
    }
    private static long positive(Map<String,Object> p,String key,boolean zero){String v=text(p,key);if(!v.matches(zero?"0|[1-9][0-9]{0,18}":"[1-9][0-9]{0,18}"))throw invalid();try{return Long.parseLong(v);}catch(Exception e){throw invalid();}}
    private static UUID uuid(Object value){try{String text=value instanceof String s?s:"";UUID id=UUID.fromString(text);if(!id.toString().equals(text))throw invalid();return id;}catch(Exception e){throw invalid();}}
    private static Instant instant(Object value){try{if(!(value instanceof String s)||!s.endsWith("Z"))throw invalid();return Instant.parse(s);}catch(Exception e){throw invalid();}}
    private static String text(Map<String,Object> p,String key){if(!(p.get(key) instanceof String s)||s.isEmpty())throw invalid();return s;}
    private static Problem invalid(){return new Problem(400,"INVALID_MONEY_TERMINAL_EVENT");}
}
