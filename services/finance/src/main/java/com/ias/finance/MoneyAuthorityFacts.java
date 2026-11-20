package com.ias.finance;

import com.ias.trust.Problem;
import java.time.*;
import java.util.*;
import static com.ias.finance.JdbcFeeGroups.*;
import static com.ias.finance.JdbcMoneyLedger.*;
import static com.ias.finance.MoneyLedger.*;

/** Finance-side validation of server-obtained facts. This does not authenticate a peer or invent an owner route. */
public final class MoneyAuthorityFacts {
 private MoneyAuthorityFacts(){}
 public record Binding(Actor actor,FeeGroup.Identity owner,UUID operation,String action,long accountVersion,long groupVersion,String authorityDigest){}
 public record Header(Binding binding,UUID receiptId,Instant checkedAt){}
 public sealed interface Fact permits Assessment,Reduction,Transfer,PrivateVoucher,EntryError {Header header();}
 /** A server-side aggregate: F owns quote/confirmation, P owns decisions, execution owners own execution receipts. */
 public record Assessment(Header header,QuoteVersion quote,UUID customerConfirmation,UUID completedApprovalId,UUID approvedQuoteId,long approvedQuoteVersion,String coverageDigest,Map<UUID,Long> executedAuthorizedCents,Map<UUID,UUID> executionReceipts,Map<String,UUID> decisionReceipts,String state)implements Fact{
  public Assessment{executedAuthorizedCents=Map.copyOf(executedAuthorizedCents);executionReceipts=Map.copyOf(executionReceipts);decisionReceipts=Map.copyOf(decisionReceipts);}
 }
 public record Reduction(Header header,UUID billId,UUID lineId,long newCents,UUID approvalId,UUID customerConfirmation,UUID financeDecision,String coverageDigest,String state)implements Fact{}
 public record Transfer(Header header,String movement,UUID businessId,UUID originalPaymentId,long cents,Voucher voucher,String state)implements Fact{}
 public record PrivateVoucher(Header header,UUID businessId,Voucher voucher,String purpose,String state)implements Fact{}
 public record EntryError(Header header,UUID originalPaymentId,UUID correctionEvidenceId,long actualUnreturnedCents,String state)implements Fact{}
 public record Bundle(List<Fact> facts){public Bundle{facts=List.copyOf(facts);}}
 @FunctionalInterface public interface Provider {Bundle obtain(Binding binding,Command command);}
 public static Binding binding(Actor actor,FeeGroup.Identity owner,UUID operation,Command command){
  Objects.requireNonNull(actor);Objects.requireNonNull(owner);Objects.requireNonNull(operation);validate(command);
  if(actor.tenantId()!=owner.tenantId())throw new SecurityException("TARGET_NOT_ACCESSIBLE");
  return new Binding(actor,owner,operation,action(command),command.expectedVersion(),command.expectedGroupVersion(),digest(List.of(actor,owner,operation,action(command),command)));
 }
 public static Provider unavailable(){return (binding,command)->{throw new Problem(503,command instanceof Assess?"FINANCE_COMPLETED_APPROVAL_FACT_PENDING":command instanceof Lower?"FINANCE_ADJUSTMENT_APPROVAL_FACT_PENDING":command instanceof Reverse?"FINANCE_CORRECTION_EVIDENCE_PENDING":command instanceof RefundPaid?"FINANCE_ACTUAL_REFUND_PROOF_PENDING":"FINANCE_COLLECTION_PROOF_PENDING");};}
 public static void verify(Provider provider,Actor actor,FeeGroup.Identity owner,UUID operation,Command command,Clock clock){
  Binding expected=binding(actor,owner,operation,command);
  try{verifyFacts(provider,expected,owner,command,clock);}catch(Problem failure){throw failure;}catch(RuntimeException malformed){throw invalid();}
 }
 private static void verifyFacts(Provider provider,Binding expected,FeeGroup.Identity owner,Command command,Clock clock){
  Bundle bundle=provider.obtain(expected,command);
  if(bundle==null)throw missing();var facts=new HashMap<Class<?>,Fact>();Instant now=clock.instant();
  for(Fact fact:bundle.facts()){
   if(fact==null||fact.header()==null||!expected.equals(fact.header().binding())||fact.header().receiptId()==null||fact.header().checkedAt()==null)throw invalid();
   Instant checked=fact.header().checkedAt();if(checked.isBefore(now.minusSeconds(2))||checked.isAfter(now.plusMillis(250)))throw new Problem(503,"FINANCE_AUTHORITY_FACT_STALE");
   if(facts.put(fact.getClass(),fact)!=null)throw invalid();
  }
  if(command instanceof Assess c){
   exact(facts,Set.of(Assessment.class));var proof=(Assessment)facts.get(Assessment.class);var q=proof.quote();
   if(q==null||q.basis()==null||!q.basis().owner().equals(owner)||!q.details().chargesCustomer()||!q.command().applicationId().equals(q.basis().applicationId())||proof.customerConfirmation()==null||!"COMPLETED".equals(proof.state())||!c.approval().completionId().equals(proof.completedApprovalId())||!q.command().applicationId().equals(proof.approvedQuoteId())||q.basis().groupVersion()!=proof.approvedQuoteVersion()||!c.approval().coverageDigest().equals(proof.coverageDigest())||!c.approval().executedAuthorizedLines().equals(proof.executedAuthorizedCents())||!proof.executionReceipts().keySet().equals(proof.executedAuthorizedCents().keySet()))throw invalid();
   if(!proof.decisionReceipts().containsKey("SUPERVISOR")||q.basis().financialReviewRequired()&&!proof.decisionReceipts().containsKey("FINANCE"))throw new Problem(503,"FINANCE_REQUIRED_APPROVAL_RECEIPTS_MISSING");
   var limits=new HashMap<UUID,Long>();q.command().additions().forEach(line->limits.put(line.id(),line.amountCents()));
   for(var entry:proof.executedAuthorizedCents().entrySet())if(!limits.containsKey(entry.getKey())||entry.getValue()<=0||entry.getValue()>limits.get(entry.getKey()))throw new Problem(503,"FINANCE_AUTHORIZED_COVERAGE_MISMATCH");
  }else if(command instanceof Lower c){
   exact(facts,Set.of(Reduction.class));var proof=(Reduction)facts.get(Reduction.class);
   if(!"COMPLETED".equals(proof.state())||!c.billId().equals(proof.billId())||!c.lineId().equals(proof.lineId())||c.newCents()!=proof.newCents()||!c.adjustmentApprovalId().equals(proof.approvalId())||!c.coverageDigest().equals(proof.coverageDigest())||proof.customerConfirmation()==null||proof.financeDecision()==null)throw invalid();
  }else if(command instanceof Reverse c){
   exact(facts,Set.of(EntryError.class));var proof=(EntryError)facts.get(EntryError.class);
   if(!"VERIFIED_ENTRY_ERROR".equals(proof.state())||!c.originalPaymentId().equals(proof.originalPaymentId())||!c.correctionEvidenceId().equals(proof.correctionEvidenceId())||proof.actualUnreturnedCents()<0)throw invalid();
   if(proof.actualUnreturnedCents()!=0)throw new Problem(409,"CORRECTION_REQUIRES_ATOMIC_REBOOKING");
  }else if(command instanceof Rebook c){
   exact(facts,Set.of(EntryError.class,PrivateVoucher.class));var proof=(EntryError)facts.get(EntryError.class);var file=(PrivateVoucher)facts.get(PrivateVoucher.class);var replacement=c.replacement();
   if(!"VERIFIED_ENTRY_ERROR".equals(proof.state())||!c.originalPaymentId().equals(proof.originalPaymentId())||!c.correctionEvidenceId().equals(proof.correctionEvidenceId())||proof.actualUnreturnedCents()<=0||proof.actualUnreturnedCents()!=replacement.cents())throw invalid();
   if(!replacement.id().equals(file.businessId())||!replacement.voucher().equals(file.voucher())||!"FINANCE_COLLECTION".equals(file.purpose())||!"AVAILABLE".equals(file.state()))throw invalid();
  }else{
   exact(facts,Set.of(Transfer.class,PrivateVoucher.class));var transfer=(Transfer)facts.get(Transfer.class);var file=(PrivateVoucher)facts.get(PrivateVoucher.class);
   UUID business,original;long cents;Voucher voucher;String movement,purpose;
   if(command instanceof Collect c){business=c.payment().id();original=c.payment().correctionOf();cents=c.payment().cents();voucher=c.payment().voucher();movement="RECEIVED";purpose="FINANCE_COLLECTION";}
   else{var c=(RefundPaid)command;business=c.refund().id();original=c.refund().originalPaymentId();cents=c.refund().cents();voucher=c.refund().voucher();movement="REFUNDED";purpose="FINANCE_REFUND";}
   if(!movement.equals(transfer.movement())||!"COMPLETED".equals(transfer.state())||!business.equals(transfer.businessId())||!Objects.equals(original,transfer.originalPaymentId())||cents!=transfer.cents()||!voucher.equals(transfer.voucher())||!business.equals(file.businessId())||!voucher.equals(file.voucher())||!purpose.equals(file.purpose())||!"AVAILABLE".equals(file.state()))throw invalid();
  }
 }
 static void exact(Map<Class<?>,Fact> facts,Set<Class<?>> required){if(!facts.keySet().equals(required))throw missing();}
 static Problem invalid(){return new Problem(503,"FINANCE_AUTHORITY_FACT_MISMATCH");}
 static Problem missing(){return new Problem(503,"FINANCE_AUTHORITY_FACT_MISSING");}
}
