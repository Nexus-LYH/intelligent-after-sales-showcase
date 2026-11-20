package com.ias.finance;
import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static com.ias.finance.FeeGroup.*;
import static com.ias.finance.JdbcFeeGroups.*;

/** Finance candidate contract. Missing exact BE-0 edges/owner ports fail closed, never fall back to booleans. */
public final class TrustedFinanceAccess implements FinanceAccess {
 @FunctionalInterface interface Calls {Map<String,Object> call(String edge,String path,Map<String,Object> body,HttpServletRequest r);}
 private final Calls calls;
 private javax.sql.DataSource dataSource;
 private final MoneyAuthorityFacts.Provider moneyFacts;
 public TrustedFinanceAccess(){this((edge,path,body,r)->{if(!Chain.active()||!"finance".equals(Chain.role()))throw new Problem(503,"FINANCE_TRUST_NOT_CONFIGURED");return Chain.call(edge,path,body,Chain.trace(r),Chain.deadline(r));});}
 public TrustedFinanceAccess(javax.sql.DataSource source){this();this.dataSource=Objects.requireNonNull(source);}
 TrustedFinanceAccess(Calls calls){this(calls,MoneyAuthorityFacts.unavailable());}
 TrustedFinanceAccess(Calls calls,javax.sql.DataSource source){this(calls);this.dataSource=Objects.requireNonNull(source);}
 TrustedFinanceAccess(Calls calls,MoneyAuthorityFacts.Provider moneyFacts){this.calls=Objects.requireNonNull(calls);this.moneyFacts=Objects.requireNonNull(moneyFacts);}
 Map<String,Object> call(String edge,String path,Map<String,Object> body,HttpServletRequest r){var f=calls.call(edge,path,body,r);Chain.fresh(f,Chain.deadline(r));return f;}
 Map<String,Object> identity(HttpServletRequest r,boolean write){var b=new LinkedHashMap<String,Object>();String token=Chain.cookie(r);if(token==null||token.isBlank())throw new Problem(401,"SESSION_INVALID");b.put("sessionToken",token);b.put("purpose",write?"FINANCE_WRITE":"FINANCE_READ");if(write){Chain.source(r);b.put("csrfToken",Chain.single(r,"X-CSRF-TOKEN"));}var f=call("finance-identity","/internal/identity/domain-authorizations",b,r);if(!Boolean.TRUE.equals(f.get("enabled")))throw new Problem(401,"SESSION_INVALID");return f;}
 public Principal principal(HttpServletRequest r,boolean write){var f=identity(r,write);return new Principal(new Actor(number(f.get("tenantId")),number(f.get("userId"))),Objects.toString(f.get("identityType"),""));}
 Map<String,Object> feeIdentity(HttpServletRequest r){String token=Chain.cookie(r);if(token==null||token.isBlank())throw new Problem(401,"SESSION_INVALID");var f=call("finance-identity","/internal/identity/domain-authorizations",Map.of("sessionToken",token,"purpose","FEE_READ"),r);if(!Boolean.TRUE.equals(f.get("enabled")))throw new Problem(401,"SESSION_INVALID");if(!"EMPLOYEE".equals(f.get("identityType"))||!Boolean.TRUE.equals(f.get("departmentEnabled"))||f.get("departmentId")==null||!((List<?>)f.getOrDefault("permissions",List.of())).contains("approval.fees.read"))throw new Problem(403,"ACCESS_DENIED");return f;}
 public Principal feePrincipal(HttpServletRequest r){var f=feeIdentity(r);return new Principal(new Actor(number(f.get("tenantId")),number(f.get("userId"))),"EMPLOYEE");}
 public Access feeBind(HttpServletRequest r,Principal p){return new Access(){
  public void current(Actor a,Identity o,String action){if(!"READ".equals(action))throw new Problem(403,"ACCESS_DENIED");var f=feeIdentity(r);match(p,f);if(!a.equals(p.actor())||a.tenantId()!=o.tenantId()||!((List<?>)f.getOrDefault("companyIds",List.of())).contains(Long.toString(o.customerCompanyId())))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");}
  public void submission(Actor a,Identity o,Submit s,QuoteDetails d){throw new Problem(403,"ACCESS_DENIED");}
  public void approval(Actor a,Identity o,UUID i,ApprovalFact f){throw new Problem(403,"ACCESS_DENIED");}
  public void verifiedCost(Actor a,Identity o,CostFact f){throw new Problem(403,"ACCESS_DENIED");}
  public void execution(Actor a,Identity o,UUID i){throw new Problem(403,"ACCESS_DENIED");}
 };}
 Map<String,Object> moneyIdentity(HttpServletRequest r,boolean write){String token=Chain.cookie(r);if(token==null||token.isBlank())throw new Problem(401,"SESSION_INVALID");var b=new LinkedHashMap<String,Object>();b.put("sessionToken",token);b.put("purpose",write?"MONEY_WRITE":"MONEY_READ");if(write){Chain.source(r);b.put("csrfToken",Chain.single(r,"X-CSRF-TOKEN"));}var f=call("finance-identity","/internal/identity/domain-authorizations",b,r);if(!Boolean.TRUE.equals(f.get("enabled"))||!"EMPLOYEE".equals(f.get("identityType")))throw new Problem(403,"ACCESS_DENIED");return f;}
 public Principal moneyPrincipal(HttpServletRequest r,boolean write){var f=moneyIdentity(r,write);return new Principal(new Actor(number(f.get("tenantId")),number(f.get("userId"))),"EMPLOYEE");}
 public Principal reviewPrincipal(HttpServletRequest r){
  try{return moneyPrincipal(r,false);}catch(Problem denied){
   if(denied.status!=403)throw denied;
   var f=call("finance-identity","/internal/identity/domain-authorizations",Map.of("sessionToken",Chain.cookie(r),"purpose","FINANCE_READ"),r);Chain.fresh(f,Chain.deadline(r));
   if(!Boolean.TRUE.equals(f.get("enabled"))||!"CUSTOMER".equals(f.get("identityType")))throw denied;
   return new Principal(new Actor(number(f.get("tenantId")),number(f.get("userId"))),"CUSTOMER");
  }
 }
 public Identity moneyOwner(javax.sql.DataSource source,HttpServletRequest r,Principal p,UUID group)throws java.sql.SQLException{return JdbcFeeGroups.groupOwner(source,p.actor(),group);}
 void match(Principal p,Map<String,Object> f){if(!Long.toString(p.actor().tenantId()).equals(f.get("tenantId"))||!Long.toString(p.actor().userId()).equals(f.get("userId"))||!p.type().equals(f.get("identityType")))throw new Problem(401,"SESSION_INVALID");}
 static void requirePartOwner(JdbcFeeGroups.QuoteDetails details){if(!details.partBindings().isEmpty()||details.chargesCustomer()&&details.kinds().containsValue("PART"))throw new Problem(503,"FINANCE_PART_COVERAGE_AUTHORITY_PENDING");}
 static void requirePartShape(JdbcFeeGroups.QuoteDetails details){if(details.chargesCustomer()||!details.partBindings().isEmpty())for(var line:details.kinds().entrySet())if("PART".equals(line.getValue())&&!details.partBindings().containsKey(line.getKey()))throw new Problem(503,"FINANCE_PART_COVERAGE_AUTHORITY_PENDING");}
 void partOwners(HttpServletRequest r,Actor actor,Identity owner,Submit command,JdbcFeeGroups.QuoteDetails details){
  requirePartShape(details);
  for(var line:command.additions()){
   var binding=details.partBindings().get(line.id());if(binding==null)continue;
   var proof=call("finance-approval","/internal/approval/finance-part-binding",Map.of("sessionToken",Chain.cookie(r),"approvalId",binding.approvalId().toString(),"warehouseId",Long.toString(binding.warehouseId()),"ticketId",line.ticketId().toString(),"roundId",line.roundId().toString()),r);
   var expected=Map.of("tenantId",Long.toString(owner.tenantId()),"companyId",Long.toString(owner.customerCompanyId()),"approvalId",binding.approvalId().toString(),"approvalVersion",Long.toString(binding.approvalVersion()),"warehouseId",Long.toString(binding.warehouseId()),"ticketId",line.ticketId().toString(),"roundId",line.roundId().toString(),"unitCode",binding.unitCode(),"chargeMode","CUSTOMER_PAID");
   for(var e:expected.entrySet())if(!Objects.equals(e.getValue(),proof.get(e.getKey())))throw new Problem(503,"FINANCE_PART_OWNER_FACT_MISMATCH");
   if(!List.of(Map.of("partId",Long.toString(binding.partId()),"quantity",Long.toString(binding.quantity()))).equals(proof.get("lines")))throw new Problem(503,"FINANCE_PART_OWNER_FACT_MISMATCH");
  }
 }
 public Scope scope(HttpServletRequest r,Principal p,UUID ticket){var f=call("finance-ticket","/internal/ticket/finance-context",Map.of("sessionToken",Chain.cookie(r),"ticketId",ticket.toString()),r);if(!Long.toString(p.actor().tenantId()).equals(f.get("tenantId"))||!ticket.toString().equals(f.get("ticketId")))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");long company=number(f.get("companyId"));UUID relation=ownerUuid(f.get("faultRelationId"));UUID group=UUID.nameUUIDFromBytes(("finance:"+p.actor().tenantId()+":"+company+":"+relation).getBytes(StandardCharsets.UTF_8));return new Scope(new Identity(p.actor().tenantId(),company,group),ticket,ownerUuid(f.get("roundId")));}
 public PublishedRule rule(HttpServletRequest r){var f=call("finance-approval","/internal/approval/fee-rule",Map.of("sessionToken",Chain.cookie(r)),r);if(!Boolean.TRUE.equals(f.get("published")))throw new Problem(503,"FEE_RULE_UNAVAILABLE");return new PublishedRule(f.get("version").toString(),nonnegative(f.get("thresholdCents")));}
 public Access bind(HttpServletRequest r,Principal p){return new Access(){
  public void approvalHighWater(java.sql.Connection db,Actor actor,Identity owner)throws java.sql.SQLException{var group=call("finance-approval","/internal/approval/fee-completions/group",Map.of("tenantId",Long.toString(owner.tenantId()),"companyId",Long.toString(owner.customerCompanyId()),"groupId",owner.faultGroupId().toString()),r);FeeCompletionMirror.highWater(db,owner,group);}
  public void current(Actor actor,Identity owner,String action){var f=identity(r,!action.equals("READ"));match(p,f);if(!actor.equals(p.actor())||actor.tenantId()!=owner.tenantId())throw new SecurityException("TARGET_NOT_ACCESSIBLE");String permission=action.equals("READ")?"finance.quote.read":Set.of("CUSTOMER_QUOTE_CONFIRMED","CUSTOMER_QUOTE_REJECTED").contains(action)?"finance.quote.confirm":"finance.quote.write";if(!((List<?>)f.getOrDefault("permissions",List.of())).contains(permission))throw new Problem(403,"ACCESS_DENIED");if(p.type().equals("EMPLOYEE")){if(!Boolean.TRUE.equals(f.get("departmentEnabled"))||!((List<?>)f.getOrDefault("companyIds",List.of())).contains(Long.toString(owner.customerCompanyId())))throw new SecurityException("TARGET_NOT_ACCESSIBLE");}else{if(!p.type().equals("CUSTOMER")||!Set.of("READ","CUSTOMER_QUOTE_CONFIRMED","CUSTOMER_QUOTE_REJECTED").contains(action))throw new Problem(403,"ACCESS_DENIED");var member=call("finance-asset","/internal/asset/finance-membership",Map.of("sessionToken",Chain.cookie(r),"companyId",Long.toString(owner.customerCompanyId())),r);if(!Long.toString(owner.tenantId()).equals(member.get("tenantId"))||!Long.toString(owner.customerCompanyId()).equals(member.get("companyId"))||!Long.toString(actor.userId()).equals(member.get("userId"))||!Boolean.TRUE.equals(member.get("active"))||!Boolean.TRUE.equals(member.get("administrator")))throw new Problem(403,"ACCESS_DENIED");}}
  public void sourceRegistration(Actor a,Identity o,Submit command,QuoteDetails details){partOwners(r,a,o,command,details);for(var line:command.additions()){var s=scope(r,p,line.ticketId());if(!s.owner().equals(o)||!s.round().equals(line.roundId()))throw new Problem(409,"QUOTE_CONTEXT_CHANGED");}if(!command.rule().equals(rule(r)))throw new Problem(409,"FEE_RULE_CHANGED");}
  public void submission(Actor a,Identity o,Submit command,QuoteDetails details){requirePartShape(details);for(var line:command.additions()){var s=scope(r,p,line.ticketId());if(!s.owner().equals(o)||!s.round().equals(line.roundId()))throw new Problem(409,"FAULT_GROUP_RELATION_CHANGED");var proof=call("finance-ticket","/internal/ticket/finance-quotes/prepare",Map.of("sessionToken",Chain.cookie(r),"csrfToken",Chain.single(r,"X-CSRF-TOKEN"),"ticketId",line.ticketId().toString(),"roundId",line.roundId().toString(),"groupId",o.faultGroupId().toString(),"expectedGroupVersion",Long.toString(command.expectedVersion()),"quoteId",command.applicationId().toString(),"operationId",command.operationId().toString(),"commandDigest",JdbcFeeGroups.quoteDigest(o,command,details)),r);if(!Long.toString(o.tenantId()).equals(proof.get("tenantId"))||!Long.toString(o.customerCompanyId()).equals(proof.get("companyId"))||!line.ticketId().toString().equals(proof.get("ticketId"))||!line.roundId().toString().equals(proof.get("roundId")))throw new Problem(409,"QUOTE_CONTEXT_CHANGED");if(!o.faultGroupId().toString().equals(proof.get("groupId"))||!Long.toString(command.expectedVersion()).equals(proof.get("expectedGroupVersion"))||!command.applicationId().toString().equals(proof.get("quoteId"))||!command.operationId().toString().equals(proof.get("operationId"))||!JdbcFeeGroups.quoteDigest(o,command,details).equals(proof.get("commandDigest")))throw new Problem(503,"FINANCE_FREEZE_PROOF_MISMATCH");if(!Boolean.TRUE.equals(proof.get("quoteWritable"))||!Boolean.TRUE.equals(proof.get("executionBarrierClear"))||!"FROZEN".equals(proof.get("coordinationState"))||!Boolean.TRUE.equals(proof.get("globalFreezeAck")))throw new Problem(503,"FINANCE_EXECUTION_FREEZE_NOT_CONFIRMED");ownerUuid(proof.get("freezeId"));number(proof.get("generation"));if(!(proof.get("requiredOwners") instanceof List<?> owners)||owners.size()!=2||!new HashSet<>(owners).equals(Set.of("approval","stock")))throw new Problem(503,"FINANCE_REQUIRED_OWNER_ACKS_NOT_CONFIRMED");}}
  public void customerConfirmation(Actor a,Identity o,QuoteVersion q){current(a,o,"CUSTOMER_QUOTE_CONFIRMED");if(!p.type().equals("CUSTOMER"))throw new Problem(403,"ACCESS_DENIED");requirePartShape(q.details());if(!q.details().partBindings().isEmpty()){if(dataSource==null)throw new Problem(503,"FINANCE_PART_COVERAGE_AUTHORITY_PENDING");try(var db=dataSource.getConnection()){PartApprovalClaims.proof(db,o,q.command(),q.details());}catch(java.sql.SQLException|IllegalStateException invalid){throw new Problem(503,"FINANCE_PART_CLAIM_UNAVAILABLE");}}for(var line:q.command().additions()){var s=scope(r,p,line.ticketId());if(!s.owner().equals(o)||!s.round().equals(line.roundId()))throw new Problem(409,"QUOTE_CONTEXT_CHANGED");}}
  public void approval(Actor a,Identity o,UUID id,ApprovalFact f){throw new Problem(503,"FINANCE_APPROVAL_FACT_ADAPTER_PENDING");}
  public void verifiedCost(Actor a,Identity o,CostFact f){throw new Problem(503,"FINANCE_COST_FACT_ADAPTER_PENDING");}
  public void execution(Actor a,Identity o,UUID id){throw new Problem(503,"FINANCE_EXECUTION_BARRIER_PENDING");}
 };}
 public JdbcMoneyLedger.Authority ledger(HttpServletRequest r,Principal p){var executionDigests=new HashMap<UUID,String>();return new JdbcMoneyLedger.Authority(){
  public JdbcMoneyLedger.NotificationOwner notificationOwner(Actor a,Identity o){var f=moneyIdentity(r,false);match(p,f);if(!((List<?>)f.getOrDefault("permissions",List.of())).contains("finance.money.read")||!Boolean.TRUE.equals(f.get("departmentEnabled"))||!((List<?>)f.getOrDefault("companyIds",List.of())).contains(Long.toString(o.customerCompanyId())))throw new Problem(403,"ACCESS_DENIED");return new JdbcMoneyLedger.NotificationOwner(number(f.get("departmentId")),a.userId());}
  public void current(Actor a,Identity o,String action){
   if(p.type().equals("CUSTOMER")){if(!action.equals("READ"))throw new Problem(403,"ACCESS_DENIED");bind(r,p).current(a,o,"READ");return;}
   var f=moneyIdentity(r,!action.equals("READ"));match(p,f);if(!a.equals(p.actor())||a.tenantId()!=o.tenantId()||!p.type().equals("EMPLOYEE"))throw new Problem(403,"ACCESS_DENIED");
   String permission=switch(action){case "READ"->"finance.money.read";case "RECEIVABLE_ASSESSED"->"finance.money.assess";case "RECEIVABLE_LOWERED"->"finance.money.adjust";case "COLLECTION_RECORDED"->"finance.money.collect";case "COLLECTION_REVERSED","COLLECTION_REBOOKED"->"finance.money.reverse";case "REFUND_VOUCHER_RECORDED"->"finance.money.refund";default->throw new Problem(403,"ACCESS_DENIED");};
   if(!((List<?>)f.getOrDefault("permissions",List.of())).contains(permission))throw new Problem(403,"ACCESS_DENIED");if(!Boolean.TRUE.equals(f.get("departmentEnabled"))||!((List<?>)f.getOrDefault("companyIds",List.of())).contains(Long.toString(o.customerCompanyId())))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
  }
  public void verify(Actor a,Identity o,UUID operation,JdbcMoneyLedger.Command command){
   if(command instanceof JdbcMoneyLedger.Assess c)executionDigests.put(operation,verifyAssessment(r,a,o,operation,c));
   else if(command instanceof JdbcMoneyLedger.Collect||command instanceof JdbcMoneyLedger.RefundPaid)verifyManualVoucher(r,p,a,o,command);
   // Investigation records and a private file do not themselves prove the real outstanding funds.
   // Keep execution closed until an independent reviewer can inspect evidence and explicitly
   // attest the verified amount under the agreed audit contract.
   else if(command instanceof JdbcMoneyLedger.Reverse||command instanceof JdbcMoneyLedger.Rebook)verifyCorrection(r,p,a,o,operation,command);
   else if(command instanceof JdbcMoneyLedger.Lower c)verifyReduction(a,o,operation,c);
   else MoneyAuthorityFacts.verify(moneyFacts,a,o,operation,command,Clock.systemUTC());
  }
   public String executionReceiptsDigest(Actor a,Identity o,UUID operation,JdbcMoneyLedger.Assess command){String value=executionDigests.get(operation);if(value==null)throw new Problem(503,"FINANCE_EXECUTION_RECEIPTS_MISSING");return value;}
  public void recorded(java.sql.Connection db,Actor a,Identity o,UUID operation,JdbcMoneyLedger.Command command)throws java.sql.SQLException{ManualMoneyFacts.append(db,a,o,operation,command);}
 };}
 private void verifyReduction(Actor actor,Identity owner,UUID operation,JdbcMoneyLedger.Lower command){
  if(dataSource==null)throw new Problem(503,"FINANCE_ADJUSTMENT_APPROVAL_FACT_PENDING");
  try(var db=dataSource.getConnection()){
   var review=MoneyReviewStore.read(db,owner,command.adjustmentApprovalId(),false);var p=review.proposal();
   if(!"REDUCTION".equals(p.kind())||!review.events().containsAll(Set.of("CUSTOMER_CONFIRMED","FINANCE_APPROVED"))||!p.billId().equals(command.billId())||!p.lineId().equals(command.lineId())||p.proposedCents()!=command.newCents()||p.accountVersion()!=command.expectedVersion()||p.groupVersion()!=command.expectedGroupVersion()||!p.coverageDigest().equals(command.coverageDigest())||!p.reason().equals(command.reason()))throw new Problem(503,"FINANCE_AUTHORITY_FACT_MISMATCH");
   UUID customer=null,finance=null;try(var q=JdbcMoneyLedger.query(db,"SELECT event_kind,operation_id FROM finance_money_review_event WHERE tenant_id=? AND review_id=?",owner.tenantId(),p.reviewId().toString());var rs=q.executeQuery()){while(rs.next()){if("CUSTOMER_CONFIRMED".equals(rs.getString(1)))customer=UUID.fromString(rs.getString(2));if("FINANCE_APPROVED".equals(rs.getString(1)))finance=UUID.fromString(rs.getString(2));}}
   var binding=MoneyAuthorityFacts.binding(actor,owner,operation,command);var header=new MoneyAuthorityFacts.Header(binding,UUID.randomUUID(),Instant.now());var fact=new MoneyAuthorityFacts.Reduction(header,p.billId(),p.lineId(),p.proposedCents(),p.reviewId(),customer,finance,p.coverageDigest(),"COMPLETED");
   MoneyAuthorityFacts.verify((b,c)->new MoneyAuthorityFacts.Bundle(List.of(fact)),actor,owner,operation,command,Clock.systemUTC());
  }catch(Problem e){if(e.status==404)throw new Problem(503,"FINANCE_ADJUSTMENT_APPROVAL_FACT_PENDING");throw e;}catch(Exception e){throw new Problem(503,"FINANCE_ADJUSTMENT_APPROVAL_FACT_PENDING");}
 }
 public MoneyReviewStore.Authorizer review(HttpServletRequest r,Principal p){return (a,o,event,proposal)->{
  if(!a.equals(p.actor()))throw new Problem(403,"ACCESS_DENIED");
  if("READ".equals(event)){if("CUSTOMER".equals(p.type())){if(!"REDUCTION".equals(proposal.kind()))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");bind(r,p).current(a,o,"READ");}else ledger(r,p).current(a,o,"READ");}
  else if("CUSTOMER_CONFIRMED".equals(event)){if(!"CUSTOMER".equals(p.type()))throw new Problem(403,"ACCESS_DENIED");bind(r,p).current(a,o,"CUSTOMER_QUOTE_CONFIRMED");}
  else {if(!"EMPLOYEE".equals(p.type()))throw new Problem(403,"ACCESS_DENIED");ledger(r,p).current(a,o,"CORRECTION".equals(proposal.kind())?"COLLECTION_REVERSED":"RECEIVABLE_LOWERED");
   if("CORRECTION".equals(proposal.kind()))verifyReviewEvidence(r,a,o,proposal,false);}
 };}
 private String verifyAssessment(HttpServletRequest r,Actor actor,Identity owner,UUID operation,JdbcMoneyLedger.Assess command){
  if(dataSource==null)throw new Problem(503,"FINANCE_COMPLETED_APPROVAL_FACT_PENDING");
  try{
   var completed=TrustedFeeCompletion.obtain(dataSource,this,r,owner,command.approval().completionId());var quote=completed.quote();UUID confirmation;
   try(var db=dataSource.getConnection();var q=JdbcMoneyLedger.query(db,"SELECT operation_id FROM finance_customer_confirmation WHERE tenant_id=? AND application_id=? AND fault_group_id=?",owner.tenantId(),quote.command().applicationId().toString(),owner.faultGroupId().toString());var rows=q.executeQuery()){if(!rows.next())throw new Problem(503,"FINANCE_CUSTOMER_CONFIRMATION_MISSING");confirmation=ownerUuid(rows.getString(1));}
   var binding=MoneyAuthorityFacts.binding(actor,owner,operation,command);
   var request=new LinkedHashMap<String,Object>();request.put("sessionToken",Chain.cookie(r));request.put("groupId",owner.faultGroupId().toString());request.put("completionId",command.approval().completionId().toString());request.put("quoteId",quote.command().applicationId().toString());request.put("quoteVersion",Long.toString(quote.basis().groupVersion()));request.put("generation",completed.fact().get("generation").toString());request.put("operationId",operation.toString());request.put("authorityDigest",binding.authorityDigest());
   var expected=new LinkedHashMap<String,Object>();expected.put("tenantId",Long.toString(owner.tenantId()));expected.put("actorUserId",Long.toString(actor.userId()));expected.put("companyId",Long.toString(owner.customerCompanyId()));expected.put("groupId",owner.faultGroupId().toString());expected.put("completionId",command.approval().completionId().toString());expected.put("quoteId",quote.command().applicationId().toString());expected.put("quoteVersion",Long.toString(quote.basis().groupVersion()));expected.put("generation",completed.fact().get("generation").toString());expected.put("coverageDigest",command.approval().coverageDigest());expected.put("operationId",operation.toString());expected.put("authorityDigest",binding.authorityDigest());expected.put("state","EXECUTED");
   var execution=AssessmentOwnerEvidence.collect(quote,command.approval().executedAuthorizedLines(),request,expected,Chain.deadline(r),(edge,path,body)->call(edge,path,body,r));var receipts=execution.receipts();
   var decisions=new HashMap<String,UUID>();decisions.put("SUPERVISOR",ownerUuid(completed.fact().get("supervisorReceiptId")));if(quote.basis().financialReviewRequired())decisions.put("FINANCE",ownerUuid(completed.fact().get("financeReceiptId")));
   var header=new MoneyAuthorityFacts.Header(binding,execution.receiptId(),execution.checkedAt());var assessment=new MoneyAuthorityFacts.Assessment(header,quote,confirmation,command.approval().completionId(),quote.command().applicationId(),quote.basis().groupVersion(),command.approval().coverageDigest(),command.approval().executedAuthorizedLines(),receipts,decisions,"COMPLETED");MoneyAuthorityFacts.verify((b,c)->new MoneyAuthorityFacts.Bundle(List.of(assessment)),actor,owner,operation,command,Clock.systemUTC());return JdbcMoneyLedger.digest(receipts);
  }catch(Problem e){if(e.status==404||e.status>=500)throw new Problem(503,"FINANCE_AUTHORITY_UNAVAILABLE");throw e;}catch(Exception e){throw new Problem(503,"FINANCE_AUTHORITY_UNAVAILABLE");}
 }
 public Map<String,Object> correctionContext(HttpServletRequest r,Principal principal,Identity owner,MoneyReviewStore.Review review){
  if(!"EMPLOYEE".equals(principal.type())||!"CORRECTION".equals(review.proposal().kind())||!owner.equals(review.owner()))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
  ledger(r,principal).current(principal.actor(),owner,"READ");var proposal=review.proposal();
  verifyReviewEvidence(r,principal.actor(),owner,proposal,true);
  try{
   MoneyLedger.State state;try(var db=dataSource.getConnection();var q=JdbcMoneyLedger.query(db,"SELECT state_json FROM finance_money_account WHERE tenant_id=? AND fault_group_id=?",owner.tenantId(),owner.faultGroupId().toString());var rows=q.executeQuery()){if(!rows.next())throw new Problem(404,"TARGET_NOT_ACCESSIBLE");state=FinanceController.JSON.readValue(rows.getString(1),MoneyLedger.State.class);}
   var payment=state.payments().get(proposal.subjectId());if(payment==null||!payment.billId().equals(proposal.billId()))throw new Problem(503,"FINANCE_CORRECTION_EVIDENCE_PENDING");
   var assessment=state.assessments().get(payment.billId());if(assessment==null)throw new Problem(503,"FINANCE_CORRECTION_EVIDENCE_PENDING");
   var completion=TrustedFeeCompletion.obtain(dataSource,this,r,owner,assessment.completionId());var tickets=completion.quote().command().additions().stream().map(FeeGroup.PlanLine::ticketId).distinct().toList();
   if(tickets.size()!=1)throw new Problem(503,"FINANCE_CORRECTION_EVIDENCE_PENDING");
   ledger(r,principal).current(principal.actor(),owner,"READ");
   var voucher=payment.voucher();return Map.of("ticketId",tickets.getFirst().toString(),"originalAmountCents",Long.toString(payment.cents()),"originalEvidenceFileId",voucher.privateFileId().toString(),"originalEvidenceDigest",voucher.evidenceDigest(),"originalReference",voucher.reference(),"originalDate",voucher.date().toString(),"originalMethod",voucher.method(),"currentAccountVersion",Long.toString(state.version()),"originalReversed",state.reversed().contains(payment.id()));
  }catch(Problem e){throw e;}catch(Exception e){throw new Problem(503,"FINANCE_CORRECTION_EVIDENCE_PENDING");}
 }
 private void verifyCorrection(HttpServletRequest r,Principal p,Actor actor,Identity owner,UUID operation,JdbcMoneyLedger.Command command){
  var f=moneyIdentity(r,true);match(p,f);if(!((List<?>)f.getOrDefault("permissions",List.of())).contains("finance.money.reverse"))throw new Problem(403,"ACCESS_DENIED");if(dataSource==null)throw new Problem(503,"FINANCE_CORRECTION_EVIDENCE_PENDING");
  try(var db=dataSource.getConnection()){
   UUID reviewId=command instanceof JdbcMoneyLedger.Reverse c?c.correctionEvidenceId():((JdbcMoneyLedger.Rebook)command).correctionEvidenceId();UUID original=command instanceof JdbcMoneyLedger.Reverse c?c.originalPaymentId():((JdbcMoneyLedger.Rebook)command).originalPaymentId();String reason=command instanceof JdbcMoneyLedger.Reverse c?c.reason():((JdbcMoneyLedger.Rebook)command).reason();
   var review=MoneyReviewStore.read(db,owner,reviewId,false);var proposal=review.proposal();if(!"CORRECTION".equals(proposal.kind())||!review.events().contains("CORRECTION_VERIFIED")||!original.equals(proposal.subjectId())||proposal.accountVersion()!=command.expectedVersion()||proposal.groupVersion()!=command.expectedGroupVersion()||!reason.equals(proposal.reason()))throw new Problem(503,"FINANCE_CORRECTION_EVIDENCE_PENDING");
   long verifiedCents;
   try(var q=JdbcMoneyLedger.query(db,"SELECT a.reviewer_id,a.verified_cents,a.evidence_file_id,a.evidence_digest,a.reviewer_note,e.actor_id,e.event_kind FROM finance_correction_attestation a JOIN finance_money_review_event e ON e.tenant_id=a.tenant_id AND e.review_id=a.review_id AND e.operation_id=a.operation_id WHERE a.tenant_id=? AND a.review_id=?",owner.tenantId(),reviewId.toString());var rs=q.executeQuery()){
    if(!rs.next()||rs.getLong(1)<=0||rs.getLong(1)==review.applicantId()||rs.getLong(1)!=rs.getLong(6)||!"CORRECTION_VERIFIED".equals(rs.getString(7))||rs.getLong(2)!=proposal.proposedCents()||!proposal.evidenceFileId().toString().equals(rs.getString(3))||!proposal.evidenceDigest().equals(rs.getString(4))||rs.getString(5)==null||rs.getString(5).isBlank())throw new Problem(503,"FINANCE_CORRECTION_EVIDENCE_PENDING");
    verifiedCents=rs.getLong(2);if(rs.next())throw new Problem(503,"FINANCE_CORRECTION_EVIDENCE_PENDING");
   }
   boolean replay;try(var q=JdbcMoneyLedger.query(db,"SELECT 1 FROM finance_operation WHERE tenant_id=? AND operation_id=? AND actor_id=? AND fault_group_id=? AND action_name=?",owner.tenantId(),operation.toString(),actor.userId(),owner.faultGroupId().toString(),JdbcMoneyLedger.action(command));var rs=q.executeQuery()){replay=rs.next();}
   verifyReviewEvidence(r,actor,owner,proposal,replay);var binding=MoneyAuthorityFacts.binding(actor,owner,operation,command);var header=new MoneyAuthorityFacts.Header(binding,reviewId,Instant.now());var error=new MoneyAuthorityFacts.EntryError(header,original,reviewId,verifiedCents,"VERIFIED_ENTRY_ERROR");
   if(command instanceof JdbcMoneyLedger.Rebook c){if(!proposal.billId().equals(c.replacement().billId()))throw new Problem(409,"REBOOK_ORIGINAL_MISMATCH");verifyManualVoucher(r,p,actor,owner,c);var file=new MoneyAuthorityFacts.PrivateVoucher(header,c.replacement().id(),c.replacement().voucher(),"FINANCE_COLLECTION","AVAILABLE");MoneyAuthorityFacts.verify((b,x)->new MoneyAuthorityFacts.Bundle(List.of(error,file)),actor,owner,operation,command,Clock.systemUTC());}
   else MoneyAuthorityFacts.verify((b,x)->new MoneyAuthorityFacts.Bundle(List.of(error)),actor,owner,operation,command,Clock.systemUTC());
  }catch(Problem e){if(e.status==404)throw new Problem(503,"FINANCE_CORRECTION_EVIDENCE_PENDING");throw e;}catch(Exception e){throw new Problem(503,"FINANCE_CORRECTION_EVIDENCE_PENDING");}
 }
 private void verifyReviewEvidence(HttpServletRequest r,Actor actor,Identity owner,MoneyReviewStore.Proposal proposal,boolean replay){
  if(dataSource==null)throw new Problem(503,"FINANCE_CORRECTION_EVIDENCE_PENDING");
  try{
   MoneyLedger.State state;try(var db=dataSource.getConnection();var q=JdbcMoneyLedger.query(db,"SELECT state_json FROM finance_money_account WHERE tenant_id=? AND fault_group_id=?",owner.tenantId(),owner.faultGroupId().toString());var rs=q.executeQuery()){if(!rs.next())throw new Problem(503,"FINANCE_CORRECTION_EVIDENCE_PENDING");state=FinanceController.JSON.readValue(rs.getString(1),MoneyLedger.State.class);}
   var payment=state.payments().get(proposal.subjectId());if(payment==null||!payment.billId().equals(proposal.billId())||(!replay&&state.reversed().contains(payment.id()))||state.refunds().values().stream().anyMatch(x->x.originalPaymentId().equals(payment.id())))throw new Problem(409,"COLLECTION_NOT_EFFECTIVE");var assessment=state.assessments().get(payment.billId());if(assessment==null)throw new Problem(503,"FINANCE_CORRECTION_EVIDENCE_PENDING");
   var completion=TrustedFeeCompletion.obtain(dataSource,this,r,owner,assessment.completionId());var tickets=completion.quote().command().additions().stream().map(FeeGroup.PlanLine::ticketId).distinct().toList();if(tickets.size()!=1)throw new Problem(503,"FINANCE_CORRECTION_EVIDENCE_PENDING");String ticket=tickets.getFirst().toString();
   var fact=call("finance-ticket","/internal/ticket/finance-voucher-fact",Map.of("sessionToken",Chain.cookie(r),"ticketId",ticket,"groupId",owner.faultGroupId().toString(),"companyId",Long.toString(owner.customerCompanyId()),"businessId",proposal.reviewId().toString(),"fileId",proposal.evidenceFileId().toString(),"purpose","FINANCE_CORRECTION","evidenceDigest",proposal.evidenceDigest()),r);
   var expected=Map.of("tenantId",Long.toString(owner.tenantId()),"actorUserId",Long.toString(actor.userId()),"companyId",Long.toString(owner.customerCompanyId()),"ticketId",ticket,"groupId",owner.faultGroupId().toString(),"businessId",proposal.reviewId().toString(),"fileId",proposal.evidenceFileId().toString(),"purpose","FINANCE_CORRECTION","sha256",proposal.evidenceDigest(),"state","AVAILABLE");if(expected.entrySet().stream().anyMatch(e->!e.getValue().equals(fact.get(e.getKey()))))throw new Problem(503,"FINANCE_PRIVATE_VOUCHER_FACT_MISMATCH");nonnegative(fact.get("size"));
  }catch(Problem e){if(e.status==404||e.status>=500)throw new Problem(503,"FINANCE_CORRECTION_EVIDENCE_PENDING");throw e;}catch(Exception e){throw new Problem(503,"FINANCE_CORRECTION_EVIDENCE_PENDING");}
 }
 private void verifyManualVoucher(HttpServletRequest r,Principal p,Actor actor,Identity owner,JdbcMoneyLedger.Command command){
  UUID business;MoneyLedger.Voucher voucher;String purpose;
  if(command instanceof JdbcMoneyLedger.Collect c){business=c.payment().id();voucher=c.payment().voucher();purpose="FINANCE_COLLECTION";}
  else if(command instanceof JdbcMoneyLedger.Rebook c){business=c.replacement().id();voucher=c.replacement().voucher();purpose="FINANCE_COLLECTION";var f=moneyIdentity(r,true);if(!((List<?>)f.getOrDefault("permissions",List.of())).contains("finance.money.collect"))throw new Problem(403,"ACCESS_DENIED");}
  else{var c=(JdbcMoneyLedger.RefundPaid)command;business=c.refund().id();voucher=c.refund().voucher();purpose="FINANCE_REFUND";}
  // Original immutable quote locates the ticket; supplied public UUIDs cannot define ownership.
  if(dataSource==null)throw new Problem(503,"FINANCE_MANUAL_VOUCHER_TICKET_BINDING_PENDING");
  try{
   MoneyLedger.State state;try(var db=dataSource.getConnection();var q=JdbcMoneyLedger.query(db,"SELECT state_json FROM finance_money_account WHERE tenant_id=? AND fault_group_id=?",owner.tenantId(),owner.faultGroupId().toString());var rows=q.executeQuery()){if(!rows.next())throw new Problem(409,"BILL_NOT_FOUND");state=FinanceController.JSON.readValue(rows.getString(1),MoneyLedger.State.class);}
   UUID bill;if(command instanceof JdbcMoneyLedger.Collect c)bill=c.billId();else if(command instanceof JdbcMoneyLedger.Rebook c){var original=state.payments().get(c.originalPaymentId());if(original==null||!original.billId().equals(c.replacement().billId()))throw new Problem(409,"REBOOK_ORIGINAL_MISMATCH");bill=original.billId();}else{var original=state.payments().get(((JdbcMoneyLedger.RefundPaid)command).refund().originalPaymentId());if(original==null)throw new Problem(409,"COLLECTION_NOT_EFFECTIVE");bill=original.billId();}
   var assessment=state.assessments().get(bill);if(assessment==null)throw new Problem(409,"BILL_NOT_FOUND");
   var completion=TrustedFeeCompletion.obtain(dataSource,this,r,owner,assessment.completionId());
   var tickets=completion.quote().command().additions().stream().map(FeeGroup.PlanLine::ticketId).distinct().toList();if(tickets.size()!=1)throw new Problem(503,"FINANCE_VOUCHER_CONTEXT_MISMATCH");String ticket=tickets.getFirst().toString();
   var fact=call("finance-ticket","/internal/ticket/finance-voucher-fact",Map.of("sessionToken",Chain.cookie(r),"ticketId",ticket,"groupId",owner.faultGroupId().toString(),"companyId",Long.toString(owner.customerCompanyId()),"businessId",business.toString(),"fileId",voucher.privateFileId().toString(),"purpose",purpose,"evidenceDigest",voucher.evidenceDigest()),r);
   var expected=Map.of("tenantId",Long.toString(owner.tenantId()),"actorUserId",Long.toString(actor.userId()),"companyId",Long.toString(owner.customerCompanyId()),"ticketId",ticket,"groupId",owner.faultGroupId().toString(),"businessId",business.toString(),"fileId",voucher.privateFileId().toString(),"purpose",purpose,"sha256",voucher.evidenceDigest(),"state","AVAILABLE");
   if(expected.entrySet().stream().anyMatch(e->!e.getValue().equals(fact.get(e.getKey()))))throw new Problem(503,"FINANCE_PRIVATE_VOUCHER_FACT_MISMATCH");nonnegative(fact.get("size"));
  }catch(Problem e){if(e.status==404||e.status>=500)throw new Problem(503,"FINANCE_AUTHORITY_UNAVAILABLE");throw e;}
   catch(java.sql.SQLException e){throw new Problem(503,"FINANCE_AUTHORITY_UNAVAILABLE");}
   catch(RuntimeException e){throw new Problem(503,"FINANCE_AUTHORITY_UNAVAILABLE");}
 }
 static UUID ownerUuid(Object v){try{String value=Objects.toString(v,"");UUID id=UUID.fromString(value);if(!id.toString().equals(value))throw new IllegalArgumentException();return id;}catch(Exception e){throw new Problem(503,"INVALID_FINANCE_OWNER_FACT");}}
 static long number(Object v){try{long n=Long.parseLong(Objects.toString(v,""));if(n<=0)throw new IllegalArgumentException();return n;}catch(Exception e){throw new Problem(503,"INVALID_FINANCE_OWNER_FACT");}}
 static long nonnegative(Object v){try{long n=Long.parseLong(Objects.toString(v,""));if(n<0)throw new IllegalArgumentException();return n;}catch(Exception e){throw new Problem(503,"INVALID_FINANCE_OWNER_FACT");}}
}
