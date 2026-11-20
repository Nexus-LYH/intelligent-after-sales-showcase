package com.ias.finance;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class MoneyHttpTest {
 JdbcFeeGroupsTest f;MockMvc mvc;boolean authorityAvailable=true;
 @BeforeEach void setup()throws Exception{f=new JdbcFeeGroupsTest();f.setup();FinanceAccess access=new FinanceAccess(){
  public Principal principal(HttpServletRequest r,boolean write){return new Principal(f.actor,"EMPLOYEE");}
  public Scope scope(HttpServletRequest r,Principal p,UUID t){throw new UnsupportedOperationException();}
  public FeeGroup.PublishedRule rule(HttpServletRequest r){throw new UnsupportedOperationException();}
  public JdbcFeeGroups.Access bind(HttpServletRequest r,Principal p){return f.authority;}
  public JdbcMoneyLedger.Authority ledger(HttpServletRequest r,Principal p){if(!authorityAvailable)throw new com.ias.trust.Problem(503,"FINANCE_LEDGER_AUTHORITY_PENDING");return new JdbcMoneyLedger.Authority(){public void current(JdbcFeeGroups.Actor a,FeeGroup.Identity o,String action){f.authority.current(a,o,action);}public void verify(JdbcFeeGroups.Actor a,FeeGroup.Identity o,UUID op,JdbcMoneyLedger.Command command){}};}
 };mvc=MockMvcBuilders.standaloneSetup(new MoneyController(f.source,access)).addFilters(new FinanceRequestBoundary()).setControllerAdvice(new FinanceErrors()).build();}
 String base(){return "/api/finance/groups/"+f.owner.faultGroupId()+"/money";}
 Map<String,Object> assess(UUID op){return Map.of("operationId",op.toString(),"expectedVersion","0","expectedGroupVersion","0","billId",UUID.randomUUID().toString(),"completionId",UUID.randomUUID().toString(),"coverageDigest","a".repeat(64),"lines",List.of(Map.of("lineId",UUID.randomUUID().toString(),"amountCents","230000")));}
 @Test void persistentAmountProjectionAndOriginalResultAreStringsWithoutEvidenceLeak()throws Exception{UUID op=UUID.randomUUID();mvc.perform(post(base()+"/assess").header("Idempotency-Key",op).contentType("application/json").content(FinanceController.JSON.writeValueAsString(assess(op)))).andExpect(status().isOk()).andExpect(jsonPath("$.balance.receivableCents").value("230000")).andExpect(jsonPath("$.balance.version").value("1"));String read=mvc.perform(get(base()+"/operations/"+op)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertFalse(read.contains("coverageDigest")||read.contains("privateFileId")||read.contains("completionId"));long count=f.count("finance_operation");mvc.perform(get(base()+"/entries?afterVersion=0&limit=20")).andExpect(status().isOk()).andExpect(jsonPath("$.entries[0].action").value("RECEIVABLE_ASSESSED"));mvc.perform(get(base())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));assertEquals(count,f.count("finance_operation"));}
 @Test void missingAuthorityIs503WithoutAnyPartialAccountOrReceipt()throws Exception{authorityAvailable=false;UUID op=UUID.randomUUID();mvc.perform(post(base()+"/assess").header("Idempotency-Key",op).contentType("application/json").content(FinanceController.JSON.writeValueAsString(assess(op)))).andExpect(status().isServiceUnavailable());assertEquals(0,f.count("finance_money_account"));assertEquals(1,f.count("finance_operation"));}
 @Test void browserApprovalBooleanAndNumericCentsAreRejected()throws Exception{UUID op=UUID.randomUUID();var b=new HashMap<>(assess(op));b.put("approved",true);mvc.perform(post(base()+"/assess").header("Idempotency-Key",op).contentType("application/json").content(FinanceController.JSON.writeValueAsString(b))).andExpect(status().isBadRequest());b.remove("approved");b.put("lines",List.of(Map.of("lineId",UUID.randomUUID().toString(),"amountCents",230000)));mvc.perform(post(base()+"/assess").header("Idempotency-Key",op).contentType("application/json").content(FinanceController.JSON.writeValueAsString(b))).andExpect(status().isBadRequest());assertEquals(0,f.count("finance_money_entry"));}
 @Test void productionDefaultPortNeverClaimsVerifiedMoney(){FinanceAccess access=new FinanceAccess(){public Principal principal(HttpServletRequest r,boolean w){return null;}public Scope scope(HttpServletRequest r,Principal p,UUID t){return null;}public FeeGroup.PublishedRule rule(HttpServletRequest r){return null;}public JdbcFeeGroups.Access bind(HttpServletRequest r,Principal p){return null;}};assertThrows(com.ias.trust.Problem.class,()->access.ledger(null,null));}
}
