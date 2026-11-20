package com.ias.finance;

import com.ias.trust.Problem;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.ias.finance.JdbcMoneyLedger.*;
import static com.ias.finance.JdbcFeeGroups.Actor;

/** Immutable review steps. Authorizer must recheck the current user and evidence on every call. */
final class MoneyReviewStore {
 record Proposal(UUID reviewId,String kind,long accountVersion,long groupVersion,UUID subjectId,UUID billId,
                 UUID lineId,long proposedCents,String coverageDigest,UUID evidenceFileId,String evidenceDigest,String reason) {}
 record Review(FeeGroup.Identity owner,Proposal proposal,long applicantId,Set<String> events) {}
 record Verification(long verifiedCents,UUID evidenceFileId,String evidenceDigest,String reviewerNote) {}
 interface Authorizer { void authorize(Actor actor,FeeGroup.Identity owner,String event,Proposal proposal); }
 private final DataSource source; private final Authorizer authorizer;
 private static final List<Connection> QUARANTINED=Collections.synchronizedList(new ArrayList<>());
 MoneyReviewStore(DataSource source,Authorizer authorizer){this.source=Objects.requireNonNull(source);this.authorizer=Objects.requireNonNull(authorizer);}

 void submit(Actor actor,FeeGroup.Identity owner,UUID operation,Proposal p)throws SQLException{
  requireOwner(actor,owner);validate(p);authorizer.authorize(actor,owner,"SUBMITTED",p);
  run(actor,owner,operation,p,"SUBMITTED");
 }
 void decide(Actor actor,FeeGroup.Identity owner,UUID operation,UUID reviewId,String event)throws SQLException{
  requireOwner(actor,owner);if(!Set.of("CUSTOMER_CONFIRMED","FINANCE_APPROVED","CORRECTION_VERIFIED").contains(event))throw new IllegalArgumentException("INVALID_REVIEW_EVENT");
  if("CORRECTION_VERIFIED".equals(event))throw new Problem(503,"CORRECTION_ATTESTATION_REQUIRED");
  Review review;try(var db=source.getConnection()){review=read(db,owner,reviewId,false);}
  authorizer.authorize(actor,owner,event,review.proposal());run(actor,owner,operation,review.proposal(),event);
 }
 void verifyCorrection(Actor actor,FeeGroup.Identity owner,UUID operation,UUID reviewId,Verification verification)throws SQLException{
  requireOwner(actor,owner);
  if(verification==null||verification.verifiedCents()<0||verification.evidenceFileId()==null||verification.evidenceDigest()==null||!verification.evidenceDigest().matches("[a-f0-9]{64}")||verification.reviewerNote()==null||verification.reviewerNote().isBlank()||verification.reviewerNote().length()>500)throw new IllegalArgumentException("INVALID_CORRECTION_ATTESTATION");
  Review review;try(var db=source.getConnection()){review=read(db,owner,reviewId,false);}
  authorizer.authorize(actor,owner,"CORRECTION_VERIFIED",review.proposal());run(actor,owner,operation,review.proposal(),"CORRECTION_VERIFIED",verification);
 }
 private void run(Actor actor,FeeGroup.Identity owner,UUID operation,Proposal p,String event)throws SQLException{
  run(actor,owner,operation,p,event,null);
 }
 private void run(Actor actor,FeeGroup.Identity owner,UUID operation,Proposal p,String event,Verification verification)throws SQLException{
  Objects.requireNonNull(operation);String request=digest(verification==null?List.of(owner,actor,operation,event,p):List.of(owner,actor,operation,event,p,verification));
  if(!QUARANTINED.isEmpty())throw new SQLException("UNCERTAIN_CONNECTION_REQUIRES_OPERATOR_RECONCILIATION");
  Connection db=source.getConnection();boolean safe=true,committing=false;
  try{
   if(!db.getAutoCommit())throw new SQLException("DEDICATED_AUTOCOMMIT_CONNECTION_REQUIRED");db.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);db.setAutoCommit(false);
   try{
    long groupVersion=lockGroup(db,owner);authorizer.authorize(actor,owner,event,p);
    try(var q=query(db,"SELECT review_id,actor_id,event_kind,request_digest FROM finance_money_review_event WHERE tenant_id=? AND operation_id=?",owner.tenantId(),operation.toString());var rs=q.executeQuery()){
     if(rs.next()){if(!p.reviewId().toString().equals(rs.getString(1))||rs.getLong(2)!=actor.userId()||!event.equals(rs.getString(3))||!request.equals(rs.getString(4)))throw new IllegalStateException("OPERATION_CONFLICT");committing=true;db.commit();return;}
    }
    try(var q=query(db,"SELECT 1 FROM finance_operation WHERE tenant_id=? AND operation_id=?",owner.tenantId(),operation.toString());var rs=q.executeQuery()){if(rs.next())throw new IllegalStateException("OPERATION_CONFLICT");}
    JdbcQuoteSources.reservedForQuote(db,owner.tenantId(),operation);
    if(groupVersion!=p.groupVersion())throw new IllegalStateException("VERSION_CONFLICT");
    MoneyLedger.State state=moneyState(db,owner);if(state.version()!=p.accountVersion())throw new IllegalStateException("LEDGER_VERSION_CONFLICT");
    if("SUBMITTED".equals(event)){
     if("REDUCTION".equals(p.kind())){var assessment=state.assessments().get(p.billId());if(assessment==null||!assessment.coverageDigest().equals(p.coverageDigest())||!p.subjectId().equals(p.lineId()))throw new IllegalStateException("REVIEW_BASIS_MISMATCH");MoneyLedger.lower(state,p.accountVersion(),p.billId(),p.lineId(),p.proposedCents());}
     else {var payment=state.payments().get(p.subjectId());if(payment==null||!p.billId().equals(payment.billId())||state.reversed().contains(payment.id())||state.refunds().values().stream().anyMatch(x->x.originalPaymentId().equals(payment.id())))throw new IllegalStateException("COLLECTION_NOT_EFFECTIVE");}
     execute(db,"INSERT INTO finance_money_review VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",owner.tenantId(),p.reviewId().toString(),owner.faultGroupId().toString(),owner.customerCompanyId(),p.kind(),actor.userId(),p.accountVersion(),p.groupVersion(),p.subjectId().toString(),p.billId().toString(),p.lineId()==null?null:p.lineId().toString(),p.proposedCents(),p.coverageDigest(),p.evidenceFileId()==null?null:p.evidenceFileId().toString(),p.evidenceDigest(),p.reason(),stamp());
    }else{
     // The group row serializes review transitions and ledger changes. Review facts remain insert-only.
     Review current=read(db,owner,p.reviewId(),false);if(!current.proposal().equals(p))throw new IllegalStateException("REVIEW_CHANGED");
     if(actor.userId()==current.applicantId()&& !"CUSTOMER_CONFIRMED".equals(event))throw new Problem(403,"INDEPENDENT_REVIEW_REQUIRED");
     if(current.events().contains(event))throw new IllegalStateException("REVIEW_ALREADY_DECIDED");
     if("CUSTOMER_CONFIRMED".equals(event)&&!"REDUCTION".equals(p.kind()) || "FINANCE_APPROVED".equals(event)&&(!"REDUCTION".equals(p.kind())||!current.events().contains("CUSTOMER_CONFIRMED")) || "CORRECTION_VERIFIED".equals(event)&&!"CORRECTION".equals(p.kind()))throw new IllegalStateException("INVALID_REVIEW_TRANSITION");
     if("CORRECTION_VERIFIED".equals(event)&&(verification==null||verification.verifiedCents()!=p.proposedCents()||!verification.evidenceFileId().equals(p.evidenceFileId())||!verification.evidenceDigest().equals(p.evidenceDigest())))throw new IllegalStateException("CORRECTION_ATTESTATION_MISMATCH");
    }
    var operationReceipt=new JdbcFeeGroups.Receipt(operation,event,owner,groupVersion,null);
    execute(db,"INSERT INTO finance_operation VALUES(?,?,?,?,?,?,?,?)",owner.tenantId(),operation.toString(),owner.faultGroupId().toString(),actor.userId(),event,request,JSON.writeValueAsString(operationReceipt),stamp());
    execute(db,"INSERT INTO finance_money_review_event VALUES(?,?,?,?,?,?,?)",owner.tenantId(),p.reviewId().toString(),operation.toString(),event,actor.userId(),request,stamp());
    if(verification!=null)execute(db,"INSERT INTO finance_correction_attestation VALUES(?,?,?,?,?,?,?,?,?)",owner.tenantId(),p.reviewId().toString(),operation.toString(),actor.userId(),verification.verifiedCents(),verification.evidenceFileId().toString(),verification.evidenceDigest(),verification.reviewerNote(),stamp());
    committing=true;db.commit();
   }catch(SQLException|RuntimeException ex){boolean uncertain=committing;try{db.rollback();}catch(SQLException rollback){ex.addSuppressed(rollback);uncertain=true;}if(uncertain){safe=false;try{db.abort(Runnable::run);}catch(SQLException abort){ex.addSuppressed(abort);QUARANTINED.add(db);}throw new SQLException("OPERATION_RESULT_UNKNOWN",ex);}throw ex;}
  }finally{if(safe)db.close();}
 }
 private static void requireOwner(Actor a,FeeGroup.Identity o){if(a.tenantId()!=o.tenantId())throw new Problem(404,"TARGET_NOT_ACCESSIBLE");}
 private static void validate(Proposal p){Objects.requireNonNull(p);Objects.requireNonNull(p.reviewId());Objects.requireNonNull(p.subjectId());Objects.requireNonNull(p.billId());if(p.accountVersion()<0||p.groupVersion()<0||p.proposedCents()<0||p.reason()==null||p.reason().isBlank()||p.reason().length()>500)throw new IllegalArgumentException("INVALID_REVIEW");if("REDUCTION".equals(p.kind())){if(p.lineId()==null||p.coverageDigest()==null||!p.coverageDigest().matches("[a-f0-9]{64}"))throw new IllegalArgumentException("INVALID_REVIEW");}else if("CORRECTION".equals(p.kind())){if(p.lineId()!=null||p.evidenceFileId()==null||p.evidenceDigest()==null||!p.evidenceDigest().matches("[a-f0-9]{64}"))throw new IllegalArgumentException("INVALID_REVIEW");}else throw new IllegalArgumentException("INVALID_REVIEW");}
 private static long lockGroup(Connection db,FeeGroup.Identity owner)throws SQLException{try(var q=query(db,"SELECT customer_company_id,row_version FROM finance_fee_group WHERE tenant_id=? AND fault_group_id=? FOR UPDATE",owner.tenantId(),owner.faultGroupId().toString());var r=q.executeQuery()){if(!r.next()||r.getLong(1)!=owner.customerCompanyId())throw new Problem(404,"TARGET_NOT_ACCESSIBLE");return r.getLong(2);}}
 private static MoneyLedger.State moneyState(Connection db,FeeGroup.Identity owner)throws SQLException{try(var q=query(db,"SELECT row_version,state_json FROM finance_money_account WHERE tenant_id=? AND fault_group_id=?",owner.tenantId(),owner.faultGroupId().toString());var r=q.executeQuery()){if(!r.next())throw new IllegalStateException("BILL_NOT_FOUND");var state=MoneyLedger.checked(JSON.readValue(r.getString(2),MoneyLedger.State.class));if(r.getLong(1)!=state.version())throw new IllegalStateException("LEDGER_VERSION_CONFLICT");return state;}}
 static Review read(Connection db,FeeGroup.Identity owner,UUID review,boolean lock)throws SQLException{
  try(var q=query(db,"SELECT kind,applicant_id,account_version,group_version,subject_id,bill_id,line_id,proposed_cents,coverage_digest,evidence_file_id,evidence_digest,reason FROM finance_money_review WHERE tenant_id=? AND fault_group_id=? AND customer_company_id=? AND review_id=?"+(lock?" FOR UPDATE":""),owner.tenantId(),owner.faultGroupId().toString(),owner.customerCompanyId(),review.toString());var r=q.executeQuery()){
   if(!r.next())throw new Problem(404,"TARGET_NOT_ACCESSIBLE");String line=r.getString(7),file=r.getString(10);var p=new Proposal(review,r.getString(1),r.getLong(3),r.getLong(4),UUID.fromString(r.getString(5)),UUID.fromString(r.getString(6)),line==null?null:UUID.fromString(line),r.getLong(8),r.getString(9),file==null?null:UUID.fromString(file),r.getString(11),r.getString(12));long applicant=r.getLong(2);var events=new HashSet<String>();try(var e=query(db,"SELECT event_kind FROM finance_money_review_event WHERE tenant_id=? AND review_id=?",owner.tenantId(),review.toString());var rows=e.executeQuery()){while(rows.next())events.add(rows.getString(1));}return new Review(owner,p,applicant,Set.copyOf(events));
  }
 }
}
