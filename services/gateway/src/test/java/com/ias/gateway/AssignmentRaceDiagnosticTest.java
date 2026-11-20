package com.ias.gateway;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssignmentRaceDiagnosticTest {
 final WorkflowTest flow=new WorkflowTest();final ChainIntegrationTest h=flow.h;
 @BeforeAll void prepare()throws Exception{flow.prepare();}
 @AfterAll void stop()throws Exception{flow.stop();}
 @Test void actorRegistrationHoldsExclusiveLockBeforeSelectForUpdate()throws Exception{
  try(var loader=flow.files.domainLoader("ticket");var first=flow.files.ticketDb(true);var second=flow.files.ticketDb(true);var pool=Executors.newVirtualThreadPerTaskExecutor()){
   h.update(first,"INSERT INTO ticket_actor_lock(tenant_id,user_id) VALUES(?,?) ON DUPLICATE KEY UPDATE user_id=ticket_actor_lock.user_id",h.tenantA,h.adminA);first.setAutoCommit(false);second.setAutoCommit(false);
   var registered=new CountDownLatch(1);var releaseSelect=new CountDownLatch(1);var secondUpdated=new CountDownLatch(1);var attemptedSecond=new CountDownLatch(1);
   var ds1=new org.springframework.jdbc.datasource.SingleConnectionDataSource(first,true);var ds2=new org.springframework.jdbc.datasource.SingleConnectionDataSource(second,true);
   var db1=new org.springframework.jdbc.core.JdbcTemplate(ds1){@Override public int update(String sql,Object...args){int count=super.update(sql,args);registered.countDown();try{if(!releaseSelect.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Test select gate timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return count;}};
   var db2=new org.springframework.jdbc.core.JdbcTemplate(ds2){@Override public int update(String sql,Object...args){attemptedSecond.countDown();int count=super.update(sql,args);secondUpdated.countDown();return count;}};
   Class<?> type=loader.loadClass("com.ias.ticket.Tickets");var method=type.getDeclaredMethod("lockActor",long.class,long.class);method.setAccessible(true);var constructor=type.getConstructor(org.springframework.jdbc.core.JdbcTemplate.class,org.springframework.transaction.support.TransactionTemplate.class);Object one=constructor.newInstance(db1,new org.springframework.transaction.support.TransactionTemplate()),two=constructor.newInstance(db2,new org.springframework.transaction.support.TransactionTemplate());
   var a=pool.submit(()->method.invoke(one,h.tenantA,h.adminA));java.util.concurrent.Future<?> b=null;
   try{assertTrue(registered.await(5,TimeUnit.SECONDS));b=pool.submit(()->method.invoke(two,h.tenantA,h.adminA));assertTrue(attemptedSecond.await(5,TimeUnit.SECONDS));assertFalse(secondUpdated.await(300,TimeUnit.MILLISECONDS),"Second registration must wait for X ownership; compatible shared registration recreates the upgrade deadlock");releaseSelect.countDown();a.get(5,TimeUnit.SECONDS);first.commit();b.get(5,TimeUnit.SECONDS);second.commit();}
   finally{releaseSelect.countDown();try{a.get(5,TimeUnit.SECONDS);}catch(Exception ignored){}first.rollback();if(b!=null)try{b.get(5,TimeUnit.SECONDS);}catch(Exception ignored){}second.rollback();}
  }
 }
 @Test void concurrentAssignmentsKeepOriginalKeysAndCorrelateUnknownResultsWithSafeTrace()throws Exception{
  var observations=new ArrayList<Map<String,Object>>();int unknown=0;
  for(int i=0;i<12;i++){
   String ticket=flow.create(false);h.status(200,flow.action(h.a,ticket,"accept",flow.acceptBody(ticket),UUID.randomUUID().toString()));var input=flow.input(ticket);input.putAll(Map.of("departmentId",Long.toString(flow.dept1),"engineerUserId",Long.toString(flow.engineerId)));var keys=List.of(UUID.randomUUID().toString(),UUID.randomUUID().toString());List<HttpResponse<String>> replies;
   try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var gate=new CountDownLatch(1);var a=pool.submit(()->{gate.await();return flow.action(h.a,ticket,"assign",input,keys.get(0));});var b=pool.submit(()->{gate.await();return flow.action(h.a,ticket,"assign",input,keys.get(1));});gate.countDown();replies=List.of(a.get(),b.get());}
   var record=new LinkedHashMap<String,Object>();record.put("ticketId",ticket);var outcomes=new ArrayList<Map<String,Object>>();assertEquals(1,replies.stream().filter(r->r.statusCode()==200).count());
   for(int n=0;n<2;n++){
    var response=replies.get(n);var outcome=new LinkedHashMap<String,Object>();outcome.put("originalOperationId",keys.get(n));outcome.put("status",response.statusCode());outcome.put("code",response.statusCode()==200?"OK":h.safeCode(response));
    if(response.statusCode()==503){unknown++;String trace=h.body(response).get("traceId").toString();assertEquals(trace,response.headers().firstValue("X-Trace-Id").orElseThrow());String log=Files.readString(Path.of("target","chain-ticket.log"));var match=Pattern.compile("ticket.failure trace="+Pattern.quote(trace)+" exceptionType=([A-Za-z0-9_$]+) sqlState=([A-Z0-9_]+) vendorCode=([0-9]+)").matcher(log);assertTrue(match.find(),"Unknown result must have a correlated safe diagnostic");outcome.put("traceId",trace);outcome.put("exceptionType",match.group(1));outcome.put("sqlState",match.group(2));outcome.put("vendorCode",Integer.parseInt(match.group(3)));
     var query=h.send(h.a,"GET","/api/ticket/action-operations/"+keys.get(n),null,Map.of());assertTrue(Set.of(200,404).contains(query.statusCode()));outcome.put("originalKeyQuery",query.statusCode());var replay=flow.action(h.a,ticket,"assign",input,keys.get(n));assertTrue(Set.of(200,409).contains(replay.statusCode()));outcome.put("explicitSameKeyReplay",replay.statusCode());
    }else assertTrue(Set.of(200,409).contains(response.statusCode()));outcomes.add(outcome);
   }
   try(var c=flow.files.ticketDb(false)){assertEquals(1,h.scalar(c,"SELECT COUNT(*) FROM ticket_assignment WHERE ticket_id=?",ticket));assertEquals(1,h.scalar(c,"SELECT COUNT(*) FROM ticket_action_operation WHERE ticket_id=? AND action='assign'",ticket));assertEquals(1,h.scalar(c,"SELECT COUNT(*) FROM ticket_processing_record WHERE ticket_id=? AND type='ASSIGN'",ticket));}record.put("outcomes",outcomes);observations.add(record);Files.writeString(Path.of("target","id06-assignment-safe-traces.json"),h.json.writeValueAsString(observations));
  }
  if(Boolean.parseBoolean(System.getProperty("ias.race.strict","true")))assertEquals(0,unknown,"Unexpected503; inspect preserved safe traces, never hide with a rerun");
 }
}
