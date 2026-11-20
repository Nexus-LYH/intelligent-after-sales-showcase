package com.ias.finance;

import com.ias.trust.Problem;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FeeApprovalContextTest {
 QuoteSourcesTest f;FeeApprovalContextController controller;boolean enabled=true;String scopeCompany="2";int identityCalls;
 @BeforeEach void setup()throws Exception{f=new QuoteSourcesTest();f.setup();f.f.store.submit(f.f.actor,f.f.owner,f.command,f.details);controller=new FeeApprovalContextController(f.f.source,(edge,path,body,r)->{
  var out=new HashMap<String,Object>();out.put("checkedAt",Instant.now().toString());
  if(edge.equals("finance-identity")){identityCalls++;assertEquals("FEE_READ",body.get("purpose"));out.putAll(Map.of("tenantId","1","userId","90","enabled",enabled,"identityType","EMPLOYEE","departmentEnabled",true,"permissions",List.of("approval.fees.read"),"companyIds",List.of("2")));}
  else{assertEquals("FEE_READ",body.get("purpose"));out.putAll(Map.of("tenantId","1","companyId",scopeCompany,"ticketId",f.command.additions().getFirst().ticketId().toString(),"roundId",f.command.additions().getFirst().roundId().toString(),"faultRelationId",f.relation.toString()));}return out;
 },r->{});}
 String request(){return FinanceController.JSON.writeValueAsString(Map.of("sessionToken","synthetic-test-session","groupId",f.f.owner.faultGroupId().toString(),"quoteId",f.command.applicationId().toString(),"ticketId",f.command.additions().getFirst().ticketId().toString()));}
 @SuppressWarnings("unchecked") Map<String,Object> read()throws Exception{return (Map<String,Object>)controller.context(request(),new MockHttpServletRequest());}
 @Test void originalBasisSourceAndNoQuotePermissionRequired()throws Exception{long operations=f.f.count("finance_operation");var result=read();assertEquals("250000",result.get("bCents"));assertEquals("200000",result.get("thresholdCents"));assertEquals("1",result.get("quoteVersion"));assertTrue((Boolean)result.get("everRequiredFinance"));assertTrue((Boolean)result.get("financialReviewRequired"));assertEquals(JdbcFeeGroups.quoteDigest(f.f.owner,f.command,f.details),result.get("quoteDigest"));assertEquals("10",result.get("sourceActorUserId"));assertEquals("90",result.get("actorUserId"));assertNull(result.get("customerConfirmation"));assertEquals(operations,f.f.count("finance_operation"));assertEquals(2,identityCalls);assertFalse(result.containsKey("state_json"));}
 @Test void actualConfirmationBindsOriginalVersionAndDigest()throws Exception{
  var wrapped=new QuoteSourcesTest.Wrapped(f.f.authority){public void customerConfirmation(JdbcFeeGroups.Actor a,FeeGroup.Identity o,JdbcFeeGroups.QuoteVersion q){}};
  UUID op=UUID.randomUUID();new JdbcFeeGroups(f.f.source,wrapped).confirm(new JdbcFeeGroups.Actor(1,111),f.f.owner,op,1,f.command.applicationId(),1);
  var result=read();assertEquals("1",result.get("quoteVersion"));assertEquals("2",result.get("currentGroupVersion"));var c=(Map<?,?>)result.get("customerConfirmation");assertEquals(op.toString(),c.get("operationId"));assertEquals("111",c.get("actorUserId"));assertEquals(result.get("quoteDigest"),c.get("quoteDigest"));assertEquals("1",c.get("quoteVersion"));
 }
 @Test void staleQuotePreservesFrozenThresholdNotNewGroupBasis()throws Exception{var replacement=f.f.command(1,100,900000,Set.of(f.command.additions().getFirst().id()));f.f.store.submit(f.f.actor,f.f.owner,replacement,f.f.details(replacement));var result=read();assertFalse((Boolean)result.get("current"));assertEquals("200000",result.get("thresholdCents"));assertEquals("250000",result.get("qCents"));assertEquals("1",result.get("quoteVersion"));assertEquals("2",result.get("currentGroupVersion"));}
 @Test void currentScopeAndDisabledEmployeeFailClosed()throws Exception{scopeCompany="999";assertThrows(Problem.class,this::read);scopeCompany="2";enabled=false;assertThrows(Problem.class,this::read);}
 @Test void forgedTransportAndExtraBodyNeverReadFacts(){var guarded=new FeeApprovalContextController(f.f.source);assertThrows(Problem.class,()->guarded.context(request(),new MockHttpServletRequest()));assertThrows(Problem.class,()->controller.context(request().replace("\"ticketId\"","\"unexpected\""),new MockHttpServletRequest()));}
 @Test void incompleteSourceCannotBecomeApprovedProjection()throws Exception{try(var db=f.f.source.getConnection();var s=db.createStatement()){s.executeUpdate("UPDATE finance_quote_source SET source_state='IN_PROGRESS',result_digest=NULL WHERE application_id='"+f.command.applicationId()+"'");}assertThrows(Problem.class,this::read);}
 @Test void laterActualCostInvalidatesCurrentApprovalWithoutChangingOriginalFrozenBasis()throws Exception{f.f.store.cost(f.f.actor,f.f.owner,UUID.randomUUID(),1,new FeeGroup.CostFact(UUID.randomUUID(),300000,null,"真实成本新增后旧方案依据不足"));var result=read();assertFalse((Boolean)result.get("current"));assertEquals("250000",result.get("bCents"));assertEquals("0",result.get("cCents"));assertEquals("1",result.get("quoteVersion"));assertEquals("2",result.get("currentGroupVersion"));}
}
