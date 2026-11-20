package com.ias.gateway;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TransferReleaseTest {
 final TransferFlowTest x=new TransferFlowTest();
 final WorkflowTest f=x.f;
 final ChainIntegrationTest h=x.h;
 final List<Map<String,Object>> evidence=new ArrayList<>();
 @BeforeAll void prepare()throws Exception{x.prepare();x.restartTicket(false);}
 @AfterAll void stop()throws Exception{try{Files.writeString(Path.of("target/id07-transfer-release.json"),h.json.writeValueAsString(evidence));}finally{x.stop();}}
 void source(boolean valid)throws Exception{var e=f.employee("eng","D1",valid?TransferFlowTest.ENG:TransferFlowTest.ENG.stream().filter(p->!p.equals("ticket.engineer.read")).toList(),List.of(),List.of(),"CHANGE");try(var db=h.connection(false,false)){e.put("expectedVersion",h.scalar(db,"SELECT row_version FROM identity_user WHERE id=?",f.engineerId));}f.configure(Map.of("operationId",UUID.randomUUID().toString(),"tenants",List.of(Map.of("tenantCode",h.codeA,"departments",List.of(),"employees",List.of(e)))),true);}
 Map<String,Object> releaseBody(String t)throws Exception{var d=f.detail(h.a,t);var b=f.input(t);b.put("assignmentId",((Map<?,?>)d.get("assignment")).get("assignmentId"));b.put("reason","原负责人当前资格失效，释放源分派并重新安排处理。");return b;}
 Map<String,Object> facts(String t)throws Exception{try(var db=f.files.ticketDb(false);var stmt=db.prepareStatement("SELECT status,row_version,assignment_id,department_id,engineer_user_id,current_transfer_id,round_id FROM ticket_record WHERE id=?")){stmt.setString(1,t);try(var rs=stmt.executeQuery()){assertTrue(rs.next());var out=new LinkedHashMap<String,Object>();for(int i=1;i<=rs.getMetaData().getColumnCount();i++)out.put(rs.getMetaData().getColumnLabel(i),rs.getObject(i));return out;}}}
 void ended(String t,String q,String op,String state)throws Exception{try(var db=f.files.ticketDb(false)){assertEquals(1,h.scalar(db,"SELECT COUNT(*) FROM ticket_transfer_end WHERE request_id=? AND state=? AND reason='SOURCE_ASSIGNMENT_RELEASED' AND operation_id=?",q,state,op));assertEquals(1,h.scalar(db,"SELECT COUNT(*) FROM ticket_audit WHERE operation_id=? AND action='TRANSFER_NO_LONGER_APPLICABLE'",op));assertEquals(0,h.scalar(db,"SELECT COUNT(*) FROM ticket_record WHERE id=? AND current_transfer_id=?",t,q));assertEquals(1,h.scalar(db,"SELECT COUNT(*) FROM ticket_action_operation WHERE operation_id=?",op));}}
 void note(String name,String t,String q,String op){evidence.add(Map.of("scenario",name,"ticketId",t,"requestId",q,"operationId",op));}

 @Test @Order(1) void releaseClearsTransferAndAllowsReassignNewTransferWithLateOriginalUuidIsolation()throws Exception{
  String t=x.assigned(),createKey=UUID.randomUUID().toString();var originalCreate=x.createBody(t);var created=f.action(h.a,t,"transfer-requests",originalCreate,createKey);h.status(200,created);String q=h.body(created).get("transferRequestId").toString();var oldDecision=x.decision(t,"ACCEPT");String oldDecisionKey=UUID.randomUUID().toString(),releaseKey=UUID.randomUUID().toString();source(false);
  try{var release=releaseBody(t);h.status(200,f.action(h.a,t,"release-invalid-assignment",release,releaseKey));var d=f.detail(h.a,t);note("release must immediately produce consistent WAITING_ASSIGNMENT detail",t,q,releaseKey);assertEquals("WAITING_ASSIGNMENT",d.get("status"));assertNull(d.get("assignment"));assertNull(d.get("currentTransfer"));ended(t,q,releaseKey,"NO_LONGER_APPLICABLE");assertEquals(List.of(),x.inbox(q).get("allowedActions"));assertEquals("NO_LONGER_APPLICABLE",x.inbox(q).get("state"));
   source(true);f.engineer=h.login(h.codeA,"eng."+h.suffix,h.password);var assign=f.input(t);assign.putAll(Map.of("departmentId",Long.toString(f.dept1),"engineerUserId",Long.toString(f.engineerId)));var assigned=f.action(h.a,t,"assign",assign,UUID.randomUUID().toString());h.status(200,assigned);String replacement=h.body(assigned).get("assignmentId").toString();h.status(200,f.action(f.engineer,t,"assignments/"+replacement+"/accept",f.input(t),UUID.randomUUID().toString()));String fresh=x.create(t);var before=facts(t);
   h.status(200,f.action(h.a,t,"transfer-requests",originalCreate,createKey));h.status(200,f.action(h.a,t,"release-invalid-assignment",release,releaseKey));h.status(200,h.send(h.a,"GET","/api/ticket/action-operations/"+releaseKey,null,Map.of()));h.status(409,f.action(f.other,t,"transfer-requests/"+q+"/decision",oldDecision,oldDecisionKey));h.status(409,f.action(f.other,t,"transfer-requests/"+q+"/decision",x.decision(t,"ACCEPT"),UUID.randomUUID().toString()));h.status(404,h.send(f.other,"GET","/api/ticket/action-operations/"+oldDecisionKey,null,Map.of()));assertEquals(before,facts(t));assertEquals(fresh,((Map<?,?>)f.detail(h.a,t).get("currentTransfer")).get("transferRequestId"));
   h.status(200,x.decide(t,fresh,"ACCEPT"));x.newRecord(t,"0.50");h.status(404,h.send(f.engineer,"GET","/api/ticket/tickets/"+t,null,Map.of()));try(var db=f.files.ticketDb(false)){assertEquals(3,h.scalar(db,"SELECT COUNT(*) FROM ticket_assignment WHERE ticket_id=?",t));assertEquals(1,h.scalar(db,"SELECT COUNT(*) FROM ticket_transfer_end WHERE request_id=?",q));}note("reassign -> ordinary accept -> fresh transfer accept/new record; late old request and original success UUIDs never clear fresh pointer or restore old owner",t,q,releaseKey);
  }finally{source(true);}
 }

 @Test @Order(2) void nlaAndFinalWorkflowAuditFailuresRollBackEverythingAndOriginalKeyRecovers()throws Exception{
  for(String action:List.of("TRANSFER_NO_LONGER_APPLICABLE","WORKFLOW_RELEASE-INVALID-ASSIGNMENT")){
   String t=x.assigned(),q=x.create(t),op=UUID.randomUUID().toString(),constraint="it_id07_"+op.substring(0,8);source(false);
   try{var body=releaseBody(t);var before=facts(t);String actual=action.equals("WORKFLOW_RELEASE-INVALID-ASSIGNMENT")?"WORKFLOW_RELEASE-INVALID-ASSIGNMENT":action;
    try(var db=f.files.ticketDb(false)){h.update(db,"ALTER TABLE ticket_audit ADD CONSTRAINT "+constraint+" CHECK(NOT(operation_id='"+op+"' AND action='"+actual+"'))");}
    try{h.status(503,f.action(h.a,t,"release-invalid-assignment",body,op));assertEquals(before,facts(t));h.status(404,h.send(h.a,"GET","/api/ticket/action-operations/"+op,null,Map.of()));try(var db=f.files.ticketDb(false)){assertEquals(0,h.scalar(db,"SELECT COUNT(*) FROM ticket_transfer_end WHERE request_id=?",q));assertEquals(0,h.scalar(db,"SELECT COUNT(*) FROM ticket_audit WHERE operation_id=?",op));assertEquals(0,h.scalar(db,"SELECT COUNT(*) FROM ticket_processing_record WHERE operation_id=?",op));assertEquals(1,h.scalar(db,"SELECT COUNT(*) FROM ticket_assignment WHERE ticket_id=?",t));}}
    finally{try(var db=f.files.ticketDb(false)){h.update(db,"ALTER TABLE ticket_audit DROP CHECK "+constraint);}}
    h.status(200,f.action(h.a,t,"release-invalid-assignment",body,op));ended(t,q,op,"NO_LONGER_APPLICABLE");var after=facts(t);h.status(200,f.action(h.a,t,"release-invalid-assignment",body,op));assertEquals(after,facts(t));note("actual "+action+" CHECK failure rolls back NLA/pointer/assignment/version/operation/record/audits; original UUID recovers once",t,q,op);
   }finally{source(true);}
  }
 }

 @Test @Order(3) void releaseAndTargetAcceptanceBothOrderingsAndRealRaceAreIsolated()throws Exception{
  for(String order:List.of("releaseFirst","acceptFirst","race")){
   String t=x.assigned(),q=x.create(t),op=UUID.randomUUID().toString();source(false);
   try{var release=releaseBody(t);var decision=x.decision(t,"ACCEPT");String decisionKey=UUID.randomUUID().toString();
    if(order.equals("releaseFirst")){h.status(200,f.action(h.a,t,"release-invalid-assignment",release,op));h.status(409,f.action(f.other,t,"transfer-requests/"+q+"/decision",decision,decisionKey));ended(t,q,op,"NO_LONGER_APPLICABLE");}
    else if(order.equals("acceptFirst")){h.status(200,f.action(f.other,t,"transfer-requests/"+q+"/decision",decision,decisionKey));h.status(409,f.action(h.a,t,"release-invalid-assignment",release,op));}
    else{x.oneWinner(x.c.race(()->f.action(h.a,t,"release-invalid-assignment",release,op),()->f.action(f.other,t,"transfer-requests/"+q+"/decision",decision,decisionKey)));}
    try(var db=f.files.ticketDb(false)){assertEquals(1,h.scalar(db,"SELECT COUNT(*) FROM ticket_transfer_end WHERE request_id=?",q));long accepted=h.scalar(db,"SELECT COUNT(*) FROM ticket_transfer_end WHERE request_id=? AND state='ACCEPTED'",q);assertEquals(1+accepted,h.scalar(db,"SELECT COUNT(*) FROM ticket_assignment WHERE ticket_id=?",t));assertEquals(0,h.scalar(db,"SELECT COUNT(*) FROM ticket_record WHERE id=? AND current_transfer_id IS NOT NULL",t));}note("release vs ACCEPT "+order+": one terminal and no half ownership",t,q,op);
   }finally{source(true);}
  }
 }

 @Test @Order(4) void releaseAndExpiryScannerBothOrderingsAndRaceHaveOneEnd()throws Exception{
  for(String order:List.of("releaseFirst","scannerFirst","race")){
   String t=x.assigned(),q=x.create(t),op=UUID.randomUUID().toString();Instant time=Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);try(var db=f.files.ticketDb(false)){h.update(db,"UPDATE ticket_transfer_request SET requested_at=?,expires_at=? WHERE id=?",Timestamp.from(time.minus(Duration.ofHours(24))),Timestamp.from(time),q);}source(false);
   try{var body=releaseBody(t);
    if(order.equals("releaseFirst")){h.status(200,f.action(h.a,t,"release-invalid-assignment",body,op));x.scan(time);ended(t,q,op,"NO_LONGER_APPLICABLE");}
    else if(order.equals("scannerFirst")){x.scan(time);h.status(409,f.action(h.a,t,"release-invalid-assignment",body,op));h.status(200,f.action(h.a,t,"release-invalid-assignment",releaseBody(t),UUID.randomUUID().toString()));}
    else{try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var gate=new CountDownLatch(1);var a=pool.submit(()->{gate.await();return f.action(h.a,t,"release-invalid-assignment",body,op);});var b=pool.submit(()->{gate.await();return x.scan(time);});gate.countDown();var result=a.get();assertTrue(Set.of(200,409).contains(result.statusCode()));b.get();if(result.statusCode()==409)h.status(200,f.action(h.a,t,"release-invalid-assignment",releaseBody(t),UUID.randomUUID().toString()));}}
    x.scan(time);try(var db=f.files.ticketDb(false)){assertEquals(1,h.scalar(db,"SELECT COUNT(*) FROM ticket_transfer_end WHERE request_id=?",q));assertEquals(1,h.scalar(db,"SELECT COUNT(*) FROM ticket_assignment WHERE ticket_id=?",t));}var d=f.detail(h.a,t);assertEquals("WAITING_ASSIGNMENT",d.get("status"));assertNull(d.get("currentTransfer"));assertNull(d.get("assignment"));note("release vs absolute-expiry SYSTEM scanner "+order+": one NLA or EXPIRED, stale release conflicts and explicit new version releases",t,q,op);
   }finally{source(true);}
  }
 }
}
