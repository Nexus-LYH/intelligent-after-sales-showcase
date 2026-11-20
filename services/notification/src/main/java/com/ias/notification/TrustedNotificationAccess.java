package com.ias.notification;
import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

/** Every recipient is resolved from authoritative owner facts and current identity scope, never event authority. */
public final class TrustedNotificationAccess implements NotificationAccess {
    @FunctionalInterface interface Calls {Map<String,Object> call(String edge,String path,Map<String,Object> body,HttpServletRequest request);}
    private final Calls calls;
    public TrustedNotificationAccess(){this((edge,path,body,r)->Chain.call(edge,path,body,Chain.trace(r),Chain.deadline(r)));}
    TrustedNotificationAccess(Calls calls){this.calls=calls;}
    private static void edge(HttpServletRequest r,String expected){if(!Chain.active()||!"notification".equals(Chain.role())||!r.isSecure()||!expected.equals(r.getAttribute("ias.chain.edge")))throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");}
    private static String token(HttpServletRequest r){String t=Chain.cookie(r);if(t==null||t.isBlank())throw new Problem(401,"SESSION_INVALID");return t;}
    private Map<String,Object> call(String edge,String path,Map<String,Object> body,HttpServletRequest r){var result=calls.call(edge,path,body,r);Chain.fresh(result,Chain.deadline(r));return result;}
    @Override public Actor actor(HttpServletRequest r,boolean write){
        edge(r,"gateway-notification");var body=new LinkedHashMap<String,Object>();body.put("sessionToken",token(r));body.put("purpose",write?"NOTIFICATION_WRITE":"NOTIFICATION_READ");
        if(write){Chain.source(r);body.put("csrfToken",Chain.single(r,"X-CSRF-TOKEN"));}
        var identity=call("notification-identity","/internal/identity/domain-authorizations",body,r);
        if(!Boolean.TRUE.equals(identity.get("enabled")))throw new Problem(403,"ACCESS_DENIED");
        return new Actor(id(identity.get("tenantId")),id(identity.get("userId")));
    }
    private Map<String,Object> facts(DomainEvent event,HttpServletRequest r,boolean read){
        if(customer(event)){if(!event.allowsConsumer("notification"))throw new Problem(403,"PRODUCER_NOT_AUTHORIZED");return customerFacts(event,r,read);}
        boolean fee=FeeEventClassifier.validate(event)!=FeeEventClassifier.Kind.NONE;
        if(!event.allowsConsumer("notification")||!fee&&(!"approval".equals(event.producer())||!event.type().startsWith("approval.")))throw new Problem(403,"PRODUCER_NOT_AUTHORIZED");
        if(fee)return feeFacts(event,r,read);
        var body=new LinkedHashMap<String,Object>();body.put("eventId",event.eventId().toString());body.put("tenantId",event.tenantId());body.put("approvalId",event.aggregateId());body.put("eventType",event.type());body.put("aggregateVersion",event.aggregateVersion());body.put("purpose",read?"READ":"DELIVERY");if(read)body.put("sessionToken",token(r));
        var facts=call("notification-approval","/internal/approval/notification-context",body,r);
        for(String key:List.of("tenantId","eventId","approvalId","eventType","aggregateVersion")){String expected=switch(key){case "tenantId"->event.tenantId();case "eventId"->event.eventId().toString();case "approvalId"->event.aggregateId();case "eventType"->event.type();default->event.aggregateVersion();};if(!expected.equals(facts.get(key)))throw new Problem(503,"DEPENDENCY_UNAVAILABLE");}
        id(facts.get("companyId"));id(facts.get("departmentId"));id(facts.get("applicantUserId"));return facts;
    }
    private static boolean customer(DomainEvent event){return "ticket".equals(event.producer())&&Set.of("ticket.accepted","ticket.resolution_submitted","ticket.closed").contains(event.type());}
    private Map<String,Object> customerFacts(DomainEvent event,HttpServletRequest r,boolean read){
        var body=new LinkedHashMap<String,Object>();body.put("eventId",event.eventId().toString());body.put("tenantId",event.tenantId());body.put("ticketId",event.aggregateId());body.put("eventType",event.type());body.put("aggregateVersion",event.aggregateVersion());body.put("purpose",read?"READ":"DELIVERY");if(read)body.put("sessionToken",token(r));
        var facts=call("notification-ticket","/internal/ticket/customer-notification-context",body,r);
        for(String key:List.of("tenantId","eventId","eventType","ticketId","aggregateVersion")){String expected=switch(key){case "tenantId"->event.tenantId();case "eventId"->event.eventId().toString();case "eventType"->event.type();case "ticketId"->event.aggregateId();default->event.aggregateVersion();};if(!expected.equals(facts.get(key)))throw new Problem(503,"DEPENDENCY_UNAVAILABLE");}
        id(facts.get("companyId"));id(facts.get("recipientUserId"));return facts;
    }
    private Map<String,Object> feeFacts(DomainEvent event,HttpServletRequest r,boolean read){
        if(event.producer().equals("finance"))return moneyFacts(event,r,read);
        if(event.producer().equals("stock"))return partFacts(event,r,read);
        var body=new LinkedHashMap<String,Object>();body.put("eventId",event.eventId().toString());body.put("tenantId",event.tenantId());body.put("eventType",event.type());body.put("aggregateId",event.aggregateId());body.put("aggregateVersion",event.aggregateVersion());body.put("feeGroupId",event.payload().get("feeGroupId"));body.put("generation",event.payload().get("generation"));body.put("purpose",read?"READ":"DELIVERY");if(read)body.put("sessionToken",token(r));
        String edge=event.producer().equals("approval")?"notification-approval":"notification-ticket";
        String path=event.producer().equals("approval")?"/internal/approval/fee-notification-context":"/internal/ticket/fee-notification-context";
        var facts=call(edge,path,body,r);
        for(String key:List.of("tenantId","eventId","eventType","aggregateId","aggregateVersion","feeGroupId","generation")){String expected=switch(key){case "tenantId"->event.tenantId();case "eventId"->event.eventId().toString();case "eventType"->event.type();case "aggregateId"->event.aggregateId();case "aggregateVersion"->event.aggregateVersion();default->event.payload().get(key);};if(!Objects.equals(expected,facts.get(key)))throw new Problem(503,"DEPENDENCY_UNAVAILABLE");}
        id(facts.get("companyId"));id(facts.get("departmentId"));id(facts.get("applicantUserId"));return facts;
    }
    private Map<String,Object> partFacts(DomainEvent event,HttpServletRequest r,boolean read){
        var body=new LinkedHashMap<String,Object>();body.put("eventId",event.eventId().toString());body.put("tenantId",event.tenantId());body.put("eventType",event.type());for(String key:List.of("companyId","ticketId","roundId","feeGroupId","generation"))body.put(key,event.payload().get(key));body.put("executionReceiptId",event.payload().get("executionId"));for(String key:List.of("lineId","warehouseId","partId","quantity","amountCents"))body.put(key,event.payload().get(key));body.put("purpose","DELIVERY");
        var facts=call("notification-stock","/internal/stock/part-fee-notification-context",body,r);
        for(var entry:body.entrySet())if(!entry.getKey().equals("purpose")&&!Objects.equals(entry.getValue(),facts.get(entry.getKey())))throw unavailable();
        if(!Objects.equals(event.payload().get("generation"),facts.get("factVersion"))||!"1".equals(facts.get("eventVersion"))||!Boolean.TRUE.equals(facts.get("currentScope"))||!Boolean.TRUE.equals(facts.get("qualified"))||Boolean.TRUE.equals(facts.get("revoked")))throw unavailable();
        id(facts.get("actorUserId"));if(!(facts.get("coverageDigest") instanceof String digest)||!digest.matches("[0-9a-f]{64}"))throw unavailable();return facts;
    }
    private Map<String,Object> moneyFacts(DomainEvent event,HttpServletRequest r,boolean read){
        var body=new LinkedHashMap<String,Object>();body.put("eventId",event.eventId().toString());body.put("eventType",event.type());body.put("schemaVersion","1");body.put("tenantId",event.tenantId());
        for(String key:List.of("companyId","groupId","billId","completionId","accountVersion","action","businessId","amountCents","authorityDigest","executionReceiptsDigest"))body.put(key,event.payload().get(key));body.put("originalPaymentId",event.payload().get("originalPaymentId"));body.put("occurredAt",event.payload().get("moneyOccurredAt"));body.put("purpose",read?"READ":"DELIVERY");if(read)body.put("sessionToken",token(r));
        var facts=call("notification-finance","/internal/finance/money-notification-context",body,r);
        for(var entry:body.entrySet())if(!entry.getKey().equals("purpose")&&!entry.getKey().equals("sessionToken")&&!Objects.equals(entry.getValue(),facts.get(entry.getKey())))throw new Problem(503,"DEPENDENCY_UNAVAILABLE");
        id(facts.get("companyId"));id(facts.get("departmentId"));id(facts.get("applicantUserId"));return facts;
    }
    private Map<String,Object> eligibility(DomainEvent event,Map<String,Object> facts,HttpServletRequest r){
        if(event.type().equals("approval.fee_completed")){
            var body=Map.of("tenantId",facts.get("tenantId"),"companyId",facts.get("companyId"),"departmentId",facts.get("departmentId"),"applicantUserId",facts.get("applicantUserId"));
            var current=call("notification-identity","/internal/identity/fee-recipients",body,r);
            for(var entry:body.entrySet())if(!Objects.equals(entry.getValue(),current.get(entry.getKey())))throw unavailable();
            if(!(current.get("supervisorUserIds") instanceof List<?> supervisors)||!(current.get("financeUserIds") instanceof List<?> finance)||!(current.get("applicantEligible") instanceof Boolean))throw unavailable();
            var reviewers=new LinkedHashSet<String>();
            for(Object user:supervisors)reviewers.add(Long.toString(id(user)));
            for(Object user:finance)reviewers.add(Long.toString(id(user)));
            return Map.of("tenantId",current.get("tenantId"),"reviewerUserIds",List.copyOf(reviewers),"applicantEligible",current.get("applicantEligible"));
        }
        var result=call("notification-identity","/internal/identity/approval-recipients",Map.of("tenantId",facts.get("tenantId"),"companyId",facts.get("companyId"),"departmentId",facts.get("departmentId"),"applicantUserId",facts.get("applicantUserId")),r);
        if(!Objects.equals(facts.get("tenantId"),result.get("tenantId"))||!(result.get("reviewerUserIds") instanceof List<?>)||!(result.get("applicantEligible") instanceof Boolean))throw new Problem(503,"DEPENDENCY_UNAVAILABLE");
        for(Object user:(List<?>)result.get("reviewerUserIds"))id(user);return result;
    }
    @Override public List<Recipient> recipients(DomainEvent event,HttpServletRequest r){
        edge(r,event.producer()+"-notification");var facts=facts(event,r,false);if(customer(event))return List.of(new Recipient(id(facts.get("recipientUserId")),"CUSTOMER"));if(event.producer().equals("stock"))return List.of(new Recipient(id(facts.get("actorUserId")),"ENGINEER"));var current=eligibility(event,facts,r);var out=new ArrayList<Recipient>();
        for(Object user:(List<?>)current.get("reviewerUserIds"))out.add(new Recipient(id(user),"REVIEWER"));
        if(Boolean.TRUE.equals(current.get("applicantEligible")))out.add(new Recipient(id(facts.get("applicantUserId")),"APPLICANT"));
        return List.copyOf(out);
    }
    @Override public boolean visible(Actor actor,DomainEvent event,String kind,HttpServletRequest r){
        if(actor.tenant()!=id(event.tenantId()))return false;
        try{var facts=facts(event,r,true);if(customer(event))return kind.equals("CUSTOMER")&&actor.user()==id(facts.get("recipientUserId"));if(event.producer().equals("stock"))return kind.equals("ENGINEER")&&actor.user()==id(facts.get("actorUserId"));var current=eligibility(event,facts,r);
            return kind.equals("APPLICANT")?actor.user()==id(facts.get("applicantUserId"))&&Boolean.TRUE.equals(current.get("applicantEligible")):((List<?>)current.get("reviewerUserIds")).contains(Long.toString(actor.user()));
        }catch(Problem failure){if(failure.status==403||failure.status==404||failure.status==409&&Set.of("FEE_NOTIFICATION_FACT_STALE","FEE_NOTIFICATION_SCOPE_REVOKED","PART_FEE_NOTIFICATION_FACT_STALE","PART_FEE_NOTIFICATION_SCOPE_REVOKED","MONEY_NOTIFICATION_FACT_STALE","MONEY_NOTIFICATION_SCOPE_REVOKED").contains(failure.code))return false;throw failure;}
    }
    static long id(Object value){if(!(value instanceof String s)||!s.matches("[1-9][0-9]{0,18}"))throw new Problem(503,"DEPENDENCY_UNAVAILABLE");try{return Long.parseLong(s);}catch(Exception e){throw new Problem(503,"DEPENDENCY_UNAVAILABLE");}}
    private static void positive(Object value){if(!(value instanceof String s)||!s.matches("[1-9][0-9]{0,18}"))throw unavailable();}
    private static void uuid(Object value){try{if(!(value instanceof String s)||!UUID.fromString(s).toString().equals(s))throw unavailable();}catch(Exception e){throw unavailable();}}
    private static Problem unavailable(){return new Problem(503,"DEPENDENCY_UNAVAILABLE");}
}
