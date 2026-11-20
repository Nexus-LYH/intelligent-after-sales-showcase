package com.ias.ticket;

import com.ias.trust.Problem;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual H2 transactions; owner-edge verification is tested separately, not represented by these domain calls. */
class ApprovalSettlementTest {
 ApprovalBoundaryTest x;
 @BeforeEach void prepare()throws Exception{x=new ApprovalBoundaryTest();x.prepare();}
 Map<String,Object> fact(String state){return Map.of("eventId",UUID.randomUUID().toString(),"tenantId","7","ticketId",x.ticket,"operationId",x.operation,"commandDigest","a".repeat(64),"approvalId",UUID.randomUUID().toString(),"decisionOperationId",UUID.randomUUID().toString(),"state",state);}
 @Test void rejectedAndWithdrawnReliableFactsUnlockWithoutErasingHistory()throws Exception{for(String state:List.of("REJECTED","WITHDRAWN")){x.prepare();x.register();assertThrows(Problem.class,()->x.boundary.requireCompletionClear(x.ticket));var body=fact(state);var one=x.boundary.settle(body);var two=x.boundary.settle(body);assertEquals(one,two);assertEquals(1,x.count("ticket_approval_reference"));assertEquals(1,x.count("ticket_approval_settlement"));assertEquals(2,x.count("ticket_audit"));assertEquals(3,x.version());assertDoesNotThrow(()->x.boundary.requireNoExternal(x.ticket));assertDoesNotThrow(()->x.boundary.requireCompletionClear(x.ticket));}}
 @Test void approvedRemainsBlockedUntilActualPartsOwnerCleanup(){x.register();x.boundary.settle(fact("APPROVED"));assertEquals("RELATED_APPROVAL_OR_PARTS_PENDING",assertThrows(Problem.class,()->x.boundary.requireCompletionClear(x.ticket)).code);assertEquals("UNSUPPORTED_RELATED_BUSINESS",assertThrows(Problem.class,()->x.boundary.requireNoExternal(x.ticket)).code);}
 @Test void changedEventOrDigestCannotRewriteSettlement(){x.register();var body=new HashMap<>(fact("REJECTED"));x.boundary.settle(body);body.put("state","WITHDRAWN");assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(Problem.class,()->x.boundary.settle(body)).code);body.put("state","REJECTED");body.put("commandDigest","b".repeat(64));assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(Problem.class,()->x.boundary.settle(body)).code);assertEquals(1,x.count("ticket_approval_settlement"));assertEquals(3,x.version());}
 @Test void settlementAuditFailureRollsBackReceiptVersionThenOriginalEventRecovers(){x.register();var body=fact("REJECTED");x.db.execute("ALTER TABLE ticket_audit ADD CONSTRAINT it_settle_audit CHECK(action<>'APPROVAL_REFERENCE_SETTLED')");assertThrows(Exception.class,()->x.boundary.settle(body));assertEquals(0,x.count("ticket_approval_settlement"));assertEquals(2,x.version());assertThrows(Problem.class,()->x.boundary.requireCompletionClear(x.ticket));x.db.execute("ALTER TABLE ticket_audit DROP CONSTRAINT it_settle_audit");x.boundary.settle(body);assertDoesNotThrow(()->x.boundary.requireCompletionClear(x.ticket));assertEquals(3,x.version());}
 @Test void solveAndRegisterHaveOneWinnerOnTicketLock()throws Exception{
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var gate=new CountDownLatch(1);
   var apply=pool.submit(()->{gate.await();try{x.register();return true;}catch(Problem failure){assertEquals("TICKET_NOT_APPROVABLE",failure.code);return false;}});
   var solve=pool.submit(()->{gate.await();try{return x.tickets.tx.execute(tx->{var row=x.db.queryForMap("SELECT * FROM ticket_record WHERE id=? FOR UPDATE",x.ticket);row.put("completion_scope","ORDINARY_REPAIR_V1");new Completion(x.tickets,x.access).requireSupported(row);x.db.update("UPDATE ticket_record SET status='RESOLVED',row_version=row_version+1 WHERE id=?",x.ticket);return true;});}catch(Problem failure){assertEquals("RELATED_APPROVAL_OR_PARTS_PENDING",failure.code);return false;}});
   gate.countDown();assertNotEquals(apply.get(),solve.get());assertEquals(2,x.version());
  }
 }
 @Test void pureClosureCheckCannotClaimCanCloseWhileUnsettled(){x.register();var check=new HashMap<String,Object>(Map.of("canClose",true,"blockers",List.of(),"relatedWorkflows","NOT_ENABLED"));x.boundary.annotateClosure(check,x.ticket);assertEquals(false,check.get("canClose"));assertEquals(List.of("RELATED_APPROVAL_OR_PARTS_PENDING"),check.get("blockers"));assertEquals(2,x.version());}
}
