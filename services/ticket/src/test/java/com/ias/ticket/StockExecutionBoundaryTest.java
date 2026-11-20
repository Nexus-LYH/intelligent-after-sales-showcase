package com.ias.ticket;

import com.ias.trust.Problem;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.h2.tools.RunScript;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

/** Real H2 owner transactions; online facts are explicitly supplied by the test boundary. */
class StockExecutionBoundaryTest {
 ApprovalBoundaryTest fixture;StockExecutionBoundary boundary;String approval,op;boolean allowed,eligible;
 final MockHttpServletRequest request=new MockHttpServletRequest();
 @BeforeEach void setup()throws Exception{
  fixture=new ApprovalBoundaryTest();fixture.prepare();allowed=true;eligible=true;approval=UUID.randomUUID().toString();op=UUID.randomUUID().toString();fixture.register();
  fixture.boundary.settle(Map.of("eventId",UUID.randomUUID().toString(),"tenantId","7","ticketId",fixture.ticket,"operationId",fixture.operation,"commandDigest","a".repeat(64),"approvalId",approval,"decisionOperationId",UUID.randomUUID().toString(),"state","APPROVED"));
  RunScript.execute(fixture.ddlConnection,new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/V11__stock_execution_permits.sql")),StandardCharsets.UTF_8));
  RunScript.execute(fixture.ddlConnection,new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/V12__finance_quote_fencing_candidate.sql")),StandardCharsets.UTF_8));
  RunScript.execute(fixture.ddlConnection,new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/V13__finance_owner_coordination.sql")),StandardCharsets.UTF_8));
  RunScript.execute(fixture.ddlConnection,new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/V22__fee_part_permit.sql")),StandardCharsets.UTF_8));
  boundary=new StockExecutionBoundary(fixture.tickets,fixture.access){
   @Override void trusted(HttpServletRequest r){}
   @Override Identity identity(Map<String,Object>b,HttpServletRequest r,boolean write,boolean engineer){return new Identity(new Tickets.Actor(7,77,"EMPLOYEE","test-only","csrf","trace",Long.MAX_VALUE,"attempt"),Map.of("warehouseIds",allowed?List.of("11"):List.of()));}
   @Override void qualify(Map<String,Object>row,Tickets.Actor a){if(!eligible)throw new Problem(409,"ENGINEER_NOT_CURRENTLY_ELIGIBLE");}
  };
 }
 Map<String,Object> command(){return Map.of("sessionToken","test-only","csrfToken","csrf","operationId",op,"commandDigest","b".repeat(64),"ticketId",fixture.ticket,"roundId",fixture.round,"assignmentId",fixture.assignment,"approvalId",approval,"warehouseId","11","phase","ISSUE");}
 Map<String,Object> prepare(){return boundary.prepare(command(),request);}
 Map<String,Object> complete(){return boundary.complete(Map.of("tenantId","7","operationId",op,"commandDigest","b".repeat(64),"resultDigest","c".repeat(64)),request);}
 long count(String table){return fixture.db.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
 @Test void feePartBarrierRequiresDurableTerminalFactAndIsTicketScoped(){String part=UUID.randomUUID().toString();fixture.db.update("INSERT INTO ticket_fee_part_permit VALUES(?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",part,7,77,1,fixture.ticket,fixture.round,fixture.assignment,"d".repeat(64),"{}");assertEquals("FEE_PART_EXECUTION_PENDING",assertThrows(Problem.class,()->StockExecutionBoundary.guard(fixture.tickets,fixture.ticket)).code);StockExecutionBoundary.guard(fixture.tickets,UUID.randomUUID().toString());fixture.db.update("INSERT INTO ticket_fee_part_permit_result VALUES(?,?,?,CURRENT_TIMESTAMP)",part,"PROVEN_NO_EXECUTION","e".repeat(64));StockExecutionBoundary.guard(fixture.tickets,fixture.ticket);}
 @Test void unknownPermitNeverAutoReleasesAndTrustedCompletionIsExactlyOnce(){assertEquals("OPEN",prepare().get("state"));assertEquals("STOCK_EXECUTION_PENDING",assertThrows(Problem.class,()->StockExecutionBoundary.guard(fixture.tickets,fixture.ticket)).code);assertEquals("OPEN",prepare().get("state"));assertEquals(1,count("ticket_stock_execution"));assertEquals("COMPLETED",complete().get("state"));complete();StockExecutionBoundary.guard(fixture.tickets,fixture.ticket);assertEquals(1,count("ticket_stock_execution_result"));assertEquals(4,count("ticket_audit"));}
 @Test void prepareAuditFaultRollsBackPermitAndTicketVersionThenOriginalKeyRecovers(){long before=fixture.version();fixture.db.execute("ALTER TABLE ticket_audit ADD CONSTRAINT fail_stock_prepare CHECK(action<>'STOCK_EXECUTION_PREPARED')");assertThrows(Exception.class,this::prepare);assertEquals(0,count("ticket_stock_execution"));assertEquals(before,fixture.version());fixture.db.execute("ALTER TABLE ticket_audit DROP CONSTRAINT fail_stock_prepare");prepare();assertEquals(before+1,fixture.version());}
 @Test void completeAuditFaultLeavesOpenNoPartialResultThenOriginalKeyRecovers(){prepare();long before=fixture.version();fixture.db.execute("ALTER TABLE ticket_audit ADD CONSTRAINT fail_stock_complete CHECK(action<>'STOCK_EXECUTION_COMPLETED')");assertThrows(Exception.class,this::complete);assertEquals(0,count("ticket_stock_execution_result"));assertEquals(before,fixture.version());assertThrows(Problem.class,()->StockExecutionBoundary.guard(fixture.tickets,fixture.ticket));fixture.db.execute("ALTER TABLE ticket_audit DROP CONSTRAINT fail_stock_complete");complete();assertEquals(before+1,fixture.version());StockExecutionBoundary.guard(fixture.tickets,fixture.ticket);}
 @Test void sameKeyConcurrentPrepareLeavesOneFact()throws Exception{try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var gate=new CountDownLatch(1);var futures=new ArrayList<Future<?>>();for(int i=0;i<6;i++)futures.add(pool.submit(()->{gate.await();return prepare();}));gate.countDown();for(var future:futures)future.get();}assertEquals(1,count("ticket_stock_execution"));assertEquals(3,count("ticket_audit"));}
 @Test void currentScopeAndEngineerQualificationPrecedeReplay(){prepare();allowed=false;assertEquals(404,assertThrows(Problem.class,this::prepare).status);allowed=true;eligible=false;assertEquals("ENGINEER_NOT_CURRENTLY_ELIGIBLE",assertThrows(Problem.class,this::prepare).code);assertEquals(1,count("ticket_stock_execution"));}
 @Test void oldContextCannotExecuteButHistoricalReturnCanBeAccounted(){fixture.db.update("UPDATE ticket_record SET status='CANCELLED',assignment_id=NULL");assertEquals("STOCK_EXECUTION_NOT_CURRENT",assertThrows(Problem.class,this::prepare).code);var returned=new HashMap<>(command());returned.put("phase","RETURN");assertEquals("OPEN",boundary.prepare(returned,request).get("state"));complete();assertEquals(1,count("ticket_stock_execution_result"));}
 @Test void changedCompleteDigestCannotClearOpenOrChangeHistoricalResult(){prepare();assertEquals(409,assertThrows(Problem.class,()->boundary.complete(Map.of("tenantId","7","operationId",op,"commandDigest","d".repeat(64),"resultDigest","c".repeat(64)),request)).status);assertEquals(0,count("ticket_stock_execution_result"));complete();assertEquals(409,assertThrows(Problem.class,()->boundary.complete(Map.of("tenantId","7","operationId",op,"commandDigest","b".repeat(64),"resultDigest","d".repeat(64)),request)).status);assertEquals(1,count("ticket_stock_execution_result"));}
}
