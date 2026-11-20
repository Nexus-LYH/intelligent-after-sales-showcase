package com.ias.approval;
import org.junit.jupiter.api.Test;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.ias.approval.PartsApproval.*;
import static org.junit.jupiter.api.Assertions.*;

class IndependentReplayAuthorizationTest {
 @Test void successfulSubmitPostReplayMustNotUseRevokedWriteQualification() throws Exception {
  var schema=ApprovalJdbcTest.class.getDeclaredMethod("database");schema.setAccessible(true);
  var source=(JdbcDataSource)schema.invoke(new ApprovalJdbcTest());
  var enabled=new AtomicBoolean(true);var checks=new java.util.concurrent.atomic.AtomicInteger();
  var principal=new ApprovalAccess.Principal("1","2");
  ApprovalAccess access=new ApprovalAccess(){
   public Principal principal(jakarta.servlet.http.HttpServletRequest r){return principal;}
   public SubmissionAuthority submission(jakarta.servlet.http.HttpServletRequest r,Principal p,Submission c){checks.incrementAndGet();return new SubmissionAuthority(p.tenantId(),p.actorId(),c.ticketId(),c.assignmentId(),c.departmentId(),c.warehouseId(),enabled.get(),true,true,true);}
   public DecisionAuthority decision(jakarta.servlet.http.HttpServletRequest r,Principal p,Application a){throw new AssertionError();}
   public WithdrawalAuthority withdrawal(jakarta.servlet.http.HttpServletRequest r,Principal p,Application a){throw new AssertionError();}
   public void read(jakarta.servlet.http.HttpServletRequest r,Principal p,Application a){} // Independent current read remains permitted.
  };
  var service=new ApprovalService(new JdbcApprovalStore(source),access,Clock.systemUTC());
  var request=new MockHttpServletRequest();
  var body=new ApprovalService.SubmitBody(UUID.randomUUID(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),"5","1","2",1,"repair purpose",null);
  var first=service.submit(request,body);assertEquals(1,checks.get());enabled.set(false);
  // Historical GET may still return the original receipt under read scope.
  assertEquals(first,service.operation(request,body.operationId()));
  var rejection=assertThrows(PartsApproval.Rejected.class,()->service.submit(request,body),"POST replay must check current create qualification, not just current read scope");
  assertEquals("NOT_AUTHORIZED",rejection.getMessage());
 }
}
