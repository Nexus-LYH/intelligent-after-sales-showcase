package com.ias.notification;
import com.ias.trust.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Adapter contract tests, not a real TLS/identity acceptance substitute. */
class TrustedNotificationAccessTest {
    MockedStatic<Chain> chain;DomainEvent event;MockHttpServletRequest request;
    Map<String,Object> facts,eligible;List<Map<String,Object>> calls;TrustedNotificationAccess access;
    @BeforeEach void setup(){
        chain=mockStatic(Chain.class,CALLS_REAL_METHODS);chain.when(Chain::active).thenReturn(true);chain.when(Chain::role).thenReturn("notification");
        event=MessageInboxTest.event();request=new MockHttpServletRequest();request.setSecure(true);request.setAttribute("ias.chain.edge","approval-notification");request.setCookies(new Cookie("IAS-SESSION-DEV","current-session"));
        facts=new HashMap<>(Map.of("tenantId",event.tenantId(),"eventId",event.eventId().toString(),"approvalId",event.aggregateId(),"eventType",event.type(),"aggregateVersion",event.aggregateVersion(),"companyId","2","departmentId","3","applicantUserId","10","checkedAt",Instant.now().toString()));
        eligible=new HashMap<>(Map.of("tenantId","1","reviewerUserIds",List.of("11","12"),"applicantEligible",true,"checkedAt",Instant.now().toString()));calls=new ArrayList<>();
        access=new TrustedNotificationAccess((edge,path,body,r)->{calls.add(new HashMap<>(body));if(path.endsWith("notification-context"))return facts;if(path.endsWith("approval-recipients"))return eligible;return Map.of("tenantId","1","userId","10","enabled",true,"checkedAt",Instant.now().toString());});
    }
    @AfterEach void close(){chain.close();}
    @Test void deliveryUsesOnlyOwnerFactsAndCurrentIdentityRecipients(){
        assertEquals(List.of(new NotificationAccess.Recipient(11,"REVIEWER"),new NotificationAccess.Recipient(12,"REVIEWER"),new NotificationAccess.Recipient(10,"APPLICANT")),access.recipients(event,request));
        assertEquals("DELIVERY",calls.getFirst().get("purpose"));assertFalse(calls.getFirst().containsKey("sessionToken"));
        assertEquals(Map.of("tenantId","1","companyId","2","departmentId","3","applicantUserId","10"),calls.getLast());
    }
    @Test void noConfiguredReviewerStillNotifiesCurrentApplicant(){eligible.put("reviewerUserIds",List.of());assertEquals(List.of(new NotificationAccess.Recipient(10,"APPLICANT")),access.recipients(event,request));}
    @Test void invalidProducerEdgeAndPlaintextNeverResolveRecipients(){
        request.setSecure(false);assertEquals(403,assertThrows(Problem.class,()->access.recipients(event,request)).status);request.setSecure(true);request.setAttribute("ias.chain.edge","gateway-notification");assertEquals(403,assertThrows(Problem.class,()->access.recipients(event,request)).status);assertTrue(calls.isEmpty());
    }
    @Test void ownerEnvelopeMismatchAndStaleFactsFailClosed(){
        facts.put("eventId",UUID.randomUUID().toString());assertEquals(503,assertThrows(Problem.class,()->access.recipients(event,request)).status);
        facts.put("eventId",event.eventId().toString());facts.put("checkedAt",Instant.now().minusSeconds(3).toString());assertEquals(503,assertThrows(Problem.class,()->access.recipients(event,request)).status);
    }
    @Test void readRechecksCurrentObjectAndQualificationBeforeHistoricalProjection(){
        request.setAttribute("ias.chain.edge","gateway-notification");assertTrue(access.visible(new NotificationAccess.Actor(1,11),event,"REVIEWER",request));
        assertEquals("READ",calls.getFirst().get("purpose"));assertEquals("current-session",calls.getFirst().get("sessionToken"));
        eligible.put("reviewerUserIds",List.of("12"));assertFalse(access.visible(new NotificationAccess.Actor(1,11),event,"REVIEWER",request));
        eligible.put("applicantEligible",false);assertFalse(access.visible(new NotificationAccess.Actor(1,10),event,"APPLICANT",request));assertFalse(access.visible(new NotificationAccess.Actor(2,10),event,"APPLICANT",request));
    }
    @Test void actorNeverComesFromBrowserAndWritesRequireCurrentCsrfAndOrigin(){
        request.setAttribute("ias.chain.edge","gateway-notification");request.addHeader("X-IAS-User","999");assertEquals(new NotificationAccess.Actor(1,10),access.actor(request,false));
        assertEquals(403,assertThrows(Problem.class,()->access.actor(request,true)).status);
        request.addHeader("Origin","http://127.0.0.1:5173");request.addHeader("X-CSRF-TOKEN","current-csrf");assertEquals(new NotificationAccess.Actor(1,10),access.actor(request,true));assertEquals("NOTIFICATION_WRITE",calls.getLast().get("purpose"));assertEquals("current-csrf",calls.getLast().get("csrfToken"));
    }
    @Test void revokedOrUnavailableOwnerDoesNotGrantHistoricalAccess(){
        for(int status:List.of(403,404,503)){
            access=new TrustedNotificationAccess((edge,path,body,r)->{throw new Problem(status,"OWNER_REJECTED");});
            if(status==503)assertEquals(503,assertThrows(Problem.class,()->access.visible(new NotificationAccess.Actor(1,10),event,"APPLICANT",request)).status);
            else assertFalse(access.visible(new NotificationAccess.Actor(1,10),event,"APPLICANT",request));
        }
    }
    @Test void staleOwnerFactHidesOnlyThatMessageAndUnknownFailuresStillPropagate(){
        for(String code:List.of("FEE_NOTIFICATION_FACT_STALE","FEE_NOTIFICATION_SCOPE_REVOKED","PART_FEE_NOTIFICATION_FACT_STALE","PART_FEE_NOTIFICATION_SCOPE_REVOKED","MONEY_NOTIFICATION_FACT_STALE","MONEY_NOTIFICATION_SCOPE_REVOKED")){
            access=new TrustedNotificationAccess((edge,path,body,r)->{throw new Problem(409,code);});
            assertFalse(access.visible(new NotificationAccess.Actor(1,10),event,"APPLICANT",request));
            assertEquals(409,assertThrows(Problem.class,()->access.recipients(event,request)).status);
        }
        access=new TrustedNotificationAccess((edge,path,body,r)->{throw new Problem(409,"UNEXPECTED_CONFLICT");});assertEquals(409,assertThrows(Problem.class,()->access.visible(new NotificationAccess.Actor(1,10),event,"APPLICANT",request)).status);
    }
    @Test void moneyDeliveryAndReadRecheckFrozenOwnerBindingAndCurrentRecipients(){
        var terminal=new MoneyFinalizedEvent(MoneyFinalizedEvent.COLLECTION,MoneyFinalizedEventTest.payload("COLLECTION_RECORDED",1,100,null));event=terminal.domainEvent();request.setAttribute("ias.chain.edge","finance-notification");facts.clear();facts.put("eventId",event.eventId().toString());facts.put("eventType",event.type());facts.put("schemaVersion","1");facts.put("tenantId",event.tenantId());for(String key:List.of("companyId","groupId","billId","completionId","accountVersion","action","businessId","amountCents","authorityDigest","executionReceiptsDigest"))facts.put(key,event.payload().get(key));facts.put("originalPaymentId",null);facts.put("occurredAt",event.payload().get("moneyOccurredAt"));facts.put("departmentId","3");facts.put("applicantUserId","10");facts.put("checkedAt",Instant.now().toString());
        assertEquals(3,access.recipients(event,request).size());request.setAttribute("ias.chain.edge","gateway-notification");assertTrue(access.visible(new NotificationAccess.Actor(1,11),event,"REVIEWER",request));eligible.put("reviewerUserIds",List.of());assertFalse(access.visible(new NotificationAccess.Actor(1,11),event,"REVIEWER",request));facts.put("authorityDigest","c".repeat(64));assertEquals(503,assertThrows(Problem.class,()->access.visible(new NotificationAccess.Actor(1,10),event,"APPLICANT",request)).status);
    }
    @Test void partUsesOnlyStockOwnerContextAndRejectsRevocationOrFieldMismatch(){
        UUID operation=UUID.randomUUID();String receipt=UUID.randomUUID().toString();event=new DomainEvent(operation,"inventory.consumed",1,"1",receipt,"4",Instant.parse("2026-10-27T06:00:00.000Z"),"stock",operation,Map.ofEntries(Map.entry("ticketId",UUID.randomUUID().toString()),Map.entry("roundId",UUID.randomUUID().toString()),Map.entry("executionId",receipt),Map.entry("feeGroupId",UUID.randomUUID().toString()),Map.entry("generation","4"),Map.entry("lineId",UUID.randomUUID().toString()),Map.entry("warehouseId","11"),Map.entry("partId","101"),Map.entry("quantity","3"),Map.entry("amountCents","1200"),Map.entry("companyId","2")));request.setAttribute("ias.chain.edge","stock-notification");
        facts.clear();for(String key:List.of("companyId","ticketId","roundId","feeGroupId","generation","lineId","warehouseId","partId","quantity","amountCents"))facts.put(key,event.payload().get(key));facts.put("eventId",event.eventId().toString());facts.put("eventType",event.type());facts.put("tenantId",event.tenantId());facts.put("executionReceiptId",receipt);facts.put("actorUserId","10");facts.put("coverageDigest","a".repeat(64));facts.put("factVersion","4");facts.put("eventVersion","1");facts.put("currentScope",true);facts.put("qualified",true);facts.put("revoked",false);facts.put("checkedAt",Instant.now().toString());
        var routes=new ArrayList<String>();access=new TrustedNotificationAccess((edge,path,body,r)->{routes.add(edge+" "+path);return facts;});assertEquals(List.of(new NotificationAccess.Recipient(10,"ENGINEER")),access.recipients(event,request));assertEquals("notification-stock /internal/stock/part-fee-notification-context",routes.getFirst());
        facts.put("executionReceiptId",UUID.randomUUID().toString());assertEquals(503,assertThrows(Problem.class,()->access.recipients(event,request)).status);facts.put("executionReceiptId",receipt);facts.put("currentScope",false);assertEquals(503,assertThrows(Problem.class,()->access.recipients(event,request)).status);
    }
    @Test void customerTicketUsesTicketOwnedContextAndCurrentMembershipAtRead(){
        UUID operation=UUID.randomUUID(),ticket=UUID.randomUUID(),round=UUID.randomUUID(),record=UUID.randomUUID();
        event=new DomainEvent(operation,"ticket.accepted",1,"1",ticket.toString(),"5",Instant.parse("2026-10-28T07:00:00.000Z"),"ticket",operation,Map.ofEntries(Map.entry("ticketId",ticket.toString()),Map.entry("roundId",round.toString()),Map.entry("companyId","2"),Map.entry("stateCode","WAITING_ASSIGNMENT"),Map.entry("action","ACCEPT"),Map.entry("processingRecordId",record.toString())));
        request.setAttribute("ias.chain.edge","ticket-notification");
        facts.clear();facts.putAll(Map.of("tenantId","1","eventId",event.eventId().toString(),"eventType",event.type(),"ticketId",ticket.toString(),"companyId","2","recipientUserId","10","aggregateVersion","5","checkedAt",Instant.now().toString()));
        var paths=new ArrayList<String>();access=new TrustedNotificationAccess((edge,path,body,r)->{paths.add(edge+" "+path);calls.add(new HashMap<>(body));return facts;});
        assertEquals(List.of(new NotificationAccess.Recipient(10,"CUSTOMER")),access.recipients(event,request));
        assertEquals("notification-ticket /internal/ticket/customer-notification-context",paths.getFirst());assertEquals("DELIVERY",calls.getFirst().get("purpose"));assertEquals(ticket.toString(),calls.getFirst().get("ticketId"));
        request.setAttribute("ias.chain.edge","gateway-notification");assertTrue(access.visible(new NotificationAccess.Actor(1,10),event,"CUSTOMER",request));assertFalse(access.visible(new NotificationAccess.Actor(1,11),event,"CUSTOMER",request));assertFalse(access.visible(new NotificationAccess.Actor(2,10),event,"CUSTOMER",request));
        assertEquals("READ",calls.getLast().get("purpose"));assertEquals("current-session",calls.getLast().get("sessionToken"));
        request.setAttribute("ias.chain.edge","ticket-notification");facts.put("eventId",UUID.randomUUID().toString());assertEquals(503,assertThrows(Problem.class,()->access.recipients(event,request)).status);
    }
    @Test void partOwner404AndTimeoutFailClosed(){
        UUID operation=UUID.randomUUID();String receipt=UUID.randomUUID().toString();event=new DomainEvent(operation,"inventory.consumed",1,"1",receipt,"1",Instant.parse("2026-10-27T06:00:00.000Z"),"stock",operation,Map.ofEntries(Map.entry("ticketId",UUID.randomUUID().toString()),Map.entry("roundId",UUID.randomUUID().toString()),Map.entry("executionId",receipt),Map.entry("feeGroupId",UUID.randomUUID().toString()),Map.entry("generation","1"),Map.entry("lineId",UUID.randomUUID().toString()),Map.entry("warehouseId","11"),Map.entry("partId","101"),Map.entry("quantity","1"),Map.entry("amountCents","1"),Map.entry("companyId","2")));request.setAttribute("ias.chain.edge","stock-notification");
        for(int status:List.of(404,503)){access=new TrustedNotificationAccess((edge,path,body,r)->{throw new Problem(status,"OWNER_FAILED");});assertEquals(status,assertThrows(Problem.class,()->access.recipients(event,request)).status);}
    }

