package com.ias.ticket;

import com.ias.trust.Chain;
import com.ias.trust.Problem;
import java.util.*;

/** Verify each approval against stock; actual state transitions must hold the ticket row lock. */
final class CompletionPartsProof {
 @FunctionalInterface interface Owner {Map<String,Object> query(Map<String,Object> binding);}
 private final Tickets tickets;private final Owner owner;
 CompletionPartsProof(Tickets tickets,Owner owner){this.tickets=tickets;this.owner=owner;}
 static Owner trusted(Tickets.Actor actor){return binding->{var proof=Chain.call("ticket-stock","/internal/stock/parts-cleanup-proof",binding,actor.trace(),actor.deadline());Chain.fresh(proof,actor.deadline());return proof;};}
 boolean clear(Map<String,Object> row){
  String ticket=row.get("id").toString(),tenant=row.get("tenant_id").toString();
  var refs=tickets.db.queryForList("SELECT r.round_id,r.assignment_id,s.state,s.approval_id FROM ticket_approval_reference r LEFT JOIN ticket_approval_settlement s ON s.operation_id=r.operation_id WHERE r.tenant_id=? AND r.ticket_id=?",Tickets.id(tenant),ticket);
  for(var ref:refs){
   String state=Objects.toString(ref.get("state"),"");
   if(state.isEmpty())return false;
   if(Set.of("REJECTED","WITHDRAWN").contains(state))continue;
   if(!state.equals("APPROVED")||ref.get("approval_id")==null)return false;
   var binding=Map.<String,Object>of("tenantId",tenant,"ticketId",ticket,"roundId",ref.get("round_id").toString(),"assignmentId",ref.get("assignment_id").toString(),"approvalId",ref.get("approval_id").toString());
   if(!validate(binding,owner.query(binding)))return false;
  }
  return true;
 }
 static boolean validate(Map<String,Object> binding,Map<String,Object> proof){
  if(proof==null||!proof.keySet().equals(Set.of("tenantId","ticketId","roundId","assignmentId","approvalId","complete","orderVersion","checkedAt")))throw new Problem(503,"STOCK_CLEANUP_FACT_INVALID");
  for(String key:binding.keySet())if(!binding.get(key).equals(proof.get(key)))throw new Problem(503,"STOCK_CLEANUP_SCOPE_MISMATCH");
  if(!(proof.get("complete") instanceof Boolean complete))throw new Problem(503,"STOCK_CLEANUP_FACT_INVALID");
  Object version=proof.get("orderVersion");if(complete&&(!(version instanceof String s)||!s.matches("0|[1-9][0-9]*")))throw new Problem(503,"STOCK_CLEANUP_FACT_INVALID");
  return complete;
 }
}
