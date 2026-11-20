package com.ias.notification;

import com.ias.trust.Problem;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class MessageInboxTest {
    JdbcDataSource db;Connection ddl;MessageInbox inbox;FakeAccess access;DomainEvent event;
    final NotificationAccess.Actor actor=new NotificationAccess.Actor(1,10);
    final MockHttpServletRequest request=new MockHttpServletRequest();
    @BeforeEach void prepare()throws Exception {
        db=new JdbcDataSource();db.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        ddl=db.getConnection();try(var s=ddl.createStatement()){
            s.execute("SET TIME ZONE 'UTC'");
            for(String file:List.of("V1__durable_delivery.sql","V2__recipient_messages.sql","V3__fee_notifications.sql","V5__separate_notification_version_domains.sql")){
                String sql=new String(getClass().getResourceAsStream("/db/migration/"+file).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)
                    .replaceAll("CHARACTER SET ascii COLLATE ascii_bin","").replace("ENGINE=InnoDB","")
                    .replace(" KEY ix_delivery_due(state,next_attempt_at,lease_until),","").replace(" KEY ix_my_messages(tenant_id,recipient_user_id,created_at,id),","");
                for(String statement:sql.split(";"))if(!statement.isBlank())s.execute(statement);
            }
        }
        access=new FakeAccess();inbox=new MessageInbox(db,access);event=event();
    }
    @AfterEach void close()throws Exception{ddl.close();}
    static DomainEvent event(){String approval=UUID.randomUUID().toString();return new DomainEvent(UUID.randomUUID(),"approval.submitted",1,"1",approval,"1",Instant.parse("2026-10-22T00:00:00.123Z"),"approval",UUID.randomUUID(),Map.of("requestId",approval,"ticketId",UUID.randomUUID().toString()));}
    long count(String table)throws Exception{try(var c=db.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}}
    void sql(String value)throws Exception{try(var s=ddl.createStatement()){s.execute(value);}}
    @SuppressWarnings("unchecked") List<Map<String,Object>> messages()throws Exception{return (List<Map<String,Object>>)inbox.list(actor,1,20,request).get("items");}
    String receive()throws Exception{inbox.receive(event,request);return (String)messages().getFirst().get("id");}
    @Test void actualMessagesAndInboxCommitTogetherAndLostAckDeduplicates()throws Exception {
        assertEquals("COMMITTED",inbox.receive(event,request).get("ack"));
        assertEquals("COMMITTED_DUPLICATE",inbox.receive(event,request).get("ack"));
        assertEquals(1,count("event_inbox"));assertEquals(2,count("notification_message"));assertEquals(1,messages().size());
        assertEquals(Set.of("eventId","ack"),inbox.receive(event,request).keySet());
    }
    @Test void messageInsertFailureRollsBackInboxAndEveryRecipient()throws Exception {
        sql("ALTER TABLE notification_message ADD CONSTRAINT injected CHECK(recipient_user_id<>11)");
        assertThrows(Exception.class,()->inbox.receive(event,request));assertEquals(0,count("event_inbox"));assertEquals(0,count("notification_message"));
        sql("ALTER TABLE notification_message DROP CONSTRAINT injected");assertEquals("COMMITTED",inbox.receive(event,request).get("ack"));
    }
    @Test void tenantAndRecipientCannotReadOrMarkAnotherInbox()throws Exception {
        String id=receive();
        for(var wrong:List.of(new NotificationAccess.Actor(2,10),new NotificationAccess.Actor(1,12))){
            assertEquals(0,inbox.list(wrong,1,20,request).get("total"));
            assertEquals(404,assertThrows(Problem.class,()->inbox.detail(wrong,id,request)).status);
            assertEquals(404,assertThrows(Problem.class,()->inbox.markRead(wrong,id,UUID.randomUUID().toString(),request)).status);
        }
        assertEquals(0,count("notification_read_operation"));
    }
    @Test void withdrawnCurrentQualificationHidesHistoricalMessagesAndReadResults()throws Exception {
        String id=receive(),key=UUID.randomUUID().toString();inbox.markRead(actor,id,key,request);access.visible=false;
        assertEquals(0,inbox.list(actor,1,20,request).get("total"));
        assertEquals(404,assertThrows(Problem.class,()->inbox.detail(actor,id,request)).status);
        assertEquals(404,assertThrows(Problem.class,()->inbox.markRead(actor,id,key,request)).status);
        assertEquals(404,assertThrows(Problem.class,()->inbox.readOperation(actor,key,request)).status);
    }
    @Test void originalReadKeyRestoresStableResultAndAnotherKeyDoesNotChangeFirstRead()throws Exception {
        String id=receive(),key=UUID.randomUUID().toString();var result=inbox.markRead(actor,id,key,request);
        assertEquals(result,inbox.readOperation(actor,key,request));assertEquals(result,inbox.markRead(actor,id,key,request));
        var second=inbox.markRead(actor,id,UUID.randomUUID().toString(),request);assertEquals(result.get("readAt"),second.get("readAt"));
        assertEquals(1,count("notification_message_read"));assertEquals(2,count("notification_read_operation"));assertEquals(2,count("notification_read_audit"));
        assertEquals("OPERATION_NOT_OBSERVED",assertThrows(Problem.class,()->inbox.readOperation(actor,UUID.randomUUID().toString(),request)).code);
    }
    @Test void oneOriginalKeyCannotMoveToAnotherMessage()throws Exception {
        String first=receive(),key=UUID.randomUUID().toString();inbox.markRead(actor,first,key,request);
        event=event();inbox.receive(event,request);String second=messages().stream().map(m->(String)m.get("id")).filter(id->!id.equals(first)).findFirst().orElseThrow();
        assertEquals(409,assertThrows(Problem.class,()->inbox.markRead(actor,second,key,request)).status);assertEquals(1,count("notification_message_read"));
    }
    @Test void auditFailureRollsBackReadFactOperationAndActorLock()throws Exception {
        String id=receive(),key=UUID.randomUUID().toString();sql("ALTER TABLE notification_read_audit ADD CONSTRAINT injected CHECK(actor_user_id<>10)");
        assertThrows(Exception.class,()->inbox.markRead(actor,id,key,request));
        for(String table:List.of("notification_message_read","notification_read_operation","notification_read_audit","notification_reader_lock"))assertEquals(0,count(table));
        sql("ALTER TABLE notification_read_audit DROP CONSTRAINT injected");assertEquals("READ",inbox.markRead(actor,id,key,request).get("state"));
    }
    @Test void concurrentSameKeyHasOneFactAndAudit()throws Exception {
        String id=receive(),key=UUID.randomUUID().toString();var gate=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var futures=new ArrayList<Future<Map<String,Object>>>();for(int i=0;i<6;i++)futures.add(pool.submit(()->{gate.await();return inbox.markRead(actor,id,key,new MockHttpServletRequest());}));
            gate.countDown();var expected=futures.getFirst().get(15,TimeUnit.SECONDS);for(var future:futures)assertEquals(expected,future.get(15,TimeUnit.SECONDS));
        }
        assertEquals(1,count("notification_message_read"));assertEquals(1,count("notification_read_operation"));assertEquals(1,count("notification_read_audit"));
    }
    @Test void dependencyFailureClosesListAndDoesNotPersistRead()throws Exception {
        String id=receive();access.unavailable=true;assertEquals(503,assertThrows(Problem.class,()->inbox.list(actor,1,20,request)).status);
        assertEquals(503,assertThrows(Problem.class,()->inbox.markRead(actor,id,UUID.randomUUID().toString(),request)).status);assertEquals(0,count("notification_read_audit"));
    }
    @Test void listChecksManyOwnersConcurrentlyWithoutChangingOrderOrAccessRules()throws Exception {
        for(int i=0;i<20;i++)inbox.receive(event(),request);
        var expected=new ArrayList<String>();try(var q=ddl.prepareStatement("SELECT id FROM notification_message WHERE recipient_user_id=10 ORDER BY created_at DESC,id DESC");var rows=q.executeQuery()){while(rows.next())expected.add(rows.getString(1));}
        var gate=new CountDownLatch(2);var active=new java.util.concurrent.atomic.AtomicInteger();var peak=new java.util.concurrent.atomic.AtomicInteger();
        NotificationAccess limited=new NotificationAccess(){public Actor actor(jakarta.servlet.http.HttpServletRequest r,boolean w){return actor;}public List<Recipient> recipients(DomainEvent e,jakarta.servlet.http.HttpServletRequest r){return access.recipients(e,r);}public boolean visible(Actor a,DomainEvent e,String k,jakarta.servlet.http.HttpServletRequest r){int n=active.incrementAndGet();peak.accumulateAndGet(n,Math::max);gate.countDown();try{if(!gate.await(2,TimeUnit.SECONDS))throw new AssertionError("checks did not overlap");return true;}catch(InterruptedException x){Thread.currentThread().interrupt();throw new IllegalStateException(x);}finally{active.decrementAndGet();}}};
        var list=(List<Map<String,Object>>)new MessageInbox(db,limited).list(actor,1,20,request).get("items");assertEquals(expected,list.stream().map(x->x.get("id").toString()).toList());assertTrue(peak.get()>=2&&peak.get()<=12);
    }
    @Test void concurrentOneKeyCannotBindTwoTargets()throws Exception {
        String first=receive(),key=UUID.randomUUID().toString();event=event();inbox.receive(event,request);
        String second=messages().stream().map(m->(String)m.get("id")).filter(id->!id.equals(first)).findFirst().orElseThrow();var gate=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var futures=new ArrayList<Future<Integer>>();for(String id:List.of(first,second))futures.add(pool.submit(()->{gate.await();try{inbox.markRead(actor,id,key,new MockHttpServletRequest());return 200;}catch(Problem p){return p.status;}}));
            gate.countDown();var statuses=new ArrayList<Integer>();for(var future:futures)statuses.add(future.get(15,TimeUnit.SECONDS));Collections.sort(statuses);assertEquals(List.of(200,409),statuses);
        }
        assertEquals(1,count("notification_message_read"));assertEquals(1,count("notification_read_operation"));assertEquals(1,count("notification_read_audit"));
    }
    static class FakeAccess implements NotificationAccess {
        volatile boolean visible=true,unavailable=false;
        public Actor actor(jakarta.servlet.http.HttpServletRequest r,boolean write){return new Actor(1,10);}
        public List<Recipient> recipients(DomainEvent e,jakarta.servlet.http.HttpServletRequest r){return List.of(new Recipient(10,"APPLICANT"),new Recipient(11,"REVIEWER"),new Recipient(10,"APPLICANT"));}
        public boolean visible(Actor a,DomainEvent e,String kind,jakarta.servlet.http.HttpServletRequest r){if(unavailable)throw new Problem(503,"DEPENDENCY_UNAVAILABLE");return visible;}
    }
}
