package com.ias.ticket;

import com.ias.trust.Problem;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class CompletionPartsProofTest {
 ApprovalBoundaryTest x;
 @BeforeEach void prepare()throws Exception{x=new ApprovalBoundaryTest();x.prepare();}
 private Map<String,Object> fact(){return Map.of("eventId",UUID.randomUUID().toString(),"tenantId","7","ticketId",x.ticket,"operationId",x.operation,"commandDigest","a".repeat(64),"approvalId",UUID.randomUUID().toString(),"decisionOperationId",UUID.randomUUID().toString(),"state","APPROVED");}
 private static Map<String,Object> proof(Map<String,Object> binding,boolean complete){var p=new HashMap<>(binding);p.put("complete",complete);p.put("orderVersion",complete?"4":null);p.put("checkedAt","2026-11-04T00:00:00Z");return p;}
 private Map<String,Object> row(){return x.db.queryForMap("SELECT * FROM ticket_record WHERE id=?",x.ticket);}
 @Test void unknownApprovalAndIncompleteStockCannotClear(){x.register();var candidate=new CompletionPartsProof(x.tickets,b->{throw new AssertionError("unsettled must not query stock");});assertFalse(candidate.clear(row()));x.boundary.settle(fact());assertFalse(new CompletionPartsProof(x.tickets,b->proof(b,false)).clear(row()));}
 @Test void matchingTerminalStockClearsOnlyCompletionCheck(){x.register();x.boundary.settle(fact());assertTrue(new CompletionPartsProof(x.tickets,b->proof(b,true)).clear(row()));assertThrows(Problem.class,()->x.boundary.requireNoExternal(x.ticket));}
 @Test void wrongScopeOrMalformedOwnerProofFailsClosed(){x.register();x.boundary.settle(fact());var wrong=new CompletionPartsProof(x.tickets,b->{var p=proof(b,true);p.put("ticketId",UUID.randomUUID().toString());return p;});assertEquals("STOCK_CLEANUP_SCOPE_MISMATCH",assertThrows(Problem.class,()->wrong.clear(row())).code);var missing=new CompletionPartsProof(x.tickets,b->{var p=proof(b,true);p.remove("orderVersion");return p;});assertEquals("STOCK_CLEANUP_FACT_INVALID",assertThrows(Problem.class,()->missing.clear(row())).code);}
 @Test void ticketLockPreventsNewApprovalDuringStockProof()throws Exception{
  x.register();x.boundary.settle(fact());var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var solve=pool.submit(()->x.tickets.tx.execute(tx->{var lockedRow=x.db.queryForMap("SELECT * FROM ticket_record WHERE id=? FOR UPDATE",x.ticket);assertTrue(new CompletionPartsProof(x.tickets,b->{locked.countDown();try{assertTrue(release.await(3,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}return proof(b,true);}).clear(lockedRow));x.db.update("UPDATE ticket_record SET status='PENDING_CUSTOMER_CONFIRMATION' WHERE id=?",x.ticket);return true;}));
   assertTrue(locked.await(3,TimeUnit.SECONDS));var register=pool.submit(()->{try{x.boundary.register(x.ticket,x.round,x.assignment,UUID.randomUUID().toString(),"b".repeat(64),x.actor);return "UNEXPECTED";}catch(Problem p){return p.code;}});
   release.countDown();assertTrue(solve.get());assertEquals("TICKET_NOT_APPROVABLE",register.get());
  }
 }
}
