package com.ias.ticket;

import com.ias.trust.Problem;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import com.ias.trust.Chain;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class FeePartPermitStoreTest {
 StockExecutionBoundaryTest fixture; FeePartPermitStore store; String use,operation; Tickets.Actor actor;
 @BeforeEach void setup() throws Exception {
  fixture=new StockExecutionBoundaryTest();fixture.setup();store=new FeePartPermitStore(fixture.fixture.tickets);
  actor=fixture.fixture.actor;use=UUID.randomUUID().toString();operation=UUID.randomUUID().toString();
  fixture.fixture.db.update("INSERT INTO ticket_stock_execution VALUES(?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",use,7,42,fixture.fixture.ticket,fixture.fixture.round,fixture.fixture.assignment,fixture.approval,11,"USE","a".repeat(64));
  fixture.fixture.db.update("INSERT INTO ticket_stock_execution_result VALUES(?,?,CURRENT_TIMESTAMP)",use,"b".repeat(64));
 }
 FeePartPermitStore.Command command(String ignored,String binding){var body=FeePartCommandTest.vector();body.put("operationId",operation);body.put("useOperationId",use);body.put("orderId",fixture.approval);body.put("companyId","9");body.put("warehouseId","11");body.put("amountCents","changed".equals(binding)?"1201":"1200");return new FeePartPermitStore.Command(fixture.fixture.ticket,fixture.fixture.round,fixture.fixture.assignment,new FeePartCommand(body));}
 String digest(){return command("","binding").request().digest();}
 @Test void onlyExplicitOwnerNonExecutionProofReleasesPermitIdempotently(){
  store.prepare(actor,command("","binding"));
  var recovery=new FeePartRecovery(fixture.fixture.tickets,request->{var result=new java.util.HashMap<>(request);result.put("state","PROVEN_NO_EXECUTION");result.put("resultDigest","e".repeat(64));return result;});
  assertEquals("PROVEN_NO_EXECUTION",recovery.reconcile(7,operation));assertEquals("PROVEN_NO_EXECUTION",recovery.reconcile(7,operation));
  assertEquals(1,fixture.count("ticket_fee_part_permit_result"));FeePartPermitBarrier.guard(fixture.fixture.tickets,fixture.fixture.ticket);
  var changed=new FeePartRecovery(fixture.fixture.tickets,request->{var result=new java.util.HashMap<>(request);result.put("state","COMMITTED");result.put("resultDigest","f".repeat(64));return result;});
  assertEquals(409,assertThrows(Problem.class,()->changed.reconcile(7,operation)).status);
 }
 @Test void internalProofRequiresExactAuthenticatedStockEdge(){
  var input=command("","binding");store.prepare(actor,input);var controller=new FeePartPermitController(fixture.fixture.tickets);
  String raw=Chain.JSON.writeValueAsString(Map.of("tenantId","7","actorUserId","42","command",input.request().body()));
  var request=new MockHttpServletRequest();request.setSecure(true);request.setAttribute("ias.chain.edge","stock-ticket");
  try(var chain=mockStatic(Chain.class)){
   chain.when(Chain::active).thenReturn(true);chain.when(Chain::role).thenReturn("ticket");
   assertEquals("PREPARED",controller.proof(raw,request).get("state"));
   request.setAttribute("ias.chain.edge","finance-ticket");assertEquals(403,assertThrows(Problem.class,()->controller.proof(raw,request)).status);
   request.setAttribute("ias.chain.edge","stock-ticket");request.setSecure(false);assertEquals(403,assertThrows(Problem.class,()->controller.proof(raw,request)).status);
  }
 }
 @Test void proofBindsOwnerAndEntireCommandAndClosesAfterTerminalResult(){
  var input=command("","binding");store.prepare(actor,input);
  assertEquals("PREPARED",store.activeProof(7,42,input.request()).get("state"));
  assertEquals(404,assertThrows(Problem.class,()->store.activeProof(8,42,input.request())).status);
  assertEquals(404,assertThrows(Problem.class,()->store.activeProof(7,43,input.request())).status);
  assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(Problem.class,()->store.activeProof(7,42,command("","changed").request())).code);
  store.recordVerifiedResult(7,operation,digest(),"COMMITTED","a".repeat(64));
  assertEquals("FEE_PART_PERMIT_COMPLETED",assertThrows(Problem.class,()->store.activeProof(7,42,input.request())).code);
 }
 @Test void useFromAnotherOrderCannotAuthorizeFeeExecution(){
  fixture.fixture.db.update("UPDATE ticket_stock_execution SET approval_id=? WHERE operation_id=?",UUID.randomUUID().toString(),use);
  assertEquals("STOCK_USE_ORDER_MISMATCH",assertThrows(Problem.class,()->store.prepare(actor,command("","binding"))).code);
  assertEquals(0,fixture.count("ticket_fee_part_permit"));
 }
 @Test void immutablePermitBlocksTransitionsAndOnlyExactReplaySucceeds(){
  var first=command("c".repeat(64),"verified-binding");store.prepare(actor,first);store.prepare(actor,first);
  assertEquals(1,fixture.count("ticket_fee_part_permit"));
  assertEquals(first.request().digest(),fixture.fixture.db.queryForObject("SELECT command_digest FROM ticket_fee_part_permit WHERE operation_id=?",String.class,operation));
  assertEquals("FEE_PART_EXECUTION_PENDING",assertThrows(Problem.class,()->FeePartPermitBarrier.guard(fixture.fixture.tickets,fixture.fixture.ticket)).code);
  assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(Problem.class,()->store.prepare(actor,command("c".repeat(64),"changed"))).code);
  assertEquals(1,fixture.count("ticket_fee_part_permit"));
 }
 @Test void staleAssignmentOrUnfinishedUseCannotCreatePermit(){
  fixture.fixture.db.update("DELETE FROM ticket_stock_execution_result WHERE operation_id=?",use);
  assertEquals("STOCK_USE_NOT_CURRENT",assertThrows(Problem.class,()->store.prepare(actor,command("c".repeat(64),"binding"))).code);
  assertEquals(0,fixture.count("ticket_fee_part_permit"));
  fixture.fixture.db.update("INSERT INTO ticket_stock_execution_result VALUES(?,?,CURRENT_TIMESTAMP)",use,"b".repeat(64));
  fixture.fixture.db.update("UPDATE ticket_record SET assignment_id=?",UUID.randomUUID().toString());
  assertEquals("STOCK_EXECUTION_NOT_CURRENT",assertThrows(Problem.class,()->store.prepare(actor,command("c".repeat(64),"binding"))).code);
  assertEquals(0,fixture.count("ticket_fee_part_permit"));
 }
 @Test void auditFailureRollsBackPermit(){
  fixture.fixture.db.execute("ALTER TABLE ticket_audit ADD CONSTRAINT fail_fee_part CHECK(action<>'FEE_PART_PERMIT_PREPARED')");
  assertThrows(Exception.class,()->store.prepare(actor,command("c".repeat(64),"binding")));
  assertEquals(0,fixture.count("ticket_fee_part_permit"));
 }
 @Test void concurrentSameOperationCreatesOnePermitAndAudit() throws Exception {
  var input=command("c".repeat(64),"binding");long before=fixture.count("ticket_audit");
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var gate=new CountDownLatch(1);var first=pool.submit(()->{gate.await();store.prepare(actor,input);return null;});
   var second=pool.submit(()->{gate.await();store.prepare(actor,input);return null;});gate.countDown();
   first.get(5,TimeUnit.SECONDS);second.get(5,TimeUnit.SECONDS);
  }
  assertEquals(1,fixture.count("ticket_fee_part_permit"));assertEquals(before+1,fixture.count("ticket_audit"));
 }
 @Test void verifiedTerminalFactIsIdempotentAndConflictsNeverClearOrChangeIt(){
  store.prepare(actor,command("c".repeat(64),"binding"));
  assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(Problem.class,()->store.recordVerifiedResult(7,operation,"d".repeat(64),"COMMITTED","e".repeat(64))).code);
  assertEquals(0,fixture.count("ticket_fee_part_permit_result"));
  assertThrows(Problem.class,()->FeePartPermitBarrier.guard(fixture.fixture.tickets,fixture.fixture.ticket));
  store.recordVerifiedResult(7,operation,digest(),"COMMITTED","e".repeat(64));
  store.recordVerifiedResult(7,operation,digest(),"COMMITTED","e".repeat(64));
  assertEquals(1,fixture.count("ticket_fee_part_permit_result"));
  FeePartPermitBarrier.guard(fixture.fixture.tickets,fixture.fixture.ticket);
  assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(Problem.class,()->store.recordVerifiedResult(7,operation,digest(),"PROVEN_NO_EXECUTION","e".repeat(64))).code);
  assertEquals(404,assertThrows(Problem.class,()->store.recordVerifiedResult(8,operation,digest(),"COMMITTED","e".repeat(64))).status);
 }
 @Test void terminalAuditFailureKeepsPermitOpen(){
  store.prepare(actor,command("c".repeat(64),"binding"));
  fixture.fixture.db.execute("ALTER TABLE ticket_audit ADD CONSTRAINT fail_fee_part_complete CHECK(action<>'FEE_PART_PERMIT_COMPLETED')");
  assertThrows(Exception.class,()->store.recordVerifiedResult(7,operation,digest(),"PROVEN_NO_EXECUTION","e".repeat(64)));
  assertEquals(0,fixture.count("ticket_fee_part_permit_result"));
  assertThrows(Problem.class,()->FeePartPermitBarrier.guard(fixture.fixture.tickets,fixture.fixture.ticket));
 }
 @Test void recoveryLeavesUnknownAndInvalidOwnerReplyOpenThenIdempotentlyRecordsCommitted(){
  store.prepare(actor,command("c".repeat(64),"binding"));
  var unknown=new FeePartRecovery(fixture.fixture.tickets,request->{var out=new java.util.HashMap<>(request);out.put("state","UNKNOWN");return out;});
  assertEquals("UNKNOWN",unknown.reconcile(7,operation));assertThrows(Problem.class,()->FeePartPermitBarrier.guard(fixture.fixture.tickets,fixture.fixture.ticket));
  var mismatch=new FeePartRecovery(fixture.fixture.tickets,request->{var out=new java.util.HashMap<>(request);out.put("state","COMMITTED");out.put("resultDigest","invalid");return out;});
  assertEquals(503,assertThrows(Problem.class,()->mismatch.reconcile(7,operation)).status);assertEquals(0,fixture.count("ticket_fee_part_permit_result"));
  var committed=new FeePartRecovery(fixture.fixture.tickets,request->{var out=new java.util.HashMap<>(request);out.put("state","COMMITTED");out.put("resultDigest","e".repeat(64));return out;});
  assertEquals("COMMITTED",committed.reconcile(7,operation));assertEquals("COMMITTED",committed.reconcile(7,operation));
  assertEquals(1,fixture.count("ticket_fee_part_permit_result"));FeePartPermitBarrier.guard(fixture.fixture.tickets,fixture.fixture.ticket);
 }
}
