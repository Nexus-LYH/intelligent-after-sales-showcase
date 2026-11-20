package com.ias.approval;
import org.junit.jupiter.api.Test;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.ias.approval.PartsApproval.*;
import static org.junit.jupiter.api.Assertions.*;
class IndependentResubmissionScopeTest {
 @Test void revisedApplicationOriginalPostAlsoRechecksCurrentQualificationWithoutNewFacts() throws Exception {
  var factory=ApprovalJdbcTest.class.getDeclaredMethod("database");factory.setAccessible(true);
  var source=(JdbcDataSource)factory.invoke(new ApprovalJdbcTest());
  var current=new AtomicBoolean(true);var principal=new ApprovalAccess.Principal("1","2");
  ApprovalAccess access=new ApprovalAccess(){
   public Principal principal(jakarta.servlet.http.HttpServletRequest r){return principal;}
   public SubmissionAuthority submission(jakarta.servlet.http.HttpServletRequest r,Principal p,Submission c){return new SubmissionAuthority(p.tenantId(),p.actorId(),c.ticketId(),c.assignmentId(),c.departmentId(),c.warehouseId(),current.get(),true,true,true);}
   public DecisionAuthority decision(jakarta.servlet.http.HttpServletRequest r,Principal p,Application a){throw new AssertionError();}
   public WithdrawalAuthority withdrawal(jakarta.servlet.http.HttpServletRequest r,Principal p,Application a){return new WithdrawalAuthority(p.tenantId(),p.actorId(),true,true);}
   public void read(jakarta.servlet.http.HttpServletRequest r,Principal p,Application a){}
  };
  var service=new ApprovalService(new JdbcApprovalStore(source),access,Clock.systemUTC());var request=new MockHttpServletRequest();
  String ticket=UUID.randomUUID().toString(),assignment=UUID.randomUUID().toString();
  var firstBody=new ApprovalService.SubmitBody(UUID.randomUUID(),ticket,assignment,"5","1","2",1,"repair purpose",null);
  var first=service.submit(request,firstBody);
  service.withdraw(request,first.id(),new ApprovalService.WithdrawBody(UUID.randomUUID(),"0","correct quantity"));
  var newBody=new ApprovalService.SubmitBody(UUID.randomUUID(),ticket,assignment,"5","1","2",2,"repair purpose",first.id());
  var revised=service.submit(request,newBody);assertEquals(first.id(),revised.revision().previousApplicationId());
  Map<String,Long> before=counts(source);assertEquals(4,before.get("event_delivery")); // three notifications plus durable withdrawal settlement
  try(var c=source.getConnection();var q=c.createStatement();var r=q.executeQuery("SELECT COUNT(*) FROM event_delivery WHERE consumer_id='ticket'")){r.next();assertEquals(1,r.getLong(1));}
  current.set(false);
  assertEquals(revised,service.operation(request,newBody.operationId()));
  var failure=assertThrows(PartsApproval.Rejected.class,()->service.submit(request,newBody));assertEquals("NOT_AUTHORIZED",failure.getMessage());
  assertEquals(before,counts(source));
  current.set(true);assertEquals(revised,service.submit(request,newBody));assertEquals(before,counts(source));
 }
 Map<String,Long> counts(JdbcDataSource source)throws Exception {
  var result=new TreeMap<String,Long>();try(var c=source.getConnection();var q=c.createStatement()){
   for(String table:List.of("parts_application","approval_operation","approval_audit","approval_outbox","event_outbox","event_delivery"))try(var r=q.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();result.put(table,r.getLong(1));}
  }return result;
 }
}
