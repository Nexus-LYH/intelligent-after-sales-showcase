package com.ias.approval;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.*;
import java.time.*;
import java.security.MessageDigest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.ias.approval.PartsApproval.*;

/** Portable owner transaction evidence; trusted identity is an explicit mock boundary. */
class ApprovalStockPolicyTest {
 JdbcTemplate db;JdbcApprovalStore store;ApprovalService service;MockHttpServletRequest request;String ticket,assignment;
 @BeforeEach void setup()throws Exception{
  var source=new ApprovalJdbcTest().database();db=new JdbcTemplate(source);store=new JdbcApprovalStore(source);
  ticket=UUID.randomUUID().toString();assignment=UUID.randomUUID().toString();request=new MockHttpServletRequest();var access=mock(ApprovalAccess.class);var actor=new ApprovalAccess.Principal("1","2");when(access.principal(request)).thenReturn(actor);
  when(access.submission(eq(request),eq(actor),any())).thenReturn(new SubmissionAuthority("1","2",ticket,assignment,"5","11",true,true,true,true));
  service=new ApprovalService(store,access,Clock.systemUTC());
 }
 ApprovalService.SubmitBody body(UUID op,String mode){return new ApprovalService.SubmitBody(op,ticket,assignment,"5","11","101",2,"approved repair material",null,mode);}
 @Test void legacyNullModePreservesExactDigestAndCreatesNoFreePolicy()throws Exception{
  UUID op=UUID.randomUUID();var command=body(op,null);var app=service.submit(request,command);assertEquals(app,service.submit(request,command));
  var domain=new Submission(op,"1","2",ticket,assignment,"5","11","101",2,"approved repair material");
  String expected=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(com.ias.trust.Chain.JSON.writeValueAsBytes(Arrays.asList("SUBMIT",null,domain))));
  assertEquals(expected,db.queryForObject("SELECT command_hash FROM approval_operation",String.class));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM approval_execution_policy",Integer.class));
 }
 @Test void explicitModeIsImmutableAndIncludedInOriginalUuidDigest(){UUID op=UUID.randomUUID();var app=service.submit(request,body(op,"FREE"));assertEquals(app,service.submit(request,body(op,"FREE")));assertEquals("FREE",db.queryForObject("SELECT charge_mode FROM approval_execution_policy",String.class));assertEquals(409,assertThrows(ApprovalFailure.class,()->service.submit(request,body(op,"CUSTOMER_PAID"))).status());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM approval_execution_policy",Integer.class));}
 @Test void outboxFaultRollsBackPolicyAndAllLocalFactsThenOriginalUuidRecovers(){db.execute("ALTER TABLE approval_outbox ADD CONSTRAINT no_submit CHECK(event_type<>'approval.submitted')");var body=body(UUID.randomUUID(),"FREE");assertThrows(ApprovalFailure.class,()->service.submit(request,body));for(String table:List.of("parts_application","approval_execution_policy","approval_operation","approval_audit","approval_outbox","event_outbox","event_delivery"))assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));db.execute("ALTER TABLE approval_outbox DROP CONSTRAINT no_submit");service.submit(request,body);assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM approval_execution_policy",Integer.class));}
 @Test void ownerContextRequiresApprovedStateAndReturnsExplicitMode(){var app=service.submit(request,body(UUID.randomUUID(),"FREE"));db.update("INSERT INTO approval_source_reference(tenant_id,application_id,round_id) VALUES(?,?,?)",1,app.id().toString(),ticket);assertEquals(409,assertThrows(com.ias.trust.Problem.class,()->StockExecutionContext.approved(db,"1","99",app.id().toString(),"11")).status);var approved=PartsApproval.decide(app,new Decision(UUID.randomUUID(),"1","3",0,Outcome.APPROVE,"verified material"),new DecisionAuthority("1","3","5",true,true,true,true),Instant.now());db.update("UPDATE parts_application SET snapshot=?,state='APPROVED',row_version=1 WHERE id=?",com.ias.trust.Chain.JSON.writeValueAsString(approved),app.id().toString());var fact=StockExecutionContext.approved(db,"1","99",app.id().toString(),"11");assertEquals("FREE",fact.get("chargeMode"));assertEquals("2",((Map<?,?>)((List<?>)fact.get("lines")).getFirst()).get("quantity"));assertEquals(404,assertThrows(com.ias.trust.Problem.class,()->StockExecutionContext.approved(db,"1","99",app.id().toString(),"22")).status);}
 @Test void originalApplicantCannotReadEngineeringOrderAfterResponsibilityChanged(){var facts=Map.<String,Object>of("tenantId","1","userId","2");var original=Map.<String,Object>of("ticketId",ticket);var current=Map.<String,Object>of("tenantId","1","ticketId",ticket,"engineerUserId","3");assertEquals(404,assertThrows(com.ias.trust.Problem.class,()->StockExecutionContext.requireEngineerContext(facts,original,current)).status);StockExecutionContext.requireEngineerContext(facts,original,Map.of("tenantId","1","ticketId",ticket,"engineerUserId","2"));assertEquals(404,assertThrows(com.ias.trust.Problem.class,()->StockExecutionContext.requireEngineerContext(facts,original,Map.of("tenantId","9","ticketId",ticket,"engineerUserId","2"))).status);}
}
