package com.ias.stock;

import com.ias.trust.Problem;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.*;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ias.stock.FinancePartExecution.*;

class FinancePartExecutionStoreTest {
 @Test void refusalEndpointRequiresAuthenticatedTicketAndExactOriginalKey(){
  var input=command(uuid());String raw=com.ias.trust.Chain.JSON.writeValueAsString(Map.of("tenantId","1","operationId",input.value("operationId"),"commandDigest",input.digest()));
  var controller=new FinancePartExecutionController(db);var request=new org.springframework.mock.web.MockHttpServletRequest();request.setSecure(true);request.setAttribute("ias.chain.edge","ticket-stock");request.addHeader("Idempotency-Key",input.value("operationId"));
  try(var chain=org.mockito.Mockito.mockStatic(com.ias.trust.Chain.class,org.mockito.Mockito.CALLS_REAL_METHODS)){
   chain.when(com.ias.trust.Chain::active).thenReturn(true);chain.when(com.ias.trust.Chain::role).thenReturn("stock");
   assertEquals("PROVEN_NO_EXECUTION",controller.refuse(raw,request).get("state"));
   request.setAttribute("ias.chain.edge","finance-stock");assertEquals(403,assertThrows(Problem.class,()->controller.refuse(raw,request)).status);
   request.setAttribute("ias.chain.edge","ticket-stock");request.removeHeader("Idempotency-Key");request.addHeader("Idempotency-Key",uuid());assertEquals(400,assertThrows(Problem.class,()->controller.refuse(raw,request)).status);
  }
 }
 @Test void persistentRefusalPreventsDelayedExecutionAndSurvivesReconstruction()throws Exception{
  var input=command(uuid());var refusal=store.refuse(1,input.value("operationId"),input.digest());
  assertEquals("PROVEN_NO_EXECUTION",refusal.get("state"));assertEquals(refusal,new FinancePartExecutionStore(db,null).ownerResult(1,input.value("operationId"),input.digest()));
  assertEquals("PART_EXECUTION_PERMANENTLY_REJECTED",assertThrows(Problem.class,()->store.execute(input)).code);
  assertEquals(409,assertThrows(Problem.class,()->store.refuse(1,input.value("operationId"),"f".repeat(64))).status);
  assertEquals(0,count("stock_finance_part_execution"));assertEquals(0,count("stock_finance_part_operation"));
 }
 @Test void refusalRacingExecutionHasOnlyOneDurableTerminalOutcome()throws Exception{
  var input=command(uuid());try(var pool=Executors.newFixedThreadPool(2)){
   var gate=new CountDownLatch(1);
   var execution=pool.submit(()->{gate.await();try{return store.execute(input).get("state");}catch(Problem expected){return expected.code;}});
   var refusal=pool.submit(()->{gate.await();return store.refuse(1,input.value("operationId"),input.digest());});gate.countDown();
   execution.get(10,TimeUnit.SECONDS);var terminal=refusal.get(10,TimeUnit.SECONDS);
   if("COMMITTED".equals(terminal.get("state")))assertEquals(1,count("stock_finance_part_execution"));
   else{assertEquals("PROVEN_NO_EXECUTION",terminal.get("state"));assertEquals(0,count("stock_finance_part_execution"));}
   assertEquals(terminal,store.ownerResult(1,input.value("operationId"),input.digest()));
  }
 }
 @Test void missingOrAlteredTicketPermitCannotProduceExecution()throws Exception{
  var base=authority();var proof=new java.util.concurrent.atomic.AtomicReference<Map<String,Object>>();
  var guarded=new FinancePartExecutionStore(db,new Authority(){
   public Actor current(boolean write){return base.current(write);}
   public Map<String,Object> completion(long t,long c,String g,String id){return base.completion(t,c,g,id);}
   public OrderFact order(Actor a,String id,long warehouse){return base.order(a,id,warehouse);}
   public Map<String,Object> permit(Actor a,Command c){return proof.get();}
  });
  var input=command(uuid());assertEquals("PART_EXECUTION_PERMIT_REQUIRED",assertThrows(Problem.class,()->guarded.execute(input)).code);
  var altered=new HashMap<>(base.permit(actor,input));altered.put("commandDigest","f".repeat(64));proof.set(altered);
  assertEquals("PART_EXECUTION_PERMIT_MISMATCH",assertThrows(Problem.class,()->guarded.execute(input)).code);
  assertEquals("UNKNOWN",store.ownerResult(1,input.value("operationId"),input.digest()).get("state"));
 }
 DataSource db;FinancePartExecutionStore store;Actor actor=new Actor(1,10,Set.of(21L));String ticket=uuid(),round=uuid(),group=uuid(),completion=uuid(),quote=uuid(),line=uuid(),order=uuid(),use=uuid();
 @BeforeEach void setup()throws Exception{var h2=new JdbcDataSource();h2.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");db=h2;try(var c=db.getConnection()){for(String f:List.of("V1__stock_inbound.sql","V5__stock_execution.sql","V6__finance_generation_barrier.sql","V8__finance_part_execution.sql","V10__part_execution_terminal_gate.sql")){String ddl=new String(Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/"+f)).readAllBytes(),StandardCharsets.UTF_8);ddl=ddl.replace(",CHECK(phase IN('FENCED','OWNER_ACKED'))","").replaceAll(",\\s*CHECK\\(action_name='FINANCE_PART_EXECUTED'\\)","");RunScript.execute(c,new java.io.StringReader(ddl));}}sql("CREATE TABLE stock_part_fee_notification_outbox(event_id CHAR(36) PRIMARY KEY,tenant_id BIGINT,event_type VARCHAR(64),aggregate_id CHAR(36),aggregate_version BIGINT,company_id BIGINT,fee_group_id CHAR(36),generation BIGINT,execution_receipt_id CHAR(36),line_id CHAR(36),ticket_id CHAR(36),round_id CHAR(36),warehouse_id BIGINT,part_id BIGINT,quantity BIGINT,amount_cents BIGINT,envelope CLOB,event_digest CHAR(64),delivery_state VARCHAR(16),attempt_count INT,next_attempt_at TIMESTAMP,lease_id CHAR(36),lease_until TIMESTAMP,last_error VARCHAR(64),created_at TIMESTAMP,delivered_at TIMESTAMP)");
  sql("INSERT INTO stock_warehouse VALUES(11,1,'W',TRUE)");sql("INSERT INTO stock_part VALUES(101,1,'P','part','m','pcs',TRUE,0)");sql("INSERT INTO stock_balance VALUES(1,11,101,8,0,0)");sql("INSERT INTO stock_finance_generation VALUES(1,'"+ticket+"',3,NULL,0)");
  var approval=new StockExecutions.Approval(UUID.fromString(order),1,1,11,ticket,round,uuid(),10,Instant.now(),"CUSTOMER_PAID",new TreeMap<>(Map.of(101L,2L)));String approvalJson=com.ias.trust.Chain.JSON.writeValueAsString(approval);
  sql("INSERT INTO stock_execution_order VALUES(1,'"+order+"',11,1,'"+approvalJson.replace("'","''")+"','ACCOUNTED',NULL,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");sql("INSERT INTO stock_execution_line VALUES(1,'"+order+"',101,2,2,2,0)");
  sql("INSERT INTO stock_execution_operation VALUES(1,'"+use+"',10,'"+order+"','USE','"+"a".repeat(64)+"','{}',CURRENT_TIMESTAMP)");sql("INSERT INTO stock_execution_movement VALUES(1,'"+use+"',101,0,0,2)");
  store=new FinancePartExecutionStore(db,authority());
 }
 FinancePartExecution.Authority authority(){return new FinancePartExecution.Authority(){public Map<String,Object> permit(Actor a,Command c){return Map.of("tenantId",Long.toString(a.tenantId()),"actorUserId",Long.toString(a.userId()),"operationId",c.value("operationId"),"commandDigest",c.digest(),"ticketId",ticket,"roundId",round,"assignmentId",UUID.randomUUID().toString(),"state","PREPARED","checkedAt",Instant.now().toString());}public Actor current(boolean write){return actor;}public Map<String,Object> completion(long tenant,long company,String g,String c){var selected=new TreeMap<String,Object>(Map.of("id",line,"ticketId",ticket,"roundId",round,"kind","PART","amountCents","1200"));var lines=List.<Map<String,Object>>of(selected);var out=new LinkedHashMap<String,Object>();out.put("tenantId","1");out.put("companyId","21");out.put("groupId",group);out.put("completionId",completion);out.put("quoteId",quote);out.put("quoteVersion","2");out.put("generation","3");out.put("ticketId",ticket);out.put("roundId",round);out.put("coverageDigest",sha(lines));out.put("lines",lines);out.put("checkedAt",Instant.now().toString());return out;}public OrderFact order(Actor a,String id,long warehouse){return new OrderFact(1,11,ticket,round,order,10,new TreeMap<>(Map.of(101L,2L)));}};}
 Command command(String operation){return new Command(new LinkedHashMap<>(Map.ofEntries(Map.entry("operationId",operation),Map.entry("groupId",group),Map.entry("completionId",completion),Map.entry("quoteId",quote),Map.entry("quoteVersion","2"),Map.entry("generation","3"),Map.entry("lineId",line),Map.entry("orderId",order),Map.entry("useOperationId",use),Map.entry("partId","101"),Map.entry("quantity","2"),Map.entry("amountCents","1200"),Map.entry("warehouseId","11"),Map.entry("companyId","21"))));}
 Map<String,Object> query(){return new LinkedHashMap<>(Map.of("groupId",group,"completionId",completion,"quoteId",quote,"quoteVersion","2","generation","3","operationId",uuid(),"authorityDigest","b".repeat(64),"lines",List.of(Map.of("lineId",line,"amountCents","1200"))));}
 @Test void realUseCreatesOneImmutableOwnerFactAndPureQuery()throws Exception{var command=command(uuid());var first=store.execute(command);assertEquals("EXECUTED",first.get("state"));assertEquals(first,store.execute(command));assertEquals(first,store.operation(command.value("operationId")));var q=store.query(query());assertEquals("EXECUTED",q.get("state"));assertEquals(1,((List<?>)q.get("items")).size());assertEquals(1,count("stock_finance_part_execution"));assertEquals(1,count("stock_finance_part_operation"));assertEquals(1,count("stock_finance_part_audit"));}
 @Test void reservationOrIssueAndWrongUseQuantityNeverCharge()throws Exception{sql("UPDATE stock_execution_operation SET action_name='ISSUE'");assertEquals(409,assertThrows(Problem.class,()->store.execute(command(uuid()))).status);sql("UPDATE stock_execution_operation SET action_name='USE'");sql("UPDATE stock_execution_movement SET used_delta=1");assertEquals(409,assertThrows(Problem.class,()->store.execute(command(uuid()))).status);assertEquals(0,count("stock_finance_part_execution"));}
 @Test void oldGenerationAndCompanyMismatchFailClosed()throws Exception{sql("UPDATE stock_finance_generation SET generation=4");assertEquals(409,assertThrows(Problem.class,()->store.execute(command(uuid()))).status);actor=new Actor(1,10,Set.of(22L));assertEquals(403,assertThrows(Problem.class,()->store.execute(command(uuid()))).status);assertEquals(0,count("stock_finance_part_execution"));}
 @Test void auditFailureRollsBackAndOriginalUuidRecovers()throws Exception{sql("ALTER TABLE stock_finance_part_audit ADD CONSTRAINT fail_part_audit CHECK(actor_user_id<>10)");var command=command(uuid());assertThrows(Problem.class,()->store.execute(command));assertEquals(0,count("stock_finance_part_execution"));assertEquals(0,count("stock_finance_part_operation"));sql("ALTER TABLE stock_finance_part_audit DROP CONSTRAINT fail_part_audit");assertEquals("EXECUTED",store.execute(command).get("state"));}
 @Test void sameLineAndUseBatchAreUniqueUnderConcurrency()throws Exception{try(var pool=Executors.newFixedThreadPool(6)){var calls=new ArrayList<Callable<Boolean>>();for(int i=0;i<6;i++){var command=command(uuid());calls.add(()->{try{store.execute(command);return true;}catch(Problem expected){return false;}});}long success=0;for(var future:pool.invokeAll(calls))if(future.get())success++;assertEquals(1,success);}assertEquals(1,count("stock_finance_part_execution"));assertEquals(1,count("stock_finance_part_operation"));}
 @Test void currentGenerationQueryRejectsReplacementAndMissingExactLine()throws Exception{store.execute(command(uuid()));sql("UPDATE stock_finance_generation SET generation=4");assertEquals(409,assertThrows(Problem.class,()->store.query(query())).status);sql("UPDATE stock_finance_generation SET generation=3");var q=query();q.put("lines",List.of(Map.of("lineId",line,"amountCents","1201")));assertEquals(404,assertThrows(Problem.class,()->store.query(q)).status);}
 @Test void operationRecoveryRequiresCurrentCompanyScopeAndMatchingOwnerFact()throws Exception{
  var input=command(uuid());store.execute(input);actor=new Actor(1,10,Set.of(22L));
  assertEquals(404,assertThrows(Problem.class,()->store.operation(input.value("operationId"))).status);
  actor=new Actor(1,10,Set.of(21L));sql("UPDATE stock_finance_part_execution SET company_id=22");
  assertEquals(503,assertThrows(Problem.class,()->store.operation(input.value("operationId"))).status);
 }
 @Test void companyRevokedDuringRecoveryIsNotReturned()throws Exception{
  var input=command(uuid());store.execute(input);var base=authority();var reads=new java.util.concurrent.atomic.AtomicInteger();
  var changing=new FinancePartExecutionStore(db,new Authority(){
   public Actor current(boolean write){return reads.incrementAndGet()==1?new Actor(1,10,Set.of(21L)):new Actor(1,10,Set.of(22L));}
   public Map<String,Object> completion(long tenant,long company,String g,String c){return base.completion(tenant,company,g,c);}
   public OrderFact order(Actor a,String id,long warehouse){return base.order(a,id,warehouse);}
  });
  assertEquals(404,assertThrows(Problem.class,()->changing.operation(input.value("operationId"))).status);
 }
 @Test void ownerRecoveryRequiresMatchingOperationAndImmutableExecutionFact()throws Exception{
  var input=command(uuid());assertEquals("UNKNOWN",store.ownerResult(1,input.value("operationId"),input.digest()).get("state"));
  var result=store.execute(input);var proof=store.ownerResult(1,input.value("operationId"),input.digest());
  assertEquals("COMMITTED",proof.get("state"));assertEquals(sha(result),proof.get("resultDigest"));
  assertEquals("UNKNOWN",store.ownerResult(2,input.value("operationId"),input.digest()).get("state"));
  assertEquals(409,assertThrows(Problem.class,()->store.ownerResult(1,input.value("operationId"),"d".repeat(64))).status);
  sql("UPDATE stock_finance_part_execution SET quantity=3");
  assertEquals(503,assertThrows(Problem.class,()->store.ownerResult(1,input.value("operationId"),input.digest())).status);
 }
 @Test void untrustedRequestCannotQueryOwnerResult(){
  var controller=new FinancePartExecutionController(db);var request=new org.springframework.mock.web.MockHttpServletRequest();request.setSecure(true);request.setAttribute("ias.chain.edge","ticket-stock");
  assertEquals(403,assertThrows(Problem.class,()->controller.ownerResult("{}",request)).status);
 }
 void sql(String statement)throws Exception{try(var c=db.getConnection();var s=c.createStatement()){s.execute(statement);}}
 long count(String table)throws Exception{try(var c=db.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}}
 static String uuid(){return UUID.randomUUID().toString();}
}
