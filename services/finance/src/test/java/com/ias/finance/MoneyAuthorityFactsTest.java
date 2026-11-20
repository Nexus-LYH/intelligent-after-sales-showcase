package com.ias.finance;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import com.ias.trust.Problem;
import static org.junit.jupiter.api.Assertions.*;
import static com.ias.finance.MoneyAuthorityFacts.*;
import static com.ias.finance.JdbcMoneyLedger.*;
import static com.ias.finance.MoneyLedger.*;

class MoneyAuthorityFactsTest {
 final Clock clock=Clock.fixed(Instant.parse("2026-10-24T01:20:00Z"),ZoneOffset.UTC);
 final JdbcFeeGroups.Actor actor=new JdbcFeeGroups.Actor(1,10);
 final FeeGroup.Identity owner=new FeeGroup.Identity(1,2,UUID.randomUUID());
 final UUID op=UUID.randomUUID(),bill=UUID.randomUUID(),line=UUID.randomUUID();
 Header header(Command c){return new Header(binding(actor,owner,op,c),UUID.randomUUID(),clock.instant());}
 void check(Command c,Fact... facts){verify((b,x)->new Bundle(List.of(facts)),actor,owner,op,c,clock);}
 Problem rejected(Command c,Fact... facts){return assertThrows(Problem.class,()->check(c,facts));}
 Voucher voucher(){return new Voucher("synthetic-bank-reference",LocalDate.of(2026,10,24),"BANK_OFFLINE",UUID.randomUUID(),"b".repeat(64));}
 Assessment assessment(Assess c,boolean finance){
  var quote=UUID.randomUUID();var rule=new FeeGroup.PublishedRule("FROZEN_TEST_V1",200000);
  var submit=new FeeGroup.Submit(UUID.randomUUID(),quote,1,Set.of(),List.of(new FeeGroup.PlanLine(line,UUID.randomUUID(),UUID.randomUUID(),1000,false)),rule);
  var basis=new FeeGroup.Basis(owner,quote,2,1000,0,0,1000,rule,true,new FeeGroup.FinanceTrigger(quote,rule.version(),2));
  var q=new JdbcFeeGroups.QuoteVersion(submit,new JdbcFeeGroups.QuoteDetails(true,"synthetic quote",Map.of(line,"LABOR")),basis);
  var receipts=new HashMap<String,UUID>();receipts.put("SUPERVISOR",UUID.randomUUID());if(finance)receipts.put("FINANCE",UUID.randomUUID());
  return new Assessment(header(c),q,UUID.randomUUID(),c.approval().completionId(),quote,2,c.approval().coverageDigest(),c.approval().executedAuthorizedLines(),Map.of(line,UUID.randomUUID()),receipts,"COMPLETED");
 }
 @Test void assessmentRequiresPermanentFinanceDecisionAndExecutedCoverage(){
  var c=new Assess(0,3,bill,new Approval(UUID.randomUUID(),"a".repeat(64),Map.of(line,900L)));
  assertDoesNotThrow(()->check(c,assessment(c,true)));
  assertEquals("FINANCE_REQUIRED_APPROVAL_RECEIPTS_MISSING",rejected(c,assessment(c,false)).code);
  var excess=new Assess(0,3,bill,new Approval(UUID.randomUUID(),"a".repeat(64),Map.of(line,1001L)));
  assertEquals("FINANCE_AUTHORIZED_COVERAGE_MISMATCH",rejected(excess,assessment(excess,true)).code);
 }
 @Test void reductionRequiresOriginalBillLineApprovalAndConfirmation(){
  var c=new Lower(2,3,bill,line,100,UUID.randomUUID(),"a".repeat(64),"approved reduction");
  var fact=new Reduction(header(c),bill,line,100,c.adjustmentApprovalId(),UUID.randomUUID(),UUID.randomUUID(),c.coverageDigest(),"COMPLETED");
  assertDoesNotThrow(()->check(c,fact));
  assertEquals(503,rejected(c,new Reduction(header(c),UUID.randomUUID(),line,100,c.adjustmentApprovalId(),fact.customerConfirmation(),fact.financeDecision(),c.coverageDigest(),"COMPLETED")).status);
 }
 @Test void collectionNeedsBothActualTransferAndBoundPrivateFile(){
  var p=new Payment(UUID.randomUUID(),bill,500,voucher());var c=new Collect(1,3,bill,p);
  var transfer=new Transfer(header(c),"RECEIVED",p.id(),null,p.cents(),p.voucher(),"COMPLETED");
  var file=new PrivateVoucher(header(c),p.id(),p.voucher(),"FINANCE_COLLECTION","AVAILABLE");
  assertDoesNotThrow(()->check(c,transfer,file));
  assertEquals("FINANCE_AUTHORITY_FACT_MISSING",rejected(c,transfer).code);
  assertEquals(503,rejected(c,transfer,new PrivateVoucher(header(c),p.id(),voucher(),"FINANCE_COLLECTION","AVAILABLE")).status);
 }
 @Test void refundPromiseOrDifferentOriginalPaymentCannotProveActualRefund(){
  var refund=new Refund(UUID.randomUUID(),UUID.randomUUID(),500,voucher());var c=new RefundPaid(2,3,refund);
  var file=new PrivateVoucher(header(c),refund.id(),refund.voucher(),"FINANCE_REFUND","AVAILABLE");
  assertDoesNotThrow(()->check(c,new Transfer(header(c),"REFUNDED",refund.id(),refund.originalPaymentId(),500,refund.voucher(),"COMPLETED"),file));
  assertEquals(503,rejected(c,new Transfer(header(c),"REFUNDED",refund.id(),UUID.randomUUID(),500,refund.voucher(),"COMPLETED"),file).status);
  assertEquals(503,rejected(c,new Transfer(header(c),"REFUNDED",refund.id(),refund.originalPaymentId(),500,refund.voucher(),"PROMISED"),file).status);
 }
 @Test void reversalCannotEraseRealUnreturnedFunds(){
  var c=new Reverse(2,3,UUID.randomUUID(),UUID.randomUUID(),"verified entry error");
  assertDoesNotThrow(()->check(c,new EntryError(header(c),c.originalPaymentId(),c.correctionEvidenceId(),0,"VERIFIED_ENTRY_ERROR")));
  assertEquals("CORRECTION_REQUIRES_ATOMIC_REBOOKING",rejected(c,new EntryError(header(c),c.originalPaymentId(),c.correctionEvidenceId(),500,"VERIFIED_ENTRY_ERROR")).code);
 }
 @Test void rebookRequiresExactVerifiedUnreturnedMoneyAndPrivateVoucher(){
  var original=UUID.randomUUID();var replacement=new Payment(UUID.randomUUID(),bill,500,voucher(),original);
  var c=new Rebook(2,3,original,UUID.randomUUID(),"correct entry",replacement);
  var evidence=new EntryError(header(c),original,c.correctionEvidenceId(),500,"VERIFIED_ENTRY_ERROR");
  var file=new PrivateVoucher(header(c),replacement.id(),replacement.voucher(),"FINANCE_COLLECTION","AVAILABLE");
  assertDoesNotThrow(()->check(c,evidence,file));
  assertEquals(503,rejected(c,new EntryError(header(c),original,c.correctionEvidenceId(),499,"VERIFIED_ENTRY_ERROR"),file).status);
  assertEquals(503,rejected(c,evidence,new PrivateVoucher(header(c),replacement.id(),voucher(),"FINANCE_COLLECTION","AVAILABLE")).status);
  assertEquals(503,rejected(c,evidence).status);
 }
 @Test void wrongActorOrOperationAndStaleFactFailClosed(){
  var c=new Reverse(2,3,UUID.randomUUID(),UUID.randomUUID(),"entry error");
  for(var wrong:List.of(new Header(binding(new JdbcFeeGroups.Actor(1,11),owner,op,c),UUID.randomUUID(),clock.instant()),new Header(binding(actor,owner,UUID.randomUUID(),c),UUID.randomUUID(),clock.instant()),new Header(binding(actor,owner,op,c),UUID.randomUUID(),clock.instant().minusSeconds(3)),new Header(binding(actor,owner,op,c),UUID.randomUUID(),clock.instant().plusSeconds(1))))
   assertEquals(503,rejected(c,new EntryError(wrong,c.originalPaymentId(),c.correctionEvidenceId(),0,"VERIFIED_ENTRY_ERROR")).status);
 }
 @Test void duplicateOrMalformedFactsAreUnavailableRatherThanClientErrors(){
  var c=new Reverse(2,3,UUID.randomUUID(),UUID.randomUUID(),"entry error");var fact=new EntryError(header(c),c.originalPaymentId(),c.correctionEvidenceId(),0,"VERIFIED_ENTRY_ERROR");
  assertEquals(503,rejected(c,fact,fact).status);
  assertEquals(503,assertThrows(Problem.class,()->verify((b,x)->{throw new NullPointerException("malformed owner payload");},actor,owner,op,c,clock)).status);
 }
 @Test void defaultProviderRemainsUnavailableAndChangedCommandChangesBinding(){
  var c=new Reverse(2,3,UUID.randomUUID(),UUID.randomUUID(),"entry error");
  assertEquals("FINANCE_CORRECTION_EVIDENCE_PENDING",assertThrows(Problem.class,()->verify(unavailable(),actor,owner,op,c,clock)).code);
  assertNotEquals(binding(actor,owner,op,c).authorityDigest(),binding(actor,owner,op,new Reverse(2,3,c.originalPaymentId(),c.correctionEvidenceId(),"changed reason")).authorityDigest());
 }
}
