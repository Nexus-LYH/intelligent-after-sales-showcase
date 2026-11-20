package com.ias.approval;

import com.ias.notification.JdbcDelivery;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.ias.approval.PartsApproval.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real separate-owner H2 transactions and local transport; not MySQL, TLS or user notification acceptance. */
class ApprovalDeliveryTest {
    JdbcDataSource producer()throws Exception {
        var factory=ApprovalJdbcTest.class.getDeclaredMethod("database");factory.setAccessible(true);
        return (JdbcDataSource)factory.invoke(new ApprovalJdbcTest());
    }
    Application application(UUID operation) {
        var command=new Submission(operation,"1","2",UUID.randomUUID().toString(),UUID.randomUUID().toString(),"5","1","2",1,"repair purpose");
        var authority=new SubmissionAuthority("1","2",command.ticketId(),command.assignmentId(),command.departmentId(),command.warehouseId(),true,true,true,true);
        return submit(UUID.randomUUID(),command,authority,null,Instant.parse("2026-10-21T06:00:00Z"));
    }
    void write(JdbcApprovalStore store,UUID operation,Application app) {
        store.transact(tx->{tx.reserve("1","2",operation,"b".repeat(64),"SUBMIT");tx.insert(app);
            tx.complete("1","2",operation,"b".repeat(64),"SUBMIT",app,"approval.submitted");return null;});
    }
    long count(JdbcDataSource source,String table)throws Exception {
        try(var c=source.getConnection();var q=c.createStatement();var r=q.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}
    }
    @Test void approvalOwnTransactionQueuesEventAndDifferentOwnerConsumesOnceAfterLostAck()throws Exception {
        var source=producer();var store=new JdbcApprovalStore(source);UUID operation=UUID.randomUUID();var app=application(operation);
        write(store,operation,app);
        for(String table:List.of("parts_application","approval_operation","approval_audit","approval_outbox","event_outbox","event_delivery"))assertEquals(1,count(source,table));
        var destination=new JdbcDataSource();destination.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try(var c=destination.getConnection();var q=c.createStatement()) {
            q.execute("CREATE TABLE event_inbox(consumer_id VARCHAR(32),event_id VARCHAR(36),tenant_id BIGINT,envelope_sha CHAR(64),processed_at TIMESTAMP,PRIMARY KEY(consumer_id,event_id))");
            q.execute("CREATE TABLE notification_signal(event_id VARCHAR(36) PRIMARY KEY,tenant_id BIGINT,producer VARCHAR(32),event_type VARCHAR(80),aggregate_id VARCHAR(100),aggregate_version DECIMAL(20,0),occurred_at TIMESTAMP)");
        }
        var publisher=new JdbcDelivery(source,3,Duration.ofSeconds(30));var consumer=new JdbcDelivery(destination,3,Duration.ofSeconds(30));
        assertTrue(publisher.pumpOne((name,event)->{consumer.consume(name,"approval",event,JdbcDelivery::signal);throw new Exception("INJECTED_ACK_LOST_AFTER_CONSUMER_COMMIT");}));
        assertEquals(1,count(destination,"event_inbox"));assertEquals(1,count(destination,"notification_signal"));
        try(var c=source.getConnection();var q=c.createStatement()){q.executeUpdate("UPDATE event_delivery SET next_attempt_at='2000-01-01'");}
        assertTrue(publisher.pumpOne((name,event)->assertEquals(JdbcDelivery.Ack.COMMITTED_DUPLICATE,consumer.consume(name,"approval",event,JdbcDelivery::signal))));
        assertEquals(1,count(destination,"event_inbox"));assertEquals(1,count(destination,"notification_signal"));
        try(var c=source.getConnection();var q=c.createStatement();var r=q.executeQuery("SELECT state FROM event_delivery")){r.next();assertEquals("DELIVERED",r.getString(1));}
        // Original application receipt remains the original fact; replay creates no second message.
        assertEquals(app,store.transact(tx->tx.receipt("1","2",operation)).result());
        assertEquals(1,count(source,"event_outbox"));
    }
    @Test void dispatchRowFailureRollsBackBusinessAuditLegacyAndDurableOutboxes()throws Exception {
        var source=producer();var store=new JdbcApprovalStore(source);UUID operation=UUID.randomUUID();var app=application(operation);
        try(var c=source.getConnection();var q=c.createStatement()) {
            q.execute("ALTER TABLE event_delivery ADD CONSTRAINT reject_delivery CHECK(consumer_id<>'notification')");
            assertThrows(ApprovalFailure.class,()->write(store,operation,app));
            for(String table:List.of("parts_application","approval_operation","approval_audit","approval_outbox","event_outbox","event_delivery"))assertEquals(0,count(source,table));
            q.execute("ALTER TABLE event_delivery DROP CONSTRAINT reject_delivery");
        }
        write(store,operation,app);assertEquals(1,count(source,"event_delivery"));
    }
}
