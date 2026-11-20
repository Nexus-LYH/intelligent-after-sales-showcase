package com.ias.notification;

import com.ias.trust.Problem;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SuppressedPartRepairTest {
    FeeNotificationTest fixture;
    DomainEvent event;
    MockHttpServletRequest request;
    @BeforeEach void prepare()throws Exception{
        fixture=new FeeNotificationTest();fixture.prepare();
        event=fixture.part(UUID.randomUUID(),1);
        request=new MockHttpServletRequest();request.setSecure(true);request.setAttribute("ias.chain.edge","stock-notification");
        fixture.sql("INSERT INTO event_inbox VALUES('notification','"+event.eventId()+"',1,'"+event.sha()+"',CURRENT_TIMESTAMP)");
        fixture.sql("INSERT INTO fee_notification_suppression VALUES('"+event.eventId()+"',1,'"+event.payload().get("feeGroupId")+"','OUT_OF_ORDER_VERSION',1,1,CURRENT_TIMESTAMP)");
    }
    @AfterEach void close()throws Exception{fixture.close();}
    @Test void repairKeepsOriginalConsumptionAndAuditAndReplaysSameOperation()throws Exception{
        UUID operation=UUID.randomUUID();
        var first=fixture.inbox.repairSuppressedPart(operation,event,"verified cross-producer version defect",request);
        var repeated=fixture.inbox.repairSuppressedPart(operation,event,"verified cross-producer version defect",request);
        assertEquals(first,repeated);
        assertEquals(1,fixture.count("event_inbox"));
        assertEquals(1,fixture.count("fee_notification_suppression"));
        assertEquals(1,fixture.count("notification_suppression_repair"));
        assertEquals(1,fixture.count("notification_message"));
        assertEquals(1,fixture.count("notification_email_capture"));
        assertEquals(409,assertThrows(Problem.class,()->fixture.inbox.repairSuppressedPart(operation,event,"changed reason",request)).status);
        assertEquals(409,assertThrows(Problem.class,()->fixture.inbox.repairSuppressedPart(UUID.randomUUID(),event,"another operation",request)).status);
    }
    @Test void messageCaptureFailureRollsBackRepairAuditAndPreservesOriginal()throws Exception{
        fixture.sql("ALTER TABLE notification_email_capture ADD CONSTRAINT injected_repair CHECK(recipient_user_id<>10)");
        assertThrows(Exception.class,()->fixture.inbox.repairSuppressedPart(UUID.randomUUID(),event,"repair",request));
        assertEquals(0,fixture.count("notification_suppression_repair"));
        assertEquals(0,fixture.count("notification_message"));
        assertEquals(1,fixture.count("event_inbox"));
        assertEquals(1,fixture.count("fee_notification_suppression"));
    }
    @Test void wrongServiceAndChangedEventCannotRepair()throws Exception{
        request.setAttribute("ias.chain.edge","ticket-notification");
        assertEquals(403,assertThrows(Problem.class,()->fixture.inbox.repairSuppressedPart(UUID.randomUUID(),event,"repair",request)).status);
        request.setAttribute("ias.chain.edge","stock-notification");
        var payload=new HashMap<>(event.payload());payload.put("amountCents","1300");
        var changed=new DomainEvent(event.eventId(),event.type(),1,event.tenantId(),event.aggregateId(),event.aggregateVersion(),event.occurredAt(),event.producer(),event.correlationId(),payload);
        assertEquals(409,assertThrows(Problem.class,()->fixture.inbox.repairSuppressedPart(UUID.randomUUID(),changed,"repair",request)).status);
        assertEquals(0,fixture.count("notification_suppression_repair"));
    }
    @Test void currentOwnerQualificationIsCheckedAgainOnRepair()throws Exception{
        NotificationAccess revoked=new NotificationAccess(){
            public Actor actor(jakarta.servlet.http.HttpServletRequest request,boolean write){return new Actor(1,10);}
            public List<Recipient> recipients(DomainEvent event,jakarta.servlet.http.HttpServletRequest request){throw new Problem(403,"REVOKED");}
            public boolean visible(Actor actor,DomainEvent event,String kind,jakarta.servlet.http.HttpServletRequest request){return false;}
        };
        var inbox=new MessageInbox(fixture.db,revoked);
        assertEquals(403,assertThrows(Problem.class,()->inbox.repairSuppressedPart(UUID.randomUUID(),event,"repair",request)).status);
        assertEquals(0,fixture.count("notification_suppression_repair"));
    }
}
