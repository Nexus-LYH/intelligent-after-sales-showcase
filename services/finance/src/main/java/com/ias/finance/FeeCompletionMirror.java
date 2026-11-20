package com.ias.finance;

import com.ias.trust.Problem;
import javax.sql.DataSource;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static com.ias.finance.JdbcMoneyLedger.*;

/** W durable pull records already-completed P facts; never a new user decision or a funds fact. */
final class FeeCompletionMirror {
 private final DataSource source;
 private final List<Connection> quarantined=Collections.synchronizedList(new ArrayList<>());
 FeeCompletionMirror(DataSource source){this.source=source;}
 static UUID operation(FeeGroup.Identity owner,UUID completion){return UUID.nameUUIDFromBytes(("finance:approval-completion:"+owner.tenantId()+":"+owner.faultGroupId()+":"+completion).getBytes(StandardCharsets.UTF_8));}
 static String immutableDigest(Map<String,Object> fact){var stable=new TreeMap<>(fact);stable.remove("checkedAt");return digest(stable);}
 Map<String,Object> record(FeeGroup.Identity owner,TrustedFeeCompletion.Verified verified)throws SQLException{
  if(!quarantined.isEmpty())throw new SQLException("UNCERTAIN_CONNECTION_REQUIRES_OPERATOR_RECONCILIATION");
  var fact=verified.fact();UUID completion=verified.completionId(),quote=verified.quote().command().applicationId(),op=operation(owner,completion);long actor=TrustedFinanceAccess.number(fact.get("actorUserId"));String hash=immutableDigest(fact);
  Connection db=source.getConnection();boolean safe=true,committing=false;
  try{
   if(!db.getAutoCommit())throw new SQLException("DEDICATED_AUTOCOMMIT_CONNECTION_REQUIRED");db.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);db.setAutoCommit(false);
   try{
    FeeGroup.State before;try(var q=query(db,"SELECT customer_company_id,state_json FROM finance_fee_group WHERE tenant_id=? AND fault_group_id=? FOR UPDATE",owner.tenantId(),owner.faultGroupId().toString());var rows=q.executeQuery()){if(!rows.next()||rows.getLong(1)!=owner.customerCompanyId())throw new Problem(404,"TARGET_NOT_ACCESSIBLE");before=JSON.readValue(rows.getString(2),FeeGroup.State.class);if(!before.owner().equals(owner))throw new Problem(503,"FINANCE_GROUP_STATE_MISMATCH");}
    try(var q=query(db,"SELECT fact_digest,receipt_json FROM finance_approval_completion_mirror WHERE tenant_id=? AND completion_id=?",owner.tenantId(),completion.toString());var rows=q.executeQuery()){
     if(rows.next()){if(!hash.equals(rows.getString(1)))throw new Problem(409,"FINANCE_COMPLETION_FACT_CONFLICT");Map<String,Object> receipt=JSON.readValue(rows.getString(2),Map.class);db.rollback();return receipt;}
    }
    if(verified.quote().details().chargesCustomer())try(var q=query(db,"SELECT quote_group_version FROM finance_customer_confirmation WHERE tenant_id=? AND application_id=? AND fault_group_id=?",owner.tenantId(),quote.toString(),owner.faultGroupId().toString());var rows=q.executeQuery()){if(!rows.next()||rows.getLong(1)!=verified.quote().basis().groupVersion())throw new Problem(503,"FINANCE_COMPLETION_CONFIRMATION_MISSING");}
    var approved=new FeeGroup.ApprovalFact(completion,true,fact.get("financeReceiptId")!=null);
    var after=FeeGroup.recordApproved(before,before.version(),quote,approved);
    var receipt=new LinkedHashMap<String,Object>();receipt.put("tenantId",Long.toString(owner.tenantId()));receipt.put("companyId",Long.toString(owner.customerCompanyId()));receipt.put("groupId",owner.faultGroupId().toString());receipt.put("completionId",completion.toString());receipt.put("quoteId",quote.toString());receipt.put("quoteVersion",Long.toString(verified.quote().basis().groupVersion()));receipt.put("recordingOperationId",op.toString());receipt.put("groupVersion",Long.toString(after.version()));receipt.put("highestApprovedCents",Long.toString(after.highestApprovedCents()));receipt.put("everRequiredFinance",after.everRequiredFinance());receipt.put("recordedAt",Instant.now().toString());
    var common=new JdbcFeeGroups.Receipt(op,"APPROVAL_RECORDED",owner,after.version(),null);
    execute(db,"INSERT INTO finance_operation VALUES(?,?,?,?,?,?,?,?)",owner.tenantId(),op.toString(),owner.faultGroupId().toString(),actor,"APPROVAL_RECORDED",hash,JSON.writeValueAsString(common),stamp());
    execute(db,"INSERT INTO finance_approval_fact VALUES(?,?,?,?,?,?,?)",owner.tenantId(),quote.toString(),completion.toString(),owner.faultGroupId().toString(),op.toString(),JSON.writeValueAsString(approved),stamp());
    var trigger=after.firstFinanceTrigger();if(execute(db,"UPDATE finance_fee_group SET row_version=?,h_cents=?,b_cents=?,state_json=?,updated_at=? WHERE tenant_id=? AND fault_group_id=? AND row_version=?",after.version(),after.highestApprovedCents(),after.bCents(),JSON.writeValueAsString(after),stamp(),owner.tenantId(),owner.faultGroupId().toString(),before.version())!=1)throw new SQLException("FINANCE_COMPLETION_VERSION_CONFLICT");
    execute(db,"INSERT INTO finance_approval_completion_mirror VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",owner.tenantId(),completion.toString(),owner.customerCompanyId(),owner.faultGroupId().toString(),verified.applicationId().toString(),quote.toString(),verified.quote().basis().groupVersion(),actor,op.toString(),hash,JSON.writeValueAsString(fact),JSON.writeValueAsString(receipt),stamp());
    execute(db,"INSERT INTO finance_audit VALUES(?,?,?,?,?,?,?)",owner.tenantId(),op.toString(),actor,"APPROVAL_RECORDED",owner.faultGroupId().toString(),after.version(),stamp());
    execute(db,"INSERT INTO finance_outbox VALUES(?,?,?,?,?,?)",owner.tenantId(),op.toString(),op.toString(),"FINANCE_APPROVAL_MIRRORED",JSON.writeValueAsString(receipt),stamp());
    committing=true;db.commit();return receipt;
   }catch(SQLException|RuntimeException failure){boolean uncertain=committing;try{db.rollback();}catch(SQLException rollback){failure.addSuppressed(rollback);uncertain=true;}if(uncertain){safe=false;try{db.abort(Runnable::run);}catch(SQLException abort){failure.addSuppressed(abort);quarantined.add(db);}throw new SQLException("OPERATION_RESULT_UNKNOWN",failure);}throw failure;}
  }finally{if(safe)db.close();}
 }
 static void highWater(Connection db,FeeGroup.Identity owner,Map<String,Object> group)throws SQLException{
  if(!Long.toString(owner.tenantId()).equals(group.get("tenantId"))||!Long.toString(owner.customerCompanyId()).equals(group.get("companyId"))||!owner.faultGroupId().toString().equals(group.get("groupId"))||!(group.get("completions") instanceof List<?> items)||items.size()>1000)throw new Problem(503,"FINANCE_APPROVAL_GROUP_FACT_MISMATCH");
  var seen=new HashSet<String>();for(Object entry:items){if(!(entry instanceof Map<?,?> map))throw new Problem(503,"FINANCE_APPROVAL_GROUP_FACT_MISMATCH");var fact=new LinkedHashMap<String,Object>();map.forEach((k,v)->fact.put(k.toString(),v));String completion=TrustedFinanceAccess.ownerUuid(fact.get("completionId")).toString();if(!seen.add(completion)||!"COMPLETED".equals(fact.get("state"))||!Long.toString(owner.tenantId()).equals(fact.get("tenantId"))||!Long.toString(owner.customerCompanyId()).equals(fact.get("companyId"))||!owner.faultGroupId().toString().equals(fact.get("groupId")))throw new Problem(503,"FINANCE_APPROVAL_GROUP_FACT_MISMATCH");
   try(var q=query(db,"SELECT fact_digest FROM finance_approval_completion_mirror WHERE tenant_id=? AND completion_id=? AND fault_group_id=? AND customer_company_id=?",owner.tenantId(),completion,owner.faultGroupId().toString(),owner.customerCompanyId());var rows=q.executeQuery()){if(!rows.next()||!immutableDigest(fact).equals(rows.getString(1)))throw new Problem(503,"FINANCE_APPROVAL_HIGH_WATER_MARK_SYNC_PENDING");}
  }
 }
}
