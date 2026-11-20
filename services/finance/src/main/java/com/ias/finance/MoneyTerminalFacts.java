package com.ias.finance;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.ias.finance.JdbcMoneyLedger.*;

/** Immutable verified money result and notification projection. */
final class MoneyTerminalFacts {
 private MoneyTerminalFacts(){}
 record Terminal(UUID business,UUID bill,UUID completion,UUID original,long cents,UUID correction,MoneyLedger.Voucher voucher,String eventType){}
 static Map<String,Object> append(Connection db,JdbcFeeGroups.Actor actor,FeeGroup.Identity owner,UUID operation,Command command,MoneyLedger.State before,MoneyLedger.State after,NotificationOwner recipient)throws SQLException{
  Terminal t=terminal(command,before);if(t==null)return null;
  String authority=MoneyAuthorityFacts.binding(actor,owner,operation,command).authorityDigest();
  var payload=new TreeMap<String,Object>();payload.put("schemaVersion","1");payload.put("operationId",operation.toString());payload.put("tenantId",Long.toString(owner.tenantId()));payload.put("companyId",Long.toString(owner.customerCompanyId()));payload.put("groupId",owner.faultGroupId().toString());payload.put("billId",t.bill().toString());payload.put("completionId",t.completion().toString());payload.put("accountVersion",Long.toString(after.version()));payload.put("action",action(command));payload.put("businessId",t.business().toString());payload.put("originalPaymentId",t.original()==null?null:t.original().toString());payload.put("amountCents",Long.toString(t.cents()));payload.put("authorityDigest",authority);payload.put("occurredAt",Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString());
  String receipts;try(var q=query(db,"SELECT execution_receipts_digest FROM finance_receivable_execution_fact WHERE tenant_id=? AND bill_id=? AND fault_group_id=? AND completion_id=?",owner.tenantId(),t.bill().toString(),owner.faultGroupId().toString(),t.completion().toString());var rows=q.executeQuery()){if(!rows.next())throw new IllegalStateException("EXECUTION_RECEIPTS_NOT_BOUND");receipts=rows.getString(1);}payload.put("executionReceiptsDigest",receipts);
  String json=JSON.writeValueAsString(payload);
  execute(db,"INSERT INTO finance_money_terminal_fact(tenant_id,operation_id,fault_group_id,customer_company_id,actor_id,account_version,action_name,business_id,bill_id,completion_id,original_payment_id,amount_cents,authority_digest,execution_receipts_digest,correction_evidence_id,private_file_id,evidence_digest,terminal_event_type,terminal_event_version,terminal_payload_json,recorded_at,department_id,applicant_user_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",owner.tenantId(),operation.toString(),owner.faultGroupId().toString(),owner.customerCompanyId(),actor.userId(),after.version(),action(command),t.business().toString(),t.bill().toString(),t.completion().toString(),t.original()==null?null:t.original().toString(),t.cents(),authority,receipts,t.correction()==null?null:t.correction().toString(),t.voucher()==null?null:t.voucher().privateFileId().toString(),t.voucher()==null?null:t.voucher().evidenceDigest(),t.eventType(),"1",json,stamp(),recipient.departmentId(),recipient.applicantUserId());
  return payload;
 }
 private static Terminal terminal(Command command,MoneyLedger.State before){
  UUID business,bill,original=null,correction=null;long cents;MoneyLedger.Voucher voucher=null;String event;
  if(command instanceof Collect c){business=c.payment().id();bill=c.billId();original=c.payment().correctionOf();cents=c.payment().cents();voucher=c.payment().voucher();event="FINANCE_COLLECTION_FINALIZED";}
  else if(command instanceof RefundPaid c){var p=before.payments().get(c.refund().originalPaymentId());if(p==null)throw new IllegalStateException("COLLECTION_NOT_EFFECTIVE");business=c.refund().id();bill=p.billId();original=p.id();cents=c.refund().cents();voucher=c.refund().voucher();event="FINANCE_REFUND_FINALIZED";}
  else if(command instanceof Reverse c){var p=before.payments().get(c.originalPaymentId());if(p==null)throw new IllegalStateException("COLLECTION_NOT_EFFECTIVE");business=UUID.nameUUIDFromBytes(("reverse:"+c.originalPaymentId()+":"+c.correctionEvidenceId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));bill=p.billId();original=p.id();cents=0;correction=c.correctionEvidenceId();event="FINANCE_COLLECTION_FINALIZED";}
  else if(command instanceof Rebook c){business=c.replacement().id();bill=c.replacement().billId();original=c.originalPaymentId();cents=c.replacement().cents();correction=c.correctionEvidenceId();voucher=c.replacement().voucher();event="FINANCE_COLLECTION_FINALIZED";}
  else return null;
  var a=before.assessments().get(bill);if(a==null)throw new IllegalStateException("BILL_NOT_FOUND");return new Terminal(business,bill,a.completionId(),original,cents,correction,voucher,event);
 }
}
