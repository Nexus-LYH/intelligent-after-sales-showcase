package com.ias.notification;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class JdbcDeliveryTest {
    JdbcDataSource db;JdbcDelivery service;DomainEvent event;Connection schemaConnection;
    @BeforeEach void prepare()throws Exception {
        db=new JdbcDataSource();db.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        String schema=new String(getClass().getResourceAsStream("/db/migration/V1__durable_delivery.sql").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        schema=schema.replaceAll("CHARACTER SET ascii COLLATE ascii_bin","").replace("ENGINE=InnoDB","").replace(" KEY ix_delivery_due(state,next_attempt_at,lease_until),","");
        // H2 2.4.240 constant-set CHECK expressions retain the DDL session. Keep that session alive;
        // production MySQL SQL/checks are not weakened and each business operation uses a fresh connection.
        schemaConnection=db.getConnection();try(Statement s=schemaConnection.createStatement()){s.execute("SET TIME ZONE 'UTC'");for(String statement:schema.split(";"))if(!statement.isBlank())s.execute(statement);}
        service=new JdbcDelivery(db,3,Duration.ofSeconds(30));event=event(UUID.randomUUID(),"1");
    }
    @AfterEach void closeSchemaSession()throws Exception{if(schemaConnection!=null)schemaConnection.close();}
    DomainEvent event(UUID id,String version){return new DomainEvent(id,"approval.approved",1,"1","request-1",version,Instant.parse("2026-10-21T01:00:00.123Z"),"approval",UUID.randomUUID(),Map.of("ticketId","ticket-1","requestId","request-1"));}
    void append()throws Exception{service.transaction(c->{service.append(c,event,Set.of("notification"));return null;});}
    long count(String table)throws Exception{try(var c=db.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}}
    String state()throws Exception{try(var c=db.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT state FROM event_delivery")){r.next();return r.getString(1);}}
    void due()throws Exception{try(var c=db.getConnection();var s=c.createStatement()){s.executeUpdate("UPDATE event_delivery SET next_attempt_at='2000-01-01',lease_until='2000-01-01'");}}
    @Test void producerOutboxAndBusinessUseOneTransaction()throws Exception {
        assertThrows(Exception.class,()->service.transaction(c->{service.append(c,event,Set.of("notification"));JdbcDelivery.signal(c,event);throw new Exception("injected");}));
        assertEquals(0,count("event_outbox"));assertEquals(0,count("event_delivery"));assertEquals(0,count("notification_signal"));
        try(var c=db.getConnection()){assertThrows(IllegalArgumentException.class,()->service.append(c,event,Set.of("notification")));}
    }
    @Test void persistentDeliveryAndCommittedDuplicateHaveSingleEffect()throws Exception {
        append();assertTrue(service.pumpOne((id,e)->service.consume(id,"approval",e,JdbcDelivery::signal)));
        assertEquals("DELIVERED",state());assertEquals(1,count("event_inbox"));assertEquals(1,count("notification_signal"));
        assertEquals(JdbcDelivery.Ack.COMMITTED_DUPLICATE,service.consume("notification","approval",event,JdbcDelivery::signal));assertEquals(1,count("notification_signal"));
    }
    @Test void ownerWorkerAfterRestartDrainsPersistedClaimWithoutChangingOriginalEnvelope()throws Exception {
        append();var abandoned=service.claim().orElseThrow();due();
        var restarted=new JdbcDelivery(db,3,Duration.ofSeconds(30));
        try(var worker=new DeliveryWorker(restarted,(id,e)->{
            assertEquals(event.eventId(),e.eventId());assertEquals(event.sha(),e.sha());
            restarted.consume(id,"approval",e,JdbcDelivery::signal);
        },10,Duration.ofMillis(100))){
            worker.tick();assertEquals(1,worker.status().claimed());assertEquals(0,worker.status().failedTicks());
        }
        assertEquals("DELIVERED",state());assertEquals(1,count("event_inbox"));assertEquals(1,count("notification_signal"));
        assertFalse(service.complete(abandoned));
    }
    @Test void inboxAndEffectRollbackTogetherAndOnlyCommitGetsAck()throws Exception {
        assertThrows(Exception.class,()->service.consume("notification","approval",event,(c,e)->{JdbcDelivery.signal(c,e);throw new Exception("business failure");}));
        assertEquals(0,count("event_inbox"));assertEquals(0,count("notification_signal"));
        assertEquals(JdbcDelivery.Ack.COMMITTED,service.consume("notification","approval",event,JdbcDelivery::signal));
    }
    @Test void successfulConsumeLostAckRedeliversIdenticalEvent()throws Exception {
        append();assertTrue(service.pumpOne((id,e)->{service.consume(id,"approval",e,JdbcDelivery::signal);throw new Exception("ack lost");}));
        assertEquals("PENDING",state());assertEquals(1,count("notification_signal"));due();
        assertTrue(service.pumpOne((id,e)->{assertEquals(event.eventId(),e.eventId());assertEquals(event.sha(),e.sha());service.consume(id,"approval",e,JdbcDelivery::signal);}));
        assertEquals("DELIVERED",state());assertEquals(1,count("notification_signal"));
    }
    @Test void expiredLeaseRecoversAndOldCompletionCannotStealCurrentClaim()throws Exception {
        append();var old=service.claim().orElseThrow();assertTrue(service.claim().isEmpty());due();var current=service.claim().orElseThrow();
        assertEquals(old.event().eventId(),current.event().eventId());assertNotEquals(old.lease(),current.lease());
        assertFalse(service.complete(old));assertFalse(service.failed(old));assertTrue(service.complete(current));assertEquals("DELIVERED",state());
    }
    @Test void finiteRetriesDeadLetterReplayIsScopedAuditedAndSameKeyIdempotent()throws Exception {
        append();for(int i=0;i<3;i++){assertTrue(service.pumpOne((id,e)->{throw new Exception("dependency unavailable");}));due();}
        assertEquals("DEAD",state());assertTrue(service.claim().isEmpty());UUID op=UUID.randomUUID();
        assertThrows(JdbcDelivery.DeliveryFailure.class,()->service.replay(op,"2",7,event.eventId(),"notification","manual replay",(a,b,c,d)->true));
        assertThrows(JdbcDelivery.DeliveryFailure.class,()->service.replay(op,"1",7,event.eventId(),"notification","manual replay",(a,b,c,d)->false));
        assertEquals(0,count("event_replay_audit"));service.replay(op,"1",7,event.eventId(),"notification","manual replay",(a,b,c,d)->true);
        service.replay(op,"1",7,event.eventId(),"notification","manual replay",(a,b,c,d)->true);
        assertEquals(1,count("event_replay_audit"));assertEquals("PENDING",state());
        assertThrows(JdbcDelivery.DeliveryFailure.class,()->service.replay(op,"1",7,event.eventId(),"notification","different reason",(a,b,c,d)->true));
        assertThrows(JdbcDelivery.DeliveryFailure.class,()->service.replay(op,"1",7,event.eventId(),"notification","manual replay",(a,b,c,d)->false));
    }
    @Test void repeatedWorkerCrashCannotRetryForever()throws Exception {
        append();for(int i=0;i<3;i++){assertTrue(service.claim().isPresent());due();}assertTrue(service.claim().isEmpty());assertEquals("DEAD",state());
    }
    @Test void authenticateProducerAndRejectContentReuse()throws Exception {
        assertThrows(JdbcDelivery.DeliveryFailure.class,()->service.consume("notification","stock",event,JdbcDelivery::signal));assertEquals(0,count("event_inbox"));
        service.consume("notification","approval",event,JdbcDelivery::signal);
        assertThrows(JdbcDelivery.DeliveryFailure.class,()->service.consume("notification","approval",event(event.eventId(),"2"),JdbcDelivery::signal));
        assertEquals(1,count("notification_signal"));
    }
    @Test void concurrentDuplicateHasOneCommittedEffect()throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{Callable<JdbcDelivery.Ack> work=()->{gate.await();return service.consume("notification","approval",event,JdbcDelivery::signal);};var a=pool.submit(work);var b=pool.submit(work);gate.countDown();
            var results=List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));assertTrue(results.contains(JdbcDelivery.Ack.COMMITTED));assertTrue(results.contains(JdbcDelivery.Ack.COMMITTED_DUPLICATE));assertEquals(1,count("event_inbox"));assertEquals(1,count("notification_signal"));
        }finally{pool.shutdownNow();}
    }
    @Test void replayAuditFailureRollsBackDeliveryReset()throws Exception {
        append();for(int i=0;i<3;i++){service.pumpOne((id,e)->{throw new Exception();});due();}
        try(var c=db.getConnection();var s=c.createStatement()){s.execute("ALTER TABLE event_replay_audit ADD CONSTRAINT reject_test CHECK(actor_id<>7)");}
        assertThrows(SQLException.class,()->service.replay(UUID.randomUUID(),"1",7,event.eventId(),"notification","manual replay",(a,b,c,d)->true));assertEquals("DEAD",state());assertEquals(0,count("event_replay_audit"));
    }
    @Test void poisonedDurableEnvelopeEntersDeadLetterWithoutHotRetry()throws Exception {
        append();try(var c=db.getConnection();var s=c.createStatement()){s.executeUpdate("UPDATE event_outbox SET envelope_json='{}'");}
        assertTrue(service.claim().isEmpty());assertEquals("DEAD",state());assertTrue(service.claim().isEmpty());
    }
    @Test void claimHasOneLeaseUnderConcurrentWorkers()throws Exception {
        append();ExecutorService pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{Callable<Optional<JdbcDelivery.Claim>> task=()->{gate.await();return service.claim();};var a=pool.submit(task);var b=pool.submit(task);gate.countDown();assertEquals(1,(a.get(15,TimeUnit.SECONDS).isPresent()?1:0)+(b.get(15,TimeUnit.SECONDS).isPresent()?1:0));}finally{pool.shutdownNow();}
    }
}
