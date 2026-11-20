package com.ias.approval;

import com.ias.trust.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.ias.approval.PartsApproval.*;

/** Real local JDBC/HTTP-controller semantics; authenticated transport and W proof are explicit mocked boundaries. */
class ApprovalFinanceProductionTest {
    ApprovalFinanceHookTest h;JdbcTemplate db;
    @BeforeEach void setup()throws Exception{h=new ApprovalFinanceHookTest();h.setup();db=h.db;}
    ApprovalFinanceGeneration.Binding binding(){return new ApprovalFinanceGeneration.Binding("1","3",h.ticket,UUID.randomUUID(),UUID.randomUUID(),"1",UUID.randomUUID(),UUID.randomUUID(),"c".repeat(64),UUID.randomUUID(),"1","7",h.ticket,"d".repeat(64));}
    @Test void committedAndAbortedReleaseAreImmutableAndNeverRestoreOldPaidExecution(){
        for(String terminal:List.of("COMMITTED","ABORTED")){
            var body=h.body("CUSTOMER_PAID");var app=h.service.submit(h.request,body);h.supervisor();var approved=h.service.decide(h.request,app.id(),new ApprovalService.DecisionBody(UUID.randomUUID(),"0",Outcome.APPROVE,"approved material"));h.applicant();
            StockExecutionContext.requireExecutionGeneration(db,"1",app.id().toString(),h.ticket.toString());
            var b=binding();var owner=new ApprovalFinanceGeneration(h.source,x->{});assertEquals("OWNER_FENCED",owner.fence(b).state());
            String resultDigest=terminal.equals("ABORTED")?null:"e".repeat(64);var receipt=owner.reconcile(b,terminal,resultDigest);assertEquals("OWNER_RELEASED",receipt.get("state"));assertEquals(receipt,owner.reconcile(b,terminal,resultDigest));
            assertEquals("OPEN",db.queryForObject("SELECT state FROM approval_finance_generation WHERE tenant_id=1 AND ticket_id=?",String.class,h.ticket.toString()));
            assertEquals(409,assertThrows(Problem.class,()->StockExecutionContext.requireExecutionGeneration(db,"1",app.id().toString(),h.ticket.toString())).status);
            assertEquals(approved,h.service.get(h.request,app.id()));assertEquals(409,assertThrows(ApprovalFailure.class,()->owner.reconcile(b,"COMMITTED","f".repeat(64))).status());
            // A new ticket supplies an independent source scope for the next terminal case.
            try{setup();}catch(Exception e){throw new RuntimeException(e);}
        }
    }
    @Test void verifiedAbortBeforePreparePersistsTombstoneAndRejectsLatePrepare(){
        var b=binding();var owner=new ApprovalFinanceGeneration(h.source,x->{});var first=owner.reconcile(b,"ABORTED",null);
        assertEquals(first,owner.reconcile(b,"ABORTED",null));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM approval_finance_owner_ack",Integer.class));
        assertEquals(1,db.queryForObject("SELECT generation FROM approval_finance_generation",Integer.class));
        assertEquals("OPEN",db.queryForObject("SELECT state FROM approval_finance_generation",String.class));
        assertEquals(409,assertThrows(ApprovalFailure.class,()->owner.fence(b)).status());
    }
    @Test void noFenceCannotUseCommittedProofAsInventedOwnerAck(){var owner=new ApprovalFinanceGeneration(h.source,x->{});assertEquals(409,assertThrows(ApprovalFailure.class,()->owner.reconcile(binding(),"COMMITTED","e".repeat(64))).status());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM approval_finance_terminal",Integer.class));}
    @Test void terminalAuditFailureRollsBackReleaseAndImmutableTerminal(){
        var b=binding();var owner=new ApprovalFinanceGeneration(h.source,x->{});owner.fence(b);db.execute("ALTER TABLE approval_finance_fence_audit ADD CONSTRAINT reject_release CHECK(event_type<>'OWNER_RELEASED')");
        assertEquals(503,assertThrows(ApprovalFailure.class,()->owner.reconcile(b,"COMMITTED","e".repeat(64))).status());
        assertEquals("FENCED",db.queryForObject("SELECT state FROM approval_finance_generation",String.class));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM approval_finance_terminal",Integer.class));
    }
    @Test void explicitWithdrawAndRejectDrainPendingWhileApproveAndNewSubmitRemainBlocked(){
        for(boolean reject:List.of(false,true)){
            var app=h.service.submit(h.request,h.body(null));var b=binding();var owner=new ApprovalFinanceGeneration(h.source,x->{});assertEquals("WAITING_OWNER_OPERATIONS",owner.fence(b).state());
            h.supervisor();assertEquals(409,assertThrows(ApprovalFailure.class,()->h.service.decide(h.request,app.id(),new ApprovalService.DecisionBody(UUID.randomUUID(),"0",Outcome.APPROVE,"approved material"))).status());
            if(reject)h.service.decide(h.request,app.id(),new ApprovalService.DecisionBody(UUID.randomUUID(),"0",Outcome.REJECT,"reject material"));
            else{h.applicant();h.service.withdraw(h.request,app.id(),new ApprovalService.WithdrawBody(UUID.randomUUID(),"0","withdraw material"));}
            assertEquals("OWNER_FENCED",owner.fence(b).state());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM approval_finance_owner_ack",Integer.class));
            try{setup();}catch(Exception e){throw new RuntimeException(e);}
        }
    }
    Map<String,Object> proof(Map<String,Object> b,String state){var value=new TreeMap<String,Object>(b);value.put("sourceState",state);value.put("coordinationState",state.equals("IN_PROGRESS")?"FENCING":"RECONCILING");value.put("resultDigest",state.equals("COMMITTED")?"e".repeat(64):null);value.put("checkedAt",Instant.now().toString());value.put("scopedOperationIds",List.of());value.put("inFlightOperationIds",List.of());return value;}
    MockHttpServletRequest request(){var r=new MockHttpServletRequest();r.setSecure(true);r.setAttribute("ias.chain.edge","ticket-approval");return r;}
    @Test void exactFourteenFieldProofAndAuthenticatedEdgeGateProductionControllers(){
        var b=ApprovalFinanceGeneration.wire(binding());var r=request();var sourceState=new AtomicReference<>("IN_PROGRESS");var controller=new ApprovalFinanceController(h.source,(wire,request)->proof(wire,sourceState.get()));
        try(var chain=mockStatic(Chain.class,CALLS_REAL_METHODS)){
            chain.when(Chain::active).thenReturn(true);var prepared=controller.prepare(b,r);for(var entry:b.entrySet())assertEquals(entry.getValue(),prepared.get(entry.getKey()));assertEquals("OWNER_FENCED",prepared.get("state"));
            sourceState.set("COMMITTED");assertEquals("OWNER_RELEASED",controller.reconcile(b,r).get("state"));
            var wrong=request();wrong.setAttribute("ias.chain.edge","stock-approval");assertEquals(403,assertThrows(Problem.class,()->controller.prepare(b,wrong)).status);
        }
    }
    @Test void mismatchedOrMissingProofNeverCreatesFenceOrReleases(){
        var b=ApprovalFinanceGeneration.wire(binding());var r=request();var calls=new AtomicInteger();
        var controller=new ApprovalFinanceController(h.source,(wire,request)->{var value=proof(wire,"IN_PROGRESS");if(calls.incrementAndGet()==2)value.put("actorUserId","999");return value;});
        try(var chain=mockStatic(Chain.class,CALLS_REAL_METHODS)){chain.when(Chain::active).thenReturn(true);assertEquals(503,assertThrows(Problem.class,()->controller.prepare(b,r)).status);}
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM approval_finance_fence",Integer.class));
    }
    @Test void malformedTerminalOrUnknownProofKeepsExistingFenceClosed(){
        var binding=binding();new ApprovalFinanceGeneration(h.source,x->{}).fence(binding);var b=ApprovalFinanceGeneration.wire(binding);var r=request();
        var controller=new ApprovalFinanceController(h.source,(wire,request)->{var value=proof(wire,"COMMITTED");value.put("resultDigest",null);return value;});
        try(var chain=mockStatic(Chain.class,CALLS_REAL_METHODS)){
            chain.when(Chain::active).thenReturn(true);assertEquals(503,assertThrows(Problem.class,()->controller.reconcile(b,r)).status);
            var unavailable=new ApprovalFinanceController(h.source,(wire,request)->{throw new Problem(503,"DEPENDENCY_UNAVAILABLE");});assertEquals(503,assertThrows(Problem.class,()->unavailable.reconcile(b,r)).status);
        }
        assertEquals("FENCED",db.queryForObject("SELECT state FROM approval_finance_generation",String.class));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM approval_finance_terminal",Integer.class));
    }
    @Test void unknownApprovedReservePreservesCompatibilityWithoutInventingPaidOrFreeAuthority(){
        var app=h.service.submit(h.request,h.body(null));h.supervisor();h.service.decide(h.request,app.id(),new ApprovalService.DecisionBody(UUID.randomUUID(),"0",Outcome.APPROVE,"approved material"));
        var r=request();r.setAttribute("ias.chain.edge","stock-approval");
        var facts=Map.<String,Object>of("tenantId","1","userId","9","identityType","EMPLOYEE","enabled",true,"warehouseIds",List.of("11"),"checkedAt",Instant.now().toString());
        try(var chain=mockStatic(Chain.class,CALLS_REAL_METHODS)){
            chain.when(Chain::active).thenReturn(true);
            chain.when(()->Chain.call(eq("approval-identity"),eq("/internal/identity/domain-authorizations"),anyMap(),anyString(),anyLong())).thenReturn(facts);
            var out=new StockExecutionContext(db).context(Map.of("sessionToken","token","approvalId",app.id().toString(),"warehouseId","11","phase","RESERVE"),r);
            assertEquals("UNKNOWN",out.get("chargeMode"));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM approval_execution_policy",Integer.class));
        }
        assertEquals("WAITING_OWNER_OPERATIONS",new ApprovalFinanceGeneration(h.source,x->{}).fence(binding()).state());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM approval_finance_owner_ack",Integer.class));
    }
    @Test void paidApprovedReserveStillRejectsAnActiveGenerationFence(){
        var app=h.service.submit(h.request,h.body("CUSTOMER_PAID"));h.supervisor();h.service.decide(h.request,app.id(),new ApprovalService.DecisionBody(UUID.randomUUID(),"0",Outcome.APPROVE,"approved material"));
        new ApprovalFinanceGeneration(h.source,x->{}).fence(binding());var r=request();r.setAttribute("ias.chain.edge","stock-approval");
        var facts=Map.<String,Object>of("tenantId","1","userId","9","identityType","EMPLOYEE","enabled",true,"warehouseIds",List.of("11"),"checkedAt",Instant.now().toString());
        try(var chain=mockStatic(Chain.class,CALLS_REAL_METHODS)){
            chain.when(Chain::active).thenReturn(true);
            chain.when(()->Chain.call(eq("approval-identity"),eq("/internal/identity/domain-authorizations"),anyMap(),anyString(),anyLong())).thenReturn(facts);
            assertEquals(409,assertThrows(Problem.class,()->new StockExecutionContext(db).context(Map.of("sessionToken","token","approvalId",app.id().toString(),"warehouseId","11","phase","RESERVE"),r)).status);
        }
    }
}
