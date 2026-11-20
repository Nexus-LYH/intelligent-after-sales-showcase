package com.ias.ticket;

import com.ias.trust.*;
import org.junit.jupiter.api.*;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.RunScript;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ias.ticket.FinanceLaborExecution.*;

/** SQL/concurrency with explicit identity/P facts. Not production trusted-network evidence. */
class FinanceLaborExecutionStoreTest {
 javax.sql.DataSource source;JdbcTemplate db;FinanceLaborExecutionStore store;java.sql.Connection ddl;
 String ticket=UUID.randomUUID().toString(),round=UUID.randomUUID().toString(),assignment=UUID.randomUUID().toString(),group=UUID.randomUUID().toString(),quote=UUID.randomUUID().toString(),completion=UUID.randomUUID().toString(),line=UUID.randomUUID().toString(),record=UUID.randomUUID().toString(),freeze=UUID.randomUUID().toString(),relation=UUID.randomUUID().toString();
 AtomicBoolean allowed=new AtomicBoolean(true);AtomicLong user=new AtomicLong(42);Map<String,Object> fact;Map<String,Object> original;
 @BeforeEach void setup()throws Exception{
  var h2=new JdbcDataSource();h2.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");initialize(h2,true);
 }
 void initialize(javax.sql.DataSource chosen,boolean h2)throws Exception{
  source=chosen;db=new JdbcTemplate(source);
  db.execute("CREATE TABLE ticket_record(id CHAR(36) PRIMARY KEY,tenant_id BIGINT,company_id BIGINT,operation_id CHAR(36),round_id CHAR(36),assignment_id CHAR(36),engineer_user_id BIGINT,department_id BIGINT,reporter_user_id BIGINT,status VARCHAR(40),row_version BIGINT)");
  db.execute("CREATE TABLE ticket_create_operation(operation_id CHAR(36),state VARCHAR(20))");
  db.execute("CREATE TABLE ticket_processing_record(id CHAR(36) PRIMARY KEY,ticket_id CHAR(36),round_id CHAR(36),assignment_id CHAR(36),actor_user_id BIGINT,type VARCHAR(40))");
  ddl=source.getConnection();for(String m:List.of("V12__finance_quote_fencing_candidate.sql","V13__finance_owner_coordination.sql","V16__finance_labor_execution.sql")){String sql=new String(getClass().getResourceAsStream("/db/migration/"+m).readAllBytes(),StandardCharsets.UTF_8);if(h2)RunScript.execute(ddl,new StringReader(sql.replace("UTC_TIMESTAMP(3)","CURRENT_TIMESTAMP")));else try(var statement=ddl.createStatement()){for(String part:sql.split(";"))if(!part.isBlank())statement.execute(part);}}if(h2)db.execute("CREATE TABLE ticket_fee_notification_outbox(event_id CHAR(36) PRIMARY KEY,tenant_id BIGINT,event_type VARCHAR(64),aggregate_id CHAR(36),aggregate_version BIGINT,fee_group_id CHAR(36),generation BIGINT,ticket_id CHAR(36),round_id CHAR(36),assignment_id CHAR(36),company_id BIGINT,department_id BIGINT,applicant_user_id BIGINT,envelope CLOB,event_digest CHAR(64),delivery_state VARCHAR(16),attempt_count INT,next_attempt_at TIMESTAMP,lease_until TIMESTAMP,last_error VARCHAR(64),created_at TIMESTAMP,delivered_at TIMESTAMP)");else{String sql=new String(getClass().getResourceAsStream("/db/migration/V17__fee_notification_outbox.sql").readAllBytes(),StandardCharsets.UTF_8);try(var statement=ddl.createStatement()){for(String part:sql.split(";"))if(!part.isBlank())statement.execute(part);}}
  String create=UUID.randomUUID().toString();db.update("INSERT INTO ticket_record VALUES(?,7,9,?,?,?,42,5,77,'IN_PROGRESS',1)",ticket,create,round,assignment);db.update("INSERT INTO ticket_create_operation VALUES(?,'COMPLETED')",create);db.update("INSERT INTO ticket_processing_record VALUES(?,?,?,?,42,'RECORDS')",record,ticket,round,assignment);
  seed(1,freeze,quote,"RELEASED");
  var lines=List.of(Map.of("id",line,"ticketId",ticket,"roundId",round,"kind","LABOR","amountCents","1000"));fact=new LinkedHashMap<>();fact.putAll(Map.of("tenantId","7","companyId","9","ticketId",ticket,"roundId",round,"groupId",group,"quoteId",quote,"quoteVersion","1","generation","1","faultRelationId",relation,"quoteDigest","a".repeat(64)));fact.putAll(Map.of("completionId",completion,"applicationId",UUID.randomUUID().toString(),"state","COMPLETED","supervisorReceiptId",UUID.randomUUID().toString(),"financeReceiptId",UUID.randomUUID().toString(),"financialReviewRequired",true,"lines",lines,"coverageDigest",sha(lines)));
  original=new TreeMap<>();original.putAll(Map.of("operationId",UUID.randomUUID().toString(),"expectedVersion","1","roundId",round,"assignmentId",assignment,"groupId",group,"completionId",completion,"quoteId",quote,"quoteVersion","1","generation","1","lineId",line));original.put("processingRecordId",record);original.put("amountCents","900");
  store=new FinanceLaborExecutionStore(source,authority());
 }
 @AfterEach void close()throws Exception{if(ddl!=null)ddl.close();}
 FinanceLaborExecutionStore.Authority authority(){return new FinanceLaborExecutionStore.Authority(){
  public Actor current(boolean write){if(!allowed.get())throw new Problem(403,"ACCESS_DENIED");return new Actor(7,user.get(),Set.of(9L),5L);}
  public Map<String,Object> completion(long tenant,long company,String group,String id){var out=new LinkedHashMap<>(fact);out.putIfAbsent("checkedAt",Instant.now().toString());return out;}
 };}
 void seed(long generation,String freezeId,String q,String state){
  db.update("INSERT INTO ticket_finance_quote_fence VALUES(?,7,42,9,?,?,?,?,0,?,?,?,?,'approval,stock',CURRENT_TIMESTAMP)",freezeId,relation,ticket,round,group,q,UUID.randomUUID().toString(),"a".repeat(64),generation);
  db.update("INSERT INTO ticket_finance_coordination(freeze_id,state,source_state,result_digest,next_attempt_at,updated_at) VALUES(?,?,'COMMITTED',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",freezeId,state,"b".repeat(64));
  db.update("INSERT INTO ticket_finance_quote_source_result VALUES(?,?,CURRENT_TIMESTAMP)",freezeId,"b".repeat(64));for(String owner:List.of("approval","stock"))db.update("INSERT INTO ticket_finance_owner_ack VALUES(?,?,'RELEASED',?,'{}',CURRENT_TIMESTAMP)",freezeId,owner,"c".repeat(64));
 }
 Command command(){return new Command(ticket,original);}
 Map<String,Object> execute(){return store.execute(command());}
 long count(String table){return db.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
 long version(){return db.queryForObject("SELECT row_version FROM ticket_record WHERE id=?",Long.class,ticket);}
 Map<String,Object> query(){return Map.of("sessionToken","synthetic","groupId",group,"completionId",completion,"quoteId",quote,"quoteVersion","1","generation","1","operationId",UUID.randomUUID().toString(),"authorityDigest","d".repeat(64),"lines",List.of(Map.of("lineId",line,"amountCents","900")));}
 void empty(){assertEquals(0,count("ticket_fee_labor_execution"));assertEquals(0,count("ticket_fee_labor_operation"));assertEquals(0,count("ticket_fee_labor_audit"));assertEquals(1,version());}
 @Test void durableReceiptReplayAndQueryArePureAndStable(){var receipt=execute();assertEquals(receipt,execute());assertEquals(receipt,store.operation(ticket,original.get("operationId").toString()));var q=query();var one=store.query(q);var next=new HashMap<>(q);next.put("operationId",UUID.randomUUID().toString());next.put("authorityDigest","e".repeat(64));assertEquals(one.get("receiptId"),store.query(next).get("receiptId"));assertNotEquals(q.get("operationId"),one.get("receiptId"));assertEquals(1,count("ticket_fee_labor_audit"));assertEquals(2,version());}
 @Test void changedBodyAndCrossKeySameLineCannotRegisterTwice(){execute();original.put("amountCents","800");assertEquals(409,assertThrows(Problem.class,this::execute).status);original.put("operationId",UUID.randomUUID().toString());original.put("expectedVersion","2");assertEquals("FEE_LINE_ALREADY_EXECUTED",assertThrows(Problem.class,this::execute).code);assertEquals(1,count("ticket_fee_labor_execution"));}
 @Test void sameUuidSixConcurrentCallsHaveOneReceipt()throws Exception{try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var go=new CountDownLatch(1);var calls=new ArrayList<Future<Map<String,Object>>>();for(int n=0;n<6;n++)calls.add(pool.submit(()->{go.await();return execute();}));go.countDown();var first=calls.getFirst().get();for(var f:calls)assertEquals(first,f.get());}assertEquals(1,count("ticket_fee_labor_execution"));assertEquals(1,count("ticket_fee_labor_audit"));assertEquals(2,version());}
 @Test void newGenerationAndFencingRejectOldExecution(){db.update("UPDATE ticket_finance_coordination SET state='RECONCILING'");assertEquals("FEE_EXECUTION_FENCED",assertThrows(Problem.class,this::execute).code);db.update("UPDATE ticket_finance_coordination SET state='RELEASED'");seed(2,UUID.randomUUID().toString(),UUID.randomUUID().toString(),"FENCING");assertEquals("FEE_EXECUTION_STALE_GENERATION",assertThrows(Problem.class,this::execute).code);empty();}
 @Test void missingOwnerAckAndWrongSourceDigestFailClosed(){db.update("DELETE FROM ticket_finance_owner_ack WHERE owner_name='stock'");assertEquals(503,assertThrows(Problem.class,this::execute).status);empty();}
 @Test void wrongRecordObjectTypeActorAndAssignmentAreRejected(){for(String col:List.of("ticket_id","round_id","assignment_id")){db.update("UPDATE ticket_processing_record SET "+col+"=?",UUID.randomUUID().toString());assertEquals("PROCESSING_RECORD_MISMATCH",assertThrows(Problem.class,this::execute).code);db.update("UPDATE ticket_processing_record SET ticket_id=?,round_id=?,assignment_id=?",ticket,round,assignment);}db.update("UPDATE ticket_processing_record SET type='SOLUTION'");assertEquals(409,assertThrows(Problem.class,this::execute).status);db.update("UPDATE ticket_processing_record SET type='RECORDS',actor_user_id=77");assertEquals(409,assertThrows(Problem.class,this::execute).status);empty();}
 @Test void overAmountPartStaleOrTamperedCompletionNeverWrites(){original.put("amountCents","1001");assertEquals(409,assertThrows(Problem.class,this::execute).status);original.put("amountCents","900");fact.put("checkedAt",Instant.now().minusSeconds(5).toString());assertEquals(503,assertThrows(Problem.class,this::execute).status);fact.remove("checkedAt");fact.put("quoteId",UUID.randomUUID().toString());assertEquals(503,assertThrows(Problem.class,this::execute).status);fact.put("quoteId",quote);var parts=List.of(Map.of("id",line,"ticketId",ticket,"roundId",round,"kind","PART","amountCents","1000"));fact.put("lines",parts);fact.put("coverageDigest",sha(parts));assertEquals("PART_EXECUTION_REQUIRES_STOCK_OWNER",assertThrows(Problem.class,this::execute).code);empty();}
 @Test void auditFailureRollsBackAllFactsAndVersionThenOriginalKeyRecovers(){db.execute("ALTER TABLE ticket_fee_labor_audit ADD CONSTRAINT fail_fee_audit CHECK(actor_user_id<>42)");assertEquals(503,assertThrows(Problem.class,this::execute).status);empty();db.execute("ALTER TABLE ticket_fee_labor_audit DROP CONSTRAINT fail_fee_audit");execute();assertEquals(1,count("ticket_fee_labor_audit"));assertEquals(2,version());}
 @Test void currentRevocationAndOldEngineerCannotReplayOrRead(){execute();allowed.set(false);assertEquals(403,assertThrows(Problem.class,this::execute).status);assertEquals(403,assertThrows(Problem.class,()->store.operation(ticket,original.get("operationId").toString())).status);allowed.set(true);db.update("UPDATE ticket_record SET engineer_user_id=77");assertEquals(403,assertThrows(Problem.class,this::execute).status);assertEquals(403,assertThrows(Problem.class,()->store.operation(ticket,original.get("operationId").toString())).status);assertEquals(1,count("ticket_fee_labor_execution"));}
 @Test void queryAmountMissingLineAndOldRoundNeverBecomeExecuted(){assertEquals(404,assertThrows(Problem.class,()->store.query(query())).status);execute();var q=new HashMap<>(query());q.put("lines",List.of(Map.of("lineId",line,"amountCents","800")));assertEquals(409,assertThrows(Problem.class,()->store.query(q)).status);db.update("UPDATE ticket_record SET round_id=?",UUID.randomUUID().toString());assertEquals(409,assertThrows(Problem.class,()->store.query(query())).status);assertEquals(1,count("ticket_fee_labor_audit"));}
 @Test void canonicalStringsUnknownFieldsAndDuplicateJsonRejected(){var b=new HashMap<>(original);b.put("amountCents",900);assertEquals(400,assertThrows(Problem.class,()->new Command(ticket,b)).status);b.put("amountCents","0900");assertThrows(Problem.class,()->new Command(ticket,b));b.put("amountCents","900");b.put("approved",true);assertThrows(Problem.class,()->new Command(ticket,b));assertThrows(Problem.class,()->FinanceLaborExecutionController.body("{\"amountCents\":\"1\",\"amountCents\":\"2\"}"));}
 @Test void differentUuidRaceNeverCreatesTwoExecutionFacts()throws Exception{
  var b=new HashMap<>(original);b.put("operationId",UUID.randomUUID().toString());var other=new Command(ticket,b);
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var go=new CountDownLatch(1);var results=new ArrayList<Future<Boolean>>();for(Command c:List.of(command(),other))results.add(pool.submit(()->{go.await();try{store.execute(c);return true;}catch(Problem p){assertEquals(409,p.status);return false;}}));go.countDown();int passed=0;for(var f:results)if(f.get(5,TimeUnit.SECONDS))passed++;assertEquals(1,passed);}
  assertEquals(1,count("ticket_fee_labor_execution"));assertEquals(1,count("ticket_fee_labor_operation"));assertEquals(1,count("ticket_fee_labor_audit"));
 }
 @Test void quotePrepareWinsCommonTicketLockAndLateExecutionIsRejected()throws Exception{
  try(var c=source.getConnection();var pool=Executors.newVirtualThreadPerTaskExecutor()){
   c.setAutoCommit(false);var locked=new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(c,true));locked.queryForMap("SELECT * FROM ticket_record WHERE id=? FOR UPDATE",ticket);
   var started=new CountDownLatch(1);var attempt=pool.submit(()->{started.countDown();return assertThrows(Problem.class,this::execute);});assertTrue(started.await(2,TimeUnit.SECONDS));
   // Represents quote prepare's already-owned common lock, not an independent second DB writer.
   String next=UUID.randomUUID().toString();locked.update("INSERT INTO ticket_finance_quote_fence VALUES(?,7,42,9,?,?,?,?,0,?,?,?,2,'approval,stock',CURRENT_TIMESTAMP)",next,relation,ticket,round,group,UUID.randomUUID().toString(),UUID.randomUUID().toString(),"a".repeat(64));c.commit();
   assertEquals("FEE_EXECUTION_STALE_GENERATION",attempt.get(5,TimeUnit.SECONDS).code);empty();
  }
 }
 @Test void executionWinsCommonLockBeforeNewQuoteAndHistoryRemains()throws Exception{
  var inside=new CountDownLatch(1);var release=new CountDownLatch(1);var normal=authority();
  var gated=new FinanceLaborExecutionStore(source,new FinanceLaborExecutionStore.Authority(){public Actor current(boolean w){return normal.current(w);}public Map<String,Object> completion(long t,long c,String g,String id){inside.countDown();try{if(!release.await(3,TimeUnit.SECONDS))throw new IllegalStateException("GATE_TIMEOUT");}catch(InterruptedException e){throw new IllegalStateException(e);}return normal.completion(t,c,g,id);}});
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var execution=pool.submit(()->gated.execute(command()));assertTrue(inside.await(2,TimeUnit.SECONDS));
   var quoteChange=pool.submit(()->{try(var c=source.getConnection()){c.setAutoCommit(false);var j=new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(c,true));j.queryForMap("SELECT * FROM ticket_record WHERE id=? FOR UPDATE",ticket);j.update("UPDATE ticket_record SET row_version=row_version+1 WHERE id=?",ticket);c.commit();return true;}});
   assertFalse(quoteChange.isDone());release.countDown();assertEquals("EXECUTED",execution.get(5,TimeUnit.SECONDS).get("state"));assertTrue(quoteChange.get(5,TimeUnit.SECONDS));
  }finally{release.countDown();}
  seed(2,UUID.randomUUID().toString(),UUID.randomUUID().toString(),"FENCING");assertEquals(409,assertThrows(Problem.class,()->store.query(query())).status);assertEquals(1,count("ticket_fee_labor_execution"));assertEquals(1,count("ticket_fee_labor_audit"));
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
 void uncertainRollbackOrActuallyCommittedResponseDiscardsConnectionAndRecovers(boolean committed)throws Exception{
  var physical=source.getConnection();var resets=new AtomicInteger();var aborts=new AtomicInteger();var normal=authority();
  var proxy=(java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{java.sql.Connection.class},(p,m,args)->{
   switch(m.getName()){case "commit"->{physical.commit();throw new java.sql.SQLException("COMMIT_RESPONSE_LOST");}case "rollback"->throw new java.sql.SQLException("ROLLBACK_FAILED");case "abort"->{aborts.incrementAndGet();physical.close();return null;}case "setAutoCommit"->{if(Boolean.TRUE.equals(args[0]))resets.incrementAndGet();}}
   try{return m.invoke(physical,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
  });
  var injected=org.mockito.Mockito.mock(javax.sql.DataSource.class);org.mockito.Mockito.when(injected.getConnection()).thenReturn(proxy);
  var fault=new FinanceLaborExecutionStore(injected,new FinanceLaborExecutionStore.Authority(){public Actor current(boolean w){return normal.current(w);}public Map<String,Object> completion(long t,long c,String g,String id){if(!committed)throw new Problem(403,"INJECTED_REJECTION");return normal.completion(t,c,g,id);}});
  var failure=assertThrows(Problem.class,()->fault.execute(command()));assertEquals(503,failure.status);assertEquals("OPERATION_RESULT_UNKNOWN",failure.code);assertNotNull(failure.getCause());assertEquals(1,failure.getCause().getSuppressed().length);assertEquals(1,aborts.get());assertEquals(0,resets.get());assertTrue(physical.isClosed());assertEquals(committed?1:0,count("ticket_fee_labor_execution"));
  execute();assertEquals(1,count("ticket_fee_labor_execution"));assertEquals(1,count("ticket_fee_labor_audit"));assertEquals(2,version());
 }
}
