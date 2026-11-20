package com.ias.approval;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.*;
import org.springframework.mock.web.MockHttpServletRequest;
import static com.ias.approval.PartsApproval.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real JDBC transaction tests with an H2-only portable schema; not MySQL migration acceptance. */
public class ApprovalJdbcTest {
    public static Timestamp utcNow(int precision) { return Timestamp.from(Instant.now()); }
    JdbcDataSource database() throws Exception {
        var source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        try (Connection connection = source.getConnection(); var sql = connection.createStatement()) {
            sql.execute("CREATE ALIAS IF NOT EXISTS UTC_TIMESTAMP FOR 'com.ias.approval.ApprovalJdbcTest.utcNow'");
            sql.execute("CREATE TABLE parts_application(id VARCHAR(36) PRIMARY KEY,tenant_id BIGINT,applicant_id BIGINT,root_application_id VARCHAR(36),revision_number INT,previous_application_id VARCHAR(36),state VARCHAR(32),row_version BIGINT,snapshot CLOB,created_at TIMESTAMP,updated_at TIMESTAMP,UNIQUE(tenant_id,previous_application_id),UNIQUE(tenant_id,root_application_id,revision_number))");
            sql.execute("CREATE TABLE approval_operation(tenant_id BIGINT,actor_id BIGINT,operation_id VARCHAR(36),command_hash VARCHAR(64),action VARCHAR(16),target_id VARCHAR(36),receipt CLOB,created_at TIMESTAMP,PRIMARY KEY(tenant_id,actor_id,operation_id))");
            sql.execute("CREATE TABLE approval_audit(audit_id VARCHAR(36) PRIMARY KEY,tenant_id BIGINT,actor_id BIGINT,operation_id VARCHAR(36),target_id VARCHAR(36),action VARCHAR(16),resulting_version BIGINT,command_hash VARCHAR(64),occurred_at TIMESTAMP,UNIQUE(tenant_id,actor_id,operation_id))");
            sql.execute("CREATE TABLE approval_outbox(event_id VARCHAR(36) PRIMARY KEY,tenant_id BIGINT,aggregate_id VARCHAR(36),aggregate_version BIGINT,event_type VARCHAR(64),envelope CLOB,occurred_at TIMESTAMP,UNIQUE(tenant_id,aggregate_id,aggregate_version,event_type))");
            // Portable transaction fixture, not acceptance of the MySQL V2 migration.
            sql.execute("CREATE TABLE event_outbox(event_id VARCHAR(36) PRIMARY KEY,tenant_id BIGINT,producer VARCHAR(32),event_type VARCHAR(80),envelope_json CLOB,envelope_sha CHAR(64),created_at TIMESTAMP)");
            sql.execute("CREATE TABLE event_delivery(event_id VARCHAR(36),consumer_id VARCHAR(32),state VARCHAR(16),attempts INT,next_attempt_at TIMESTAMP,lease_id VARCHAR(36),lease_until TIMESTAMP,last_error_code VARCHAR(32),delivered_at TIMESTAMP,PRIMARY KEY(event_id,consumer_id),FOREIGN KEY(event_id) REFERENCES event_outbox(event_id))");
            sql.execute("CREATE TABLE event_replay_audit(operation_id VARCHAR(36) PRIMARY KEY,tenant_id BIGINT,event_id VARCHAR(36),consumer_id VARCHAR(32),actor_id BIGINT,reason VARCHAR(500),replayed_at TIMESTAMP)");
            sql.execute("CREATE TABLE approval_source_reference(tenant_id BIGINT,application_id VARCHAR(36),operation_id VARCHAR(36),actor_id BIGINT,ticket_id VARCHAR(36),round_id VARCHAR(36),assignment_id VARCHAR(36),company_id BIGINT,department_id BIGINT,command_digest VARCHAR(64),recorded_at TIMESTAMP)");
            sql.execute("CREATE TABLE approval_execution_policy(tenant_id BIGINT,application_id VARCHAR(36),charge_mode VARCHAR(20),operation_id VARCHAR(36),recorded_at TIMESTAMP,PRIMARY KEY(tenant_id,application_id))");
            String ddl=new String(java.util.Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/V6__approval_finance_generation.sql")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).replace(" JSON NOT NULL"," CLOB NOT NULL").replace(", CHECK(state='OPEN' OR state='FENCED')","");
            org.h2.tools.RunScript.execute(connection,new java.io.StringReader(ddl));
            String terminal=new String(java.util.Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/V7__finance_owner_terminal.sql")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).replace("\r\n","\n").replace(" JSON NOT NULL"," CLOB NOT NULL").replace(",\n CHECK(source_state='COMMITTED' OR source_state='ABORTED')","");
            org.h2.tools.RunScript.execute(connection,new java.io.StringReader(terminal));
            for(String migration:java.util.List.of("V8__fee_approval_persistence.sql","V9__fee_approval_owner_facts.sql")){
                String fee=new String(java.util.Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/"+migration)).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)
                    .replace(", CHECK(state IN ('WAITING_CUSTOMER','WAITING_CONFIGURATION','PENDING_SUPERVISOR','WAITING_FINANCE','COMPLETED','REJECTED','WITHDRAWN'))","");
                org.h2.tools.RunScript.execute(connection,new java.io.StringReader(fee));
            }
        }
        return source;
    }
    private Application application(UUID operation) {
        var command = new Submission(operation,"1","2","ticket","assignment","dept","warehouse","part",2,"repair");
        return submit(UUID.randomUUID(),command,new SubmissionAuthority("1","2","ticket","assignment","dept","warehouse",true,true,true,true),null,Instant.parse("2026-10-21T01:00:00Z"));
    }
    private long count(JdbcDataSource source, String table) throws Exception {
        try (var connection = source.getConnection();var sql = connection.createStatement();var rows = sql.executeQuery("SELECT COUNT(*) FROM " + table)) { rows.next(); return rows.getLong(1); }
    }
    @Test void unconfirmedSourceFenceRollsBackLocalReservationAndSameUuidRecovers() throws Exception {
        var source=database();var store=new JdbcApprovalStore(source);var access=mock(ApprovalAccess.class);
        var request=new MockHttpServletRequest();var actor=new ApprovalAccess.Principal("1","2");
        String ticket=UUID.randomUUID().toString(),assignment=UUID.randomUUID().toString();
        when(access.principal(request)).thenReturn(actor);
        when(access.submission(eq(request),eq(actor),any())).thenReturn(new SubmissionAuthority("1","2",ticket,assignment,"5","1",true,true,true,true));
        when(access.registerReference(eq(request),eq(actor),any(),anyString())).thenThrow(new ApprovalFailure(503,"OPERATION_RESULT_UNKNOWN")).thenReturn(null);
        var service=new ApprovalService(store,access,Clock.systemUTC());
        var body=new ApprovalService.SubmitBody(UUID.randomUUID(),ticket,assignment,"5","1","2",1,"repair purpose",null);
        assertEquals(503,assertThrows(ApprovalFailure.class,()->service.submit(request,body)).status());
        for(String table:new String[]{"parts_application","approval_operation","approval_audit","approval_outbox","event_outbox","event_delivery"})assertEquals(0,count(source,table));
        var committed=service.submit(request,body);assertEquals(committed,service.submit(request,body));
        assertEquals(1,count(source,"parts_application"));assertEquals(1,count(source,"approval_outbox"));
        verify(access,times(2)).registerReference(eq(request),eq(actor),any(),matches("[0-9a-f]{64}"));
    }
    @Test void applicationReceiptAuditAndOutboxCommitTogetherAndRoundTripRecords() throws Exception {
        var source = database(); var store = new JdbcApprovalStore(source); UUID operation = UUID.randomUUID(); var application = application(operation);
        store.transact(tx -> {
            tx.reserve("1","2",operation,"a".repeat(64),"SUBMIT"); tx.insert(application);
            tx.complete("1","2",operation,"a".repeat(64),"SUBMIT",application,"approval.submitted"); return null;
        });
        assertEquals(application,store.transact(tx -> tx.application("1",application.id())));
        assertEquals(application,store.transact(tx -> tx.receipt("1","2",operation)).result());
        for (String table : new String[]{"parts_application","approval_operation","approval_audit","approval_outbox"}) assertEquals(1,count(source,table));
        assertThrows(ApprovalFailure.class, () -> store.transact(tx -> tx.application("9",application.id())));
    }
    @Test void auditFailureRollsBackApplicationAndReservedOperation() throws Exception {
        var source = database(); var store = new JdbcApprovalStore(source); UUID operation = UUID.randomUUID(); var application = application(operation);
        try (var connection = source.getConnection();var sql = connection.createStatement()) {
            sql.execute("ALTER TABLE approval_audit ADD CONSTRAINT reject_audit CHECK(action <> 'SUBMIT')");
        }
        assertThrows(ApprovalFailure.class, () -> store.transact(tx -> {
            tx.reserve("1","2",operation,"a".repeat(64),"SUBMIT"); tx.insert(application);
            tx.complete("1","2",operation,"a".repeat(64),"SUBMIT",application,"approval.submitted");return null;
        }));
        for (String table : new String[]{"parts_application","approval_operation","approval_audit","approval_outbox"}) assertEquals(0,count(source,table));
    }
    @Test void outboxFailureAlsoRollsBackAuditAndBusiness() throws Exception {
        var source = database(); var store = new JdbcApprovalStore(source); UUID operation = UUID.randomUUID(); var application = application(operation);
        try (var connection = source.getConnection();var sql = connection.createStatement()) {
            sql.execute("ALTER TABLE approval_outbox ADD CONSTRAINT reject_outbox CHECK(event_type <> 'approval.submitted')");
        }
        assertThrows(ApprovalFailure.class, () -> store.transact(tx -> {
            tx.reserve("1","2",operation,"a".repeat(64),"SUBMIT"); tx.insert(application);
            tx.complete("1","2",operation,"a".repeat(64),"SUBMIT",application,"approval.submitted");return null;
        }));
        for (String table : new String[]{"parts_application","approval_operation","approval_audit","approval_outbox"}) assertEquals(0,count(source,table));
    }
    @Test void serviceWritesWithdrawalRevisionAndDecisionAndReplaysWithoutExtraFacts() throws Exception {
        var source = database(); var store = new JdbcApprovalStore(source);
        var access = mock(ApprovalAccess.class); var request = new MockHttpServletRequest();
        var engineer = new ApprovalAccess.Principal("1","2");
        when(access.principal(request)).thenReturn(engineer);
        String ticket=UUID.randomUUID().toString(),assignment=UUID.randomUUID().toString();
        when(access.submission(eq(request),eq(engineer),any())).thenReturn(
                new SubmissionAuthority("1","2",ticket,assignment,"5","1",true,true,true,true));
        when(access.withdrawal(eq(request),eq(engineer),any())).thenReturn(new WithdrawalAuthority("1","2",true,true));
        var service = new ApprovalService(store,access,Clock.fixed(Instant.parse("2026-10-21T01:00:00Z"),ZoneOffset.UTC));
        var firstBody = new ApprovalService.SubmitBody(UUID.randomUUID(),ticket,assignment,"5","1","2",2,"repair",null);
        var first = service.submit(request,firstBody);
        assertEquals(first,service.submit(request,firstBody));
        var withdraw = new ApprovalService.WithdrawBody(UUID.randomUUID(),"0","correct quantity");
        var old = service.withdraw(request,first.id(),withdraw);
        assertEquals(old,service.withdraw(request,first.id(),withdraw));
        var revisedBody = new ApprovalService.SubmitBody(UUID.randomUUID(),ticket,assignment,"5","1","2",3,"repair",first.id());
        var revised = service.submit(request,revisedBody);
        assertEquals(revised,service.submit(request,revisedBody));
        var supervisor = new ApprovalAccess.Principal("1","3");
        when(access.principal(request)).thenReturn(supervisor);
        when(access.decision(eq(request),eq(supervisor),any())).thenReturn(new DecisionAuthority("1","3","5",true,true,true,true));
        var decision = new ApprovalService.DecisionBody(UUID.randomUUID(),"0",Outcome.APPROVE,"");
        var approved = service.decide(request,revised.id(),decision);
        assertEquals(approved,service.decide(request,revised.id(),decision));
        assertEquals(State.APPROVED,approved.state());
        assertEquals(State.WITHDRAWN,store.transact(tx->tx.application("1",first.id())).state());
        assertEquals(2,count(source,"parts_application"));
        for(String table:new String[]{"approval_operation","approval_audit","approval_outbox"}) assertEquals(4,count(source,table));
    }
    @Test void competingIdenticalOperationHasOnlyOneCommittedFactSet() throws Exception {
        var source = database(); var store = new JdbcApprovalStore(source); UUID operation = UUID.randomUUID();
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        Callable<Boolean> contender = () -> {
            try {
                store.transact(tx -> {
                    assertNull(tx.receipt("1","2",operation)); ready.countDown();
                    try { assertTrue(start.await(5,TimeUnit.SECONDS)); } catch(InterruptedException e) { throw new IllegalStateException(e); }
                    var value=application(operation); tx.reserve("1","2",operation,"a".repeat(64),"SUBMIT");tx.insert(value);
                    tx.complete("1","2",operation,"a".repeat(64),"SUBMIT",value,"approval.submitted");return null;
                }); return true;
            } catch(ApprovalFailure expectedConflict) { return false; }
        };
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(contender);var second=executor.submit(contender);
            assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();
            assertNotEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
        }
        for(String table:new String[]{"parts_application","approval_operation","approval_audit","approval_outbox"}) assertEquals(1,count(source,table));
        assertNotNull(store.transact(tx->tx.receipt("1","2",operation)));
    }
}
