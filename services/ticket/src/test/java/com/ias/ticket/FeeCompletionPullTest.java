package com.ias.ticket;
import com.ias.trust.Problem;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real local JDBC transactions; peer responses are injected, not network acceptance. */
class FeeCompletionPullTest {
 ApprovalBoundaryTest f;Tickets t;String group,quote,first,second;int records;boolean failSecond;String remoteError;Map<String,Object> row;
 @BeforeEach void setup()throws Exception{
  f=new ApprovalBoundaryTest();f.prepare();RunScript.execute(f.ddlConnection,new InputStreamReader(getClass().getResourceAsStream("/db/migration/V15__fee_completion_pull.sql"),StandardCharsets.UTF_8));
  // Only MySQL DATE_ADD syntax is translated for H2. Lease predicates and real transactions remain intact.
  var db=new JdbcTemplate(f.db.getDataSource()){@Override public int update(String sql,Object...args){return super.update(sql.replace("DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 30 SECOND)","DATEADD('SECOND',30,UTC_TIMESTAMP(3))").replace("DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 2 SECOND)","DATEADD('SECOND',2,UTC_TIMESTAMP(3))"),args);}};
  t=new Tickets(db,f.tickets.tx);group=UUID.randomUUID().toString();quote=UUID.randomUUID().toString();first=UUID.randomUUID().toString();second=UUID.randomUUID().toString();db.update("INSERT INTO ticket_fee_group_pull(tenant_id,company_id,group_id,next_attempt_at) VALUES(7,9,?,UTC_TIMESTAMP(3))",group);row=db.queryForMap("SELECT * FROM ticket_fee_group_pull");
 }
 @AfterEach void close()throws Exception{f.ddlConnection.close();}
 Map<String,Object> binding(){return Map.of("tenantId","7","companyId","9","groupId",group);}
 Map<String,Object> completion(String id){var m=new LinkedHashMap<String,Object>(binding());m.putAll(Map.of("completionId",id,"quoteId",quote,"quoteVersion","3","state","COMPLETED"));return m;}
 Map<String,Object> ack(String id){var m=new LinkedHashMap<String,Object>(binding());m.putAll(Map.of("completionId",id,"quoteId",quote,"quoteVersion","3","recordingOperationId",UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)).toString(),"groupVersion","5","recordedAt","2026-10-25T01:00:00Z","checkedAt",java.time.Instant.now().toString()));return m;}
 FeeCompletionPull worker(List<Map<String,Object>> completions){return new FeeCompletionPull(t,(edge,path,body)->{
  if(edge.equals("ticket-approval")){assertEquals("/internal/approval/fee-completions/group",path);assertEquals(binding(),body);var m=new LinkedHashMap<String,Object>(binding());m.put("completions",completions);return m;}
  assertEquals("ticket-finance",edge);assertEquals("/internal/finance/fee-completions/record",path);assertEquals(Set.of("tenantId","companyId","groupId","completionId"),body.keySet());records++;String id=body.get("completionId").toString();if(remoteError!=null)throw new Problem(404,remoteError);if(failSecond&&id.equals(second))throw new Problem(503,"DEPENDENCY_UNAVAILABLE");return ack(id);
 });}
 long count(){return t.db.queryForObject("SELECT COUNT(*) FROM ticket_fee_completion_ack",Long.class);}
 @Test void partialOwnerProgressPersistsAndRetriesOnlyMissingImmutableAck(){failSecond=true;var w=worker(List.of(completion(first),completion(second)));w.reconcile(row);assertEquals(1,count());assertEquals(2,records);assertEquals("DEPENDENCY_UNAVAILABLE",t.db.queryForObject("SELECT last_error_code FROM ticket_fee_group_pull",String.class));assertNull(t.db.queryForObject("SELECT lease_id FROM ticket_fee_group_pull",String.class));failSecond=false;w.reconcile(row);assertEquals(2,count());assertEquals(3,records);w.reconcile(row);assertEquals(3,records);assertNull(t.db.queryForObject("SELECT last_error_code FROM ticket_fee_group_pull",String.class));}
 @Test void remote404NeverCreatesAckAndOriginalCompletionRemainsRetryable(){remoteError="FEE_COMPLETION_NOT_FOUND";var w=worker(List.of(completion(first)));w.reconcile(row);assertEquals(0,count());assertEquals(remoteError,t.db.queryForObject("SELECT last_error_code FROM ticket_fee_group_pull",String.class));remoteError=null;w.reconcile(row);assertEquals(1,count());assertEquals(2,records);}
 @Test void localAckInsertFailureAfterRemoteSuccessRetriesSameFactWithoutPartialAck(){var w=worker(List.of(completion(first)));t.db.execute("ALTER TABLE ticket_fee_completion_ack ADD CONSTRAINT it_pull_ack CHECK(completion_id<>'"+first+"')");w.reconcile(row);assertEquals(0,count());assertEquals(1,records);t.db.execute("ALTER TABLE ticket_fee_completion_ack DROP CONSTRAINT it_pull_ack");w.reconcile(row);assertEquals(1,count());assertEquals(2,records);assertEquals(first,t.db.queryForObject("SELECT completion_id FROM ticket_fee_completion_ack",String.class));}
 @Test void heldLeasePreventsSecondWorkerAndMalformedBindingNeverCallsFinance(){t.db.update("UPDATE ticket_fee_group_pull SET lease_id=?,lease_until=DATEADD('SECOND',30,UTC_TIMESTAMP(3))",UUID.randomUUID().toString());var w=worker(List.of(completion(first)));w.reconcile(row);assertEquals(0,records);t.db.update("UPDATE ticket_fee_group_pull SET lease_id=NULL,lease_until=NULL");var wrong=new LinkedHashMap<>(completion(first));wrong.put("companyId","10");worker(List.of(wrong)).reconcile(row);assertEquals(0,records);assertEquals(0,count());assertEquals("FEE_COMPLETION_BINDING_MISMATCH",t.db.queryForObject("SELECT last_error_code FROM ticket_fee_group_pull",String.class));}
}
