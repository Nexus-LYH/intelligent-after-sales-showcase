package com.ias.notification;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FeeNotificationTest {
    JdbcDataSource db;Connection ddl;MessageInbox inbox;Access access;
    @BeforeEach void prepare()throws Exception{
        db=new JdbcDataSource();db.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        ddl=db.getConnection();try(var s=ddl.createStatement()){s.execute("SET TIME ZONE 'UTC'");for(String file:List.of("V1__durable_delivery.sql","V2__recipient_messages.sql","V3__fee_notifications.sql","V5__separate_notification_version_domains.sql","V7__suppressed_notification_repair_audit.sql","V8__suppression_repair_serialization.sql","V9__fee_recipient_repair_audit.sql")){
            String sql=new String(getClass().getResourceAsStream("/db/migration/"+file).readAllBytes(),StandardCharsets.UTF_8).replaceAll("CHARACTER SET ascii COLLATE ascii_bin","").replace("ENGINE=InnoDB","").replace(" KEY ix_delivery_due(state,next_attempt_at,lease_until),","").replace(" KEY ix_my_messages(tenant_id,recipient_user_id,created_at,id),","").replace(" KEY ix_fee_recipient_repair_event(tenant_id,event_id),","").replace("ALTER TABLE notification_message ADD COLUMN","ALTER TABLE notification_message ADD");
            for(String statement:sql.split(";"))if(!statement.isBlank())s.execute(statement);
        }}inbox=new MessageInbox(db,access=new Access());
    }
    @AfterEach void close()throws Exception{ddl.close();}
    DomainEvent event(UUID id,long generation,long version){String group=UUID.nameUUIDFromBytes("group".getBytes()).toString(),execution=UUID.nameUUIDFromBytes(("execution-"+generation).getBytes()).toString();return new DomainEvent(id,"ticket.fee_execution_recorded",1,"1",execution,Long.toString(version),Instant.parse("2026-10-26T06:00:00.000Z"),"ticket",UUID.randomUUID(),Map.ofEntries(Map.entry("ticketId",UUID.randomUUID().toString()),Map.entry("roundId",UUID.randomUUID().toString()),Map.entry("feeGroupId",group),Map.entry("completionId",UUID.randomUUID().toString()),Map.entry("executionId",execution),Map.entry("lineId",UUID.randomUUID().toString()),Map.entry("processingRecordId",UUID.randomUUID().toString()),Map.entry("assignmentId",UUID.randomUUID().toString()),Map.entry("generation",Long.toString(generation)),Map.entry("companyId","2"),Map.entry("kind","LABOR"),Map.entry("amountCents","100")));}
    DomainEvent part(UUID id,long generation){String execution=UUID.randomUUID().toString();return new DomainEvent(id,"inventory.consumed",1,"1",execution,Long.toString(generation),Instant.parse("2026-10-27T06:30:00.000Z"),"stock",id,Map.ofEntries(Map.entry("ticketId",UUID.randomUUID().toString()),Map.entry("roundId",UUID.randomUUID().toString()),Map.entry("executionId",execution),Map.entry("feeGroupId",UUID.nameUUIDFromBytes("part-group".getBytes()).toString()),Map.entry("generation",Long.toString(generation)),Map.entry("lineId",UUID.randomUUID().toString()),Map.entry("warehouseId","11"),Map.entry("partId","101"),Map.entry("quantity","2"),Map.entry("amountCents","1200"),Map.entry("companyId","2")));}
    long count(String table)throws Exception{try(var c=db.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}}
    String scalar(String sql)throws Exception{try(var c=db.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getString(1);}}
    void sql(String value)throws Exception{try(var c=db.getConnection();var s=c.createStatement()){s.executeUpdate(value);}}
    @Test void hundredPersistedBacklogRecoversAfterConsumerRestartAndDuplicateIsSuppressed()throws Exception{
        var request=new MockHttpServletRequest();var producer=new JdbcDelivery(db,6,java.time.Duration.ofSeconds(30));DomainEvent last=null;
        for(int i=1;i<=100;i++){last=event(UUID.randomUUID(),1,1);DomainEvent saved=last;producer.transaction(c->{producer.append(c,saved,Set.of("notification"));return null;});}
        assertEquals(0,count("event_inbox"));
        var restarted=new JdbcDelivery(db,6,java.time.Duration.ofSeconds(30));try(var worker=new DeliveryWorker(restarted,(id,e)->inbox.receive(e,request),100,java.time.Duration.ofMillis(100))){worker.tick();assertEquals(100,worker.status().claimed());}
        assertEquals(100,count("event_inbox"));assertEquals(100,count("notification_message"));assertEquals(100,count("notification_email_capture"));assertEquals(0,count("fee_notification_suppression"));
        assertEquals("COMMITTED_DUPLICATE",inbox.receive(last,request).get("ack"));
    }
    @Test void staleGenerationAndOutOfOrderVersionAreAuditedWithoutDelivery()throws Exception{
        var r=new MockHttpServletRequest();inbox.receive(event(UUID.randomUUID(),3,5),r);inbox.receive(event(UUID.randomUUID(),2,9),r);inbox.receive(event(UUID.randomUUID(),3,4),r);
        assertEquals(3,count("event_inbox"));assertEquals(1,count("notification_message"));assertEquals(1,count("notification_email_capture"));assertEquals(2,count("fee_notification_suppression"));
    }
    @Test void captureAuditFailureRollsBackInboxCursorAndMessage()throws Exception{
        try(var s=ddl.createStatement()){s.execute("ALTER TABLE notification_email_capture ADD CONSTRAINT injected CHECK(recipient_user_id<>10)");}
        var e=event(UUID.randomUUID(),1,1);assertThrows(Exception.class,()->inbox.receive(e,new MockHttpServletRequest()));assertEquals(0,count("event_inbox"));assertEquals(0,count("notification_message"));assertEquals(0,count("fee_notification_cursor"));
    }
    @Test void collectionPartialOverpaymentRefundReverseAndRebookProduceLocalNotifications()throws Exception{
        var request=new MockHttpServletRequest();String original=UUID.randomUUID().toString();var cases=List.of(
            new MoneyFinalizedEvent(MoneyFinalizedEvent.COLLECTION,MoneyFinalizedEventTest.payload("COLLECTION_RECORDED",1,50,null)),
            new MoneyFinalizedEvent(MoneyFinalizedEvent.COLLECTION,MoneyFinalizedEventTest.payload("COLLECTION_RECORDED",2,500,null)),
            new MoneyFinalizedEvent(MoneyFinalizedEvent.REFUND,MoneyFinalizedEventTest.payload("REFUND_VOUCHER_RECORDED",3,20,original)),
            new MoneyFinalizedEvent(MoneyFinalizedEvent.COLLECTION,MoneyFinalizedEventTest.payload("COLLECTION_REVERSED",4,0,original)),
            new MoneyFinalizedEvent(MoneyFinalizedEvent.COLLECTION,MoneyFinalizedEventTest.payload("COLLECTION_REBOOKED",5,50,original)));
        for(var value:cases)assertEquals("COMMITTED",inbox.receive(value.domainEvent(),request).get("ack"));
        assertEquals(5,count("event_inbox"));assertEquals(5,count("notification_message"));assertEquals(5,count("notification_email_capture"));assertEquals("收款已冲正重记",scalar("SELECT title FROM notification_message WHERE event_type='finance.collection_finalized' ORDER BY scope_generation DESC LIMIT 1"));
    }
    @Test void moneyAccountVersionRejectsOldAndOutOfOrderTerminalEvents()throws Exception{
        var r=new MockHttpServletRequest();inbox.receive(new MoneyFinalizedEvent(MoneyFinalizedEvent.COLLECTION,MoneyFinalizedEventTest.payload("COLLECTION_RECORDED",5,100,null)).domainEvent(),r);inbox.receive(new MoneyFinalizedEvent(MoneyFinalizedEvent.REFUND,MoneyFinalizedEventTest.payload("REFUND_VOUCHER_RECORDED",3,10,UUID.randomUUID().toString())).domainEvent(),r);
        assertEquals(2,count("event_inbox"));assertEquals(1,count("notification_message"));assertEquals(1,count("fee_notification_suppression"));
    }
    @Test void moneyFailureRetriesDeadLettersAndManualReplayUsesOriginalEvent()throws Exception{
        var event=new MoneyFinalizedEvent(MoneyFinalizedEvent.COLLECTION,MoneyFinalizedEventTest.payload("COLLECTION_RECORDED",1,100,null)).domainEvent();var producer=new JdbcDelivery(db,3,java.time.Duration.ofSeconds(30));producer.transaction(c->{producer.append(c,event,Set.of("notification"));return null;});
        for(int i=0;i<3;i++){assertTrue(producer.pumpOne((id,e)->{throw new Exception("notification stopped");}));sql("UPDATE event_delivery SET next_attempt_at='2000-01-01',lease_until='2000-01-01'");}
        assertEquals("DEAD",scalar("SELECT state FROM event_delivery"));UUID replay=UUID.randomUUID();producer.replay(replay,"1",10,event.eventId(),"notification","operator verified dependency recovery",(tenant,actor,consumer,original)->tenant.equals("1")&&actor==10&&consumer.equals("notification")&&original.eventId().equals(event.eventId()));
        sql("UPDATE event_delivery SET next_attempt_at='2000-01-01'");assertTrue(producer.pumpOne((id,e)->inbox.receive(e,new MockHttpServletRequest())));assertEquals("DELIVERED",scalar("SELECT state FROM event_delivery"));assertEquals(1,count("event_replay_audit"));assertEquals(1,count("notification_message"));
    }
    @Test void partDuplicateOldGenerationAndLostAckRecoveryRemainIdempotent()throws Exception{
        var request=new MockHttpServletRequest();var current=part(UUID.randomUUID(),2);assertEquals("COMMITTED",inbox.receive(current,request).get("ack"));assertEquals("COMMITTED_DUPLICATE",inbox.receive(current,request).get("ack"));inbox.receive(part(UUID.randomUUID(),1),request);
        assertEquals(2,count("event_inbox"));assertEquals(1,count("notification_message"));assertEquals(1,count("notification_email_capture"));assertEquals(1,count("fee_notification_suppression"));assertEquals("PART收费执行已登记",scalar("SELECT title FROM notification_message"));
        var next=part(UUID.randomUUID(),3);var producer=new JdbcDelivery(db,3,java.time.Duration.ofSeconds(1));producer.transaction(c->{producer.append(c,next,Set.of("notification"));return null;});assertTrue(producer.pumpOne((id,e)->{inbox.receive(e,request);throw new Exception("ACK lost");}));sql("UPDATE event_delivery SET next_attempt_at='2000-01-01',lease_until='2000-01-01'");assertTrue(producer.pumpOne((id,e)->inbox.receive(e,request)));assertEquals("DELIVERED",scalar("SELECT state FROM event_delivery"));assertEquals(2,count("notification_message"));
    }
    static final class Access implements NotificationAccess{
        volatile boolean visible=true;public Actor actor(jakarta.servlet.http.HttpServletRequest r,boolean w){return new Actor(1,10);}public List<Recipient> recipients(DomainEvent e,jakarta.servlet.http.HttpServletRequest r){return List.of(new Recipient(10,"APPLICANT"));}public boolean visible(Actor a,DomainEvent e,String k,jakarta.servlet.http.HttpServletRequest r){return visible;}
    }
    @Test void inventoryVersionIsIndependentOfTicketVersionInSameFeeGroup()throws Exception{
        var request=new MockHttpServletRequest();
        var labor=event(UUID.randomUUID(),1,10);
        var stock=part(UUID.randomUUID(),1);
        var payload=new HashMap<>(stock.payload());
        payload.put("feeGroupId",labor.payload().get("feeGroupId"));
        var sameGroupStock=new DomainEvent(stock.eventId(),stock.type(),1,stock.tenantId(),stock.aggregateId(),stock.aggregateVersion(),stock.occurredAt(),stock.producer(),stock.correlationId(),payload);
        inbox.receive(labor,request);
        inbox.receive(sameGroupStock,request);
        assertEquals(2,count("notification_message"));
        assertEquals(0,count("fee_notification_suppression"));
    }
    @Test void differentExecutionAggregatesHaveIndependentVersions()throws Exception{
        var request=new MockHttpServletRequest();
        inbox.receive(event(UUID.randomUUID(),1,10),request);
        var other=event(UUID.randomUUID(),1,9);
        String execution=UUID.randomUUID().toString();
        var payload=new HashMap<>(other.payload());payload.put("executionId",execution);
        inbox.receive(new DomainEvent(other.eventId(),other.type(),1,other.tenantId(),execution,other.aggregateVersion(),other.occurredAt(),other.producer(),other.correlationId(),payload),request);
        assertEquals(2,count("notification_message"));
        assertEquals(0,count("fee_notification_suppression"));
    }
    @Test void accountVersionDoesNotAdvanceFeeAuthorizationGeneration()throws Exception{
        var request=new MockHttpServletRequest();
        var labor=event(UUID.randomUUID(),1,10);
        var payload=new LinkedHashMap<>(MoneyFinalizedEventTest.payload("COLLECTION_RECORDED",20,100,null));
        payload.put("groupId",labor.payload().get("feeGroupId"));
        inbox.receive(new MoneyFinalizedEvent(MoneyFinalizedEvent.COLLECTION,payload).domainEvent(),request);
        inbox.receive(labor,request);
        assertEquals(2,count("notification_message"));
        assertEquals(0,count("fee_notification_suppression"));
    }
}