    @Test void feeCompletionUsesFeeQualificationsAndRechecksTheirRemoval(){
        UUID application=UUID.randomUUID();
        event=new DomainEvent(UUID.randomUUID(),"approval.fee_completed",1,"1",application.toString(),"4",Instant.parse("2026-11-16T12:00:00.000Z"),"approval",UUID.randomUUID(),Map.ofEntries(Map.entry("ticketId",UUID.randomUUID().toString()),Map.entry("roundId",UUID.randomUUID().toString()),Map.entry("feeGroupId",UUID.randomUUID().toString()),Map.entry("generation","1"),Map.entry("completionId",UUID.randomUUID().toString()),Map.entry("applicationId",application.toString()),Map.entry("quoteId",UUID.randomUUID().toString()),Map.entry("quoteVersion","1"),Map.entry("companyId","2"),Map.entry("stateCode","COMPLETED")));
        var feeEligible=new HashMap<String,Object>(Map.of("tenantId","1","companyId","2","departmentId","3","applicantUserId","10","supervisorUserIds",List.of("11"),"financeUserIds",List.of("11","12"),"applicantEligible",true,"checkedAt",Instant.now().toString()));
        var paths=new ArrayList<String>();
        access=new TrustedNotificationAccess((edge,path,body,r)->{
            paths.add(path);
            if(path.equals("/internal/identity/fee-recipients"))return feeEligible;
            assertFalse(path.equals("/internal/identity/approval-recipients"),"Fee notifications must not borrow parts qualifications");
            var owner=new HashMap<String,Object>(body);owner.remove("purpose");owner.remove("sessionToken");owner.putAll(Map.of("companyId","2","departmentId","3","applicantUserId","10","checkedAt",Instant.now().toString()));return owner;
        });
        assertEquals(List.of(new NotificationAccess.Recipient(11,"REVIEWER"),new NotificationAccess.Recipient(12,"REVIEWER"),new NotificationAccess.Recipient(10,"APPLICANT")),access.recipients(event,request));
        assertTrue(paths.contains("/internal/identity/fee-recipients"));
        request.setAttribute("ias.chain.edge","gateway-notification");
        assertTrue(access.visible(new NotificationAccess.Actor(1,11),event,"REVIEWER",request));
        feeEligible.put("supervisorUserIds",List.of());feeEligible.put("financeUserIds",List.of("12"));
        assertFalse(access.visible(new NotificationAccess.Actor(1,11),event,"REVIEWER",request));
        feeEligible.put("companyId","99");
        assertEquals(503,assertThrows(Problem.class,()->access.visible(new NotificationAccess.Actor(1,12),event,"REVIEWER",request)).status);
    }
}
