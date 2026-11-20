package com.ias.finance;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.ias.finance.JdbcMoneyLedger.*;

/** Called in the quote transaction. Claims are permanent and insert-only for the runtime principal. */
final class PartApprovalClaims {
 static List<Map<String,Object>> proof(Connection db,FeeGroup.Identity owner,FeeGroup.Submit command,JdbcFeeGroups.QuoteDetails details)throws SQLException{
  var proof=new ArrayList<Map<String,Object>>();var lines=new HashMap<UUID,FeeGroup.PlanLine>();command.additions().forEach(line->lines.put(line.id(),line));
  var ids=new ArrayList<>(details.partBindings().keySet());ids.sort(Comparator.comparing(id->details.partBindings().get(id).approvalId().toString()));
  for(UUID id:ids){var binding=details.partBindings().get(id);var line=lines.get(id);if(line==null)throw new IllegalStateException("PART_CLAIM_MISSING");
   try(var q=query(db,"SELECT fault_group_id,customer_company_id,ticket_id,round_id,binding_digest FROM finance_part_approval_claim WHERE tenant_id=? AND approval_id=?",owner.tenantId(),binding.approvalId().toString());var r=q.executeQuery()){
    if(!r.next()||!owner.faultGroupId().toString().equals(r.getString(1))||owner.customerCompanyId()!=r.getLong(2)||!line.ticketId().toString().equals(r.getString(3))||!line.roundId().toString().equals(r.getString(4))||!digest(binding.canonical()).equals(r.getString(5)))throw new IllegalStateException("PART_CLAIM_MISMATCH");
   }
   proof.add(Map.of("tenantId",Long.toString(owner.tenantId()),"companyId",Long.toString(owner.customerCompanyId()),"groupId",owner.faultGroupId().toString(),"ticketId",line.ticketId().toString(),"roundId",line.roundId().toString(),"approvalId",binding.approvalId().toString(),"binding",binding.canonical()));
  }
  return List.copyOf(proof);
 }
 static void reserve(Connection db,FeeGroup.Identity owner,FeeGroup.Submit command,JdbcFeeGroups.QuoteDetails details)throws SQLException{
  var lines=new HashMap<UUID,FeeGroup.PlanLine>();command.additions().forEach(line->lines.put(line.id(),line));
  var ids=new ArrayList<>(details.partBindings().keySet());ids.sort(Comparator.comparing(id->details.partBindings().get(id).approvalId().toString()));
  var seen=new HashSet<UUID>();
  for(UUID id:ids){var binding=details.partBindings().get(id);var line=lines.get(id);if(line==null||!seen.add(binding.approvalId()))throw new IllegalStateException("PART_APPROVAL_REUSED_IN_QUOTE");
   String hash=digest(binding.canonical());
   execute(db,"INSERT IGNORE INTO finance_part_approval_claim VALUES(?,?,?,?,?,?,?,?,?)",owner.tenantId(),binding.approvalId().toString(),owner.faultGroupId().toString(),owner.customerCompanyId(),line.ticketId().toString(),line.roundId().toString(),hash,command.operationId().toString(),Timestamp.from(Instant.now()));
   try(var q=query(db,"SELECT fault_group_id,customer_company_id,ticket_id,round_id,binding_digest FROM finance_part_approval_claim WHERE tenant_id=? AND approval_id=?",owner.tenantId(),binding.approvalId().toString());var r=q.executeQuery()){
    if(!r.next())throw new SQLException("PART_CLAIM_MISSING");
    if(!owner.faultGroupId().toString().equals(r.getString(1))||owner.customerCompanyId()!=r.getLong(2)||!line.ticketId().toString().equals(r.getString(3))||!line.roundId().toString().equals(r.getString(4))||!hash.equals(r.getString(5)))throw new IllegalStateException("PART_APPROVAL_ALREADY_BOUND");
   }
  }
 }
}
