package com.ias.finance;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import com.ias.trust.Problem;
import org.h2.tools.RunScript;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ias.finance.JdbcMoneyLedger.*;
import static com.ias.finance.MoneyLedger.*;

class TrustedManualMoneyTest {
 QuoteSourcesTest f;TrustedFinanceAccess access;MockHttpServletRequest request;UUID completion=UUID.randomUUID(),application=UUID.randomUUID(),bill=UUID.randomUUID();String mode="VALID";Map<String,Object> fact;int voucherCalls;
 @BeforeEach void setup()throws Exception{f=new QuoteSourcesTest();f.setup();f.f.store.submit(f.f.actor,f.f.owner,f.command,f.details);try(var db=f.f.source.getConnection()){RunScript.execute(db,new InputStreamReader(getClass().getResourceAsStream("/db/migration/V6__manual_money_facts.sql"),StandardCharsets.UTF_8));}
  var line=f.command.additions().getFirst();var lines=List.of(new TreeMap<>(Map.of("id",line.id().toString(),"ticketId",line.ticketId().toString(),"roundId",line.roundId().toString(),"amountCents",Long.toString(line.amountCents()),"kind","LABOR")));
  fact=new HashMap<>();fact.putAll(Map.of("tenantId","1","companyId","2","groupId",f.f.owner.faultGroupId().toString(),"completionId",completion.toString(),"applicationId",application.toString(),"quoteId",f.command.applicationId().toString(),"quoteVersion","1","quoteDigest",JdbcFeeGroups.quoteDigest(f.f.owner,f.command,f.details),"coverageDigest",JdbcMoneyLedger.digest(lines),"state","COMPLETED"));fact.putAll(Map.of("financialReviewRequired",true,"supervisorReceiptId",UUID.randomUUID().toString(),"financeReceiptId",UUID.randomUUID().toString(),"generation","1","ticketId",line.ticketId().toString(),"roundId",line.roundId().toString(),"faultRelationId",f.relation.toString(),"lines",lines));
  new JdbcMoneyLedger(f.f.source,new Authority(){public void current(JdbcFeeGroups.Actor a,FeeGroup.Identity o,String x){}public void verify(JdbcFeeGroups.Actor a,FeeGroup.Identity o,UUID op,Command c){}}).apply(f.f.actor,f.f.owner,UUID.randomUUID(),new Assess(0,1,bill,new Approval(completion,JdbcMoneyLedger.digest(lines),Map.of(line.id(),250000L))));
  request=new MockHttpServletRequest();request.addHeader("Cookie","IAS-SESSION-DEV=synthetic_manual_session");request.addHeader("Origin","http://127.0.0.1:5173");request.addHeader("X-CSRF-TOKEN","synthetic_manual_csrf");
  access=new TrustedFinanceAccess((edge,path,body,r)->{var out=new HashMap<String,Object>();if(edge.equals("finance-identity")){assertTrue(Set.of("MONEY_READ","MONEY_WRITE").contains(body.get("purpose")));out.putAll(Map.of("tenantId","1","userId","10","identityType","EMPLOYEE","enabled",true,"departmentEnabled",true,"departmentId","8","companyIds",List.of("2"),"permissions",mode.equals("REVOKED")?List.of():List.of("finance.money.read","finance.money.adjust","finance.money.collect","finance.money.reverse","finance.money.refund")));}
   else if(edge.equals("finance-approval")){assertEquals(Set.of("tenantId","companyId","groupId","completionId"),body.keySet());out.putAll(fact);if(mode.equals("MISSING_FINANCE"))out.put("financeReceiptId",null);}
   else{voucherCalls++;if(mode.equals("OWNER_404"))throw new Problem(404,"OWNER_NOT_FOUND");if(mode.equals("OWNER_TIMEOUT"))throw new RuntimeException("synthetic timeout");assertEquals("/internal/ticket/finance-voucher-fact",path);assertEquals(8,body.size());for(String key:List.of("companyId","ticketId","groupId","businessId","fileId","purpose"))out.put(key,body.get(key));out.putAll(Map.of("tenantId","1","actorUserId","10","sha256",mode.equals("WRONG_FILE")?"0".repeat(64):body.get("evidenceDigest"),"size","1024","state","AVAILABLE"));}
   out.put("checkedAt",Instant.now().toString());return out;},f.f.source);
 }
 JdbcMoneyLedger production(){var p=access.moneyPrincipal(request,true);return new JdbcMoneyLedger(f.f.source,access.ledger(request,p));}
 Collect command(){return new Collect(1,1,bill,new Payment(UUID.randomUUID(),bill,100000,new Voucher("manual-real-reference",LocalDate.of(2026,10,24),"OFFLINE_BANK",UUID.randomUUID(),"b".repeat(64))));}
 @Test void productionAdapterUsesPOriginalCompletionAndPrivateVoucherWithoutQuotePermissions()throws Exception{var c=command();var op=UUID.randomUUID();var ledger=production();var result=ledger.apply(f.f.actor,f.f.owner,op,c);assertEquals(result,ledger.apply(f.f.actor,f.f.owner,op,c));assertEquals(1,f.f.count("finance_manual_money_fact"));assertEquals(100000,result.balance().collected());assertEquals(2,voucherCalls);}
 @Test void wrongPrivateFileMissingDecisionAndRevocationCannotWrite()throws Exception{for(String failure:List.of("WRONG_FILE","MISSING_FINANCE","REVOKED")){mode=failure;assertThrows(Problem.class,()->production().apply(f.f.actor,f.f.owner,UUID.randomUUID(),command()));assertEquals(0,f.f.count("finance_manual_money_fact"));assertEquals(0,new JdbcMoneyLedger(f.f.source,new Authority(){public void current(JdbcFeeGroups.Actor a,FeeGroup.Identity o,String x){}public void verify(JdbcFeeGroups.Actor a,FeeGroup.Identity o,UUID op,Command c){}}).read(f.f.actor,f.f.owner).collected());}}
 @Test void missingOwnerAndTimeoutRemainServiceUnavailable()throws Exception{for(String failure:List.of("OWNER_404","OWNER_TIMEOUT")){mode=failure;var problem=assertThrows(Problem.class,()->production().apply(f.f.actor,f.f.owner,UUID.randomUUID(),command()));assertEquals(503,problem.status);assertEquals("FINANCE_AUTHORITY_UNAVAILABLE",problem.code);assertEquals(0,f.f.count("finance_money_terminal_fact"));}}
 @Test void correctionRequiresIndependentPersistedReviewAndExactNonzeroRebook()throws Exception{
  var initial=command();var ledger=production();ledger.apply(f.f.actor,f.f.owner,UUID.randomUUID(),initial);var payment=initial.payment();var reviewId=UUID.randomUUID();String reason="reviewed offline bank evidence";
  var proposal=new MoneyReviewStore.Proposal(reviewId,"CORRECTION",2,1,payment.id(),bill,null,90000,null,UUID.randomUUID(),"c".repeat(64),reason);
  var reviews=new MoneyReviewStore(f.f.source,(a,o,e,p)->{});reviews.submit(f.f.actor,f.f.owner,UUID.randomUUID(),proposal);
  var wrong=new Rebook(2,1,payment.id(),reviewId,reason,new Payment(UUID.randomUUID(),bill,80000,payment.voucher(),payment.id()));assertEquals(503,assertThrows(Problem.class,()->ledger.apply(f.f.actor,f.f.owner,UUID.randomUUID(),wrong)).status);
  reviews.verifyCorrection(new JdbcFeeGroups.Actor(1,11),f.f.owner,UUID.randomUUID(),reviewId,new MoneyReviewStore.Verification(90000,proposal.evidenceFileId(),proposal.evidenceDigest(),"Independently checked bank evidence"));assertEquals(503,assertThrows(Problem.class,()->ledger.apply(f.f.actor,f.f.owner,UUID.randomUUID(),wrong)).status);
  var reverse=new Reverse(2,1,payment.id(),reviewId,reason);assertEquals(409,assertThrows(Problem.class,()->ledger.apply(f.f.actor,f.f.owner,UUID.randomUUID(),reverse)).status);
  var replacement=new Payment(UUID.randomUUID(),bill,90000,payment.voucher(),payment.id());var corrected=new Rebook(2,1,payment.id(),reviewId,reason,replacement);var correctedReceipt=ledger.apply(f.f.actor,f.f.owner,UUID.randomUUID(),corrected);assertEquals(90000,correctedReceipt.balance().collected());
 }
 @Test void reductionRequiresActualConfirmedAndApprovedReview()throws Exception{
  var line=f.command.additions().getFirst();var digest=(String)fact.get("coverageDigest");var review=UUID.randomUUID();String reason="customer confirmed adjustment";
  var proposal=new MoneyReviewStore.Proposal(review,"REDUCTION",1,1,line.id(),bill,line.id(),180000,digest,null,null,reason);var reviews=new MoneyReviewStore(f.f.source,(a,o,e,p)->{});reviews.submit(f.f.actor,f.f.owner,UUID.randomUUID(),proposal);var ledger=production();var command=new Lower(1,1,bill,line.id(),180000,review,digest,reason);
  assertEquals(503,assertThrows(Problem.class,()->ledger.apply(f.f.actor,f.f.owner,UUID.randomUUID(),command)).status);reviews.decide(new JdbcFeeGroups.Actor(1,20),f.f.owner,UUID.randomUUID(),review,"CUSTOMER_CONFIRMED");
  assertEquals(503,assertThrows(Problem.class,()->ledger.apply(f.f.actor,f.f.owner,UUID.randomUUID(),command)).status);reviews.decide(new JdbcFeeGroups.Actor(1,11),f.f.owner,UUID.randomUUID(),review,"FINANCE_APPROVED");
  UUID op=UUID.randomUUID();var receipt=ledger.apply(f.f.actor,f.f.owner,op,command);assertEquals(180000,receipt.balance().receivable());assertEquals(receipt,ledger.apply(f.f.actor,f.f.owner,op,command));
  var changed=new Lower(1,1,bill,line.id(),180001,review,digest,reason);
  var conflict=assertThrows(IllegalStateException.class,()->ledger.apply(f.f.actor,f.f.owner,op,changed));assertEquals("OPERATION_CONFLICT",conflict.getMessage());
  assertEquals(180000,ledger.read(f.f.actor,f.f.owner).receivable());mode="REVOKED";
  assertEquals(403,assertThrows(Problem.class,()->ledger.apply(f.f.actor,f.f.owner,op,changed)).status);
 }
}
