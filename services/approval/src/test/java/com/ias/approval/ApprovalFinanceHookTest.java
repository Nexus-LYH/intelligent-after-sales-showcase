package com.ias.approval;

import org.junit.jupiter.api.*;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.ias.approval.PartsApproval.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Same-connection owner hooks, portable JDBC only. Current identity/source authorization is explicitly mocked. */
class ApprovalFinanceHookTest {
    JdbcDataSource source;JdbcTemplate db;ApprovalAccess access;ApprovalAccess.Principal actor;
    MockHttpServletRequest request;ApprovalService service;UUID ticket;String assignment;
    @BeforeEach void setup()throws Exception{
        source=new ApprovalJdbcTest().database();db=new JdbcTemplate(source);access=mock(ApprovalAccess.class);actor=new ApprovalAccess.Principal("1","2");request=new MockHttpServletRequest();ticket=UUID.randomUUID();assignment=UUID.randomUUID().toString();
        when(access.principal(request)).thenReturn(actor);
        when(access.submission(eq(request),eq(actor),any())).thenReturn(new SubmissionAuthority("1","2",ticket.toString(),assignment,"5","11",true,true,true,true));
        when(access.registerReference(eq(request),eq(actor),any(),anyString())).thenReturn(new ApprovalAccess.SourceReference(UUID.randomUUID().toString(),"3",Instant.now().toString()));
        when(access.withdrawal(eq(request),eq(actor),any())).thenReturn(new WithdrawalAuthority("1","2",true,true));
        when(access.decision(eq(request),eq(actor),any())).thenReturn(new DecisionAuthority("1","2","5",true,true,true,true));
        service=new ApprovalService(new JdbcApprovalStore(source),access,Clock.systemUTC());
    }
    ApprovalService.SubmitBody body(String mode){return new ApprovalService.SubmitBody(UUID.randomUUID(),ticket.toString(),assignment,"5","11","101",2,"repair materials",null,mode);}
    ApprovalFinanceGeneration.Binding binding(){return new ApprovalFinanceGeneration.Binding("1","3",ticket,UUID.randomUUID(),UUID.randomUUID(),"1",UUID.randomUUID(),UUID.randomUUID(),"f".repeat(64),UUID.randomUUID(),"1");}
    void supervisor(){var supervisor=new ApprovalAccess.Principal("1","3");when(access.principal(request)).thenReturn(supervisor);when(access.decision(eq(request),eq(supervisor),any())).thenReturn(new DecisionAuthority("1","3","5",true,true,true,true));}
    void applicant(){when(access.principal(request)).thenReturn(actor);}
    @Test void unknownCompatibilityHasDurableIntentAndCannotBeImplicitFreeOrOwnerAck(){
        var command=body(null);var app=service.submit(request,command);
        assertEquals("UNKNOWN",db.queryForObject("SELECT charge_mode FROM approval_finance_write_intent",String.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM approval_execution_policy",Integer.class));
        assertEquals("WAITING_OWNER_OPERATIONS",new ApprovalFinanceGeneration(source,b->{}).fence(binding()).state());
        assertEquals(app,service.submit(request,command));
        assertEquals(409,assertThrows(ApprovalFailure.class,()->service.submit(request,body(null))).status());
        service.withdraw(request,app.id(),new ApprovalService.WithdrawBody(UUID.randomUUID(),"0","withdraw material"));
    }
    @Test void paidSubmitDecisionAndWithdrawalIntentsCommitWithBusinessReceipts(){
        var first=service.submit(request,body("CUSTOMER_PAID"));
        supervisor();service.decide(request,first.id(),new ApprovalService.DecisionBody(UUID.randomUUID(),"0",Outcome.APPROVE,"approved repair"));applicant();
        var second=service.submit(request,body("CUSTOMER_PAID"));service.withdraw(request,second.id(),new ApprovalService.WithdrawBody(UUID.randomUUID(),"0","withdraw material"));
        assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM approval_finance_write_intent",Integer.class));
        assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM approval_operation WHERE receipt IS NOT NULL",Integer.class));
        assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM approval_audit",Integer.class));
        assertEquals("OWNER_FENCED",new ApprovalFinanceGeneration(source,b->{}).fence(binding()).state());
    }
    @Test void historyRecoveryAfterFenceStillRechecksCurrentPermissionAndDoesNotRepeatEffects(){
        var command=body("CUSTOMER_PAID");var app=service.submit(request,command);var decision=new ApprovalService.DecisionBody(UUID.randomUUID(),"0",Outcome.APPROVE,"approved material");supervisor();var approved=service.decide(request,app.id(),decision);
        assertEquals("OWNER_FENCED",new ApprovalFinanceGeneration(source,b->{}).fence(binding()).state());
        applicant();assertEquals(app,service.submit(request,command));supervisor();assertEquals(approved,service.decide(request,app.id(),decision));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM approval_operation",Integer.class));
        when(access.decision(eq(request),eq(new ApprovalAccess.Principal("1","3")),any())).thenThrow(new ApprovalFailure(403,"CURRENT_PERMISSION_REVOKED"));
        assertEquals(403,assertThrows(ApprovalFailure.class,()->service.decide(request,app.id(),decision)).status());
        applicant();assertEquals(409,assertThrows(ApprovalFailure.class,()->service.submit(request,body("CUSTOMER_PAID"))).status());
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM approval_finance_write_intent",Integer.class));
    }
    @Test void freeWritesRemainOutsidePaidScopeEvenWhenTicketIsFenced(){
        new ApprovalFinanceGeneration(source,b->{}).fence(binding());var app=service.submit(request,body("FREE"));service.withdraw(request,app.id(),new ApprovalService.WithdrawBody(UUID.randomUUID(),"0","withdraw material"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM approval_finance_write_intent",Integer.class));assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM approval_operation",Integer.class));
    }
    @Test void auditFailureRollsBackGenerationIntentApplicationReceiptAndOutbox(){
        db.execute("ALTER TABLE approval_audit ADD CONSTRAINT reject_submit CHECK(action<>'SUBMIT')");
        assertThrows(ApprovalFailure.class,()->service.submit(request,body("CUSTOMER_PAID")));
        for(String table:List.of("approval_finance_generation","approval_finance_write_intent","parts_application","approval_operation","approval_audit","approval_outbox","event_outbox"))assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
    }
    @Test void freezeWaitsForInFlightPaidMutationAndSeesCommittedPendingFact()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(access.registerReference(eq(request),eq(actor),any(),anyString())).thenAnswer(inv->{entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("test release missing");return new ApprovalAccess.SourceReference(UUID.randomUUID().toString(),"3",Instant.now().toString());});
        var pool=Executors.newFixedThreadPool(2);try{
            var submitted=pool.submit(()->service.submit(request,body("CUSTOMER_PAID")));assertTrue(entered.await(3,TimeUnit.SECONDS));
            var frozen=pool.submit(()->new ApprovalFinanceGeneration(source,b->{}).fence(binding()));assertThrows(TimeoutException.class,()->frozen.get(150,TimeUnit.MILLISECONDS));release.countDown();submitted.get(5,TimeUnit.SECONDS);
            assertEquals("WAITING_OWNER_OPERATIONS",frozen.get(5,TimeUnit.SECONDS).state());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM approval_finance_owner_ack",Integer.class));
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void concurrentOriginalUuidWaitsThenReturnsCommittedReceiptWithoutDuplicateFacts()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var command=body("CUSTOMER_PAID");
        when(access.registerReference(eq(request),eq(actor),any(),anyString())).thenAnswer(inv->{entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("test release missing");return new ApprovalAccess.SourceReference(UUID.randomUUID().toString(),"3",Instant.now().toString());});
        var pool=Executors.newFixedThreadPool(2);try{
            var first=pool.submit(()->service.submit(request,command));assertTrue(entered.await(3,TimeUnit.SECONDS));
            var late=pool.submit(()->service.submit(request,command));assertThrows(TimeoutException.class,()->late.get(150,TimeUnit.MILLISECONDS));release.countDown();assertEquals(first.get(5,TimeUnit.SECONDS),late.get(5,TimeUnit.SECONDS));
            for(String table:List.of("parts_application","approval_finance_write_intent","approval_operation","approval_audit","approval_outbox"))assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
        }finally{release.countDown();pool.shutdownNow();}
    }
}
