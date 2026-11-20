package com.ias.ticket;

import com.ias.trust.Problem;
import java.util.*;

/** Package-private recovery building block; only an authenticated stock owner can supply a terminal fact. */
final class FeePartRecovery {
 interface Owner { Map<String,Object> result(Map<String,Object> request); }
 static Owner trustedOwner(String trace,long deadline){return request->{if(!com.ias.trust.Chain.active()||!"ticket".equals(com.ias.trust.Chain.role()))throw new Problem(503,"TRUST_NOT_CONFIGURED");return com.ias.trust.Chain.call("ticket-stock","/internal/stock/finance-part-executions/owner-result",request,trace,deadline);};}
 private final Tickets tickets;private final Owner owner;
 FeePartRecovery(Tickets tickets,Owner owner){this.tickets=tickets;this.owner=owner;}
 String reconcile(long tenant,String operationId){
  if(tenant<=0)throw Tickets.bad();String op=Tickets.uuid(operationId);
  var rows=tickets.db.queryForList("SELECT command_digest FROM ticket_fee_part_permit WHERE tenant_id=? AND operation_id=?",tenant,op);
  if(rows.size()!=1)throw new Problem(404,"OPERATION_NOT_FOUND");String digest=StockExecutionBoundary.digest(rows.getFirst().get("command_digest"));
  var request=Map.<String,Object>of("tenantId",Long.toString(tenant),"operationId",op,"commandDigest",digest);
  // No database lock is held during the remote query. Absence is not a terminal result.
  var reply=owner.result(request);
  if(reply==null||!Objects.equals(request.get("tenantId"),reply.get("tenantId"))||!op.equals(reply.get("operationId"))||!digest.equals(reply.get("commandDigest")))throw new Problem(503,"PART_OWNER_RESULT_MISMATCH");
  if("UNKNOWN".equals(reply.get("state"))&&reply.keySet().equals(Set.of("tenantId","operationId","commandDigest","state")))return "UNKNOWN";
  String state=Objects.toString(reply.get("state"),"");
  if(!Set.of("COMMITTED","PROVEN_NO_EXECUTION").contains(state)||!reply.keySet().equals(Set.of("tenantId","operationId","commandDigest","state","resultDigest")))throw new Problem(503,"PART_OWNER_RESULT_MISMATCH");
  if(!(reply.get("resultDigest") instanceof String result)||!result.matches("[a-f0-9]{64}"))throw new Problem(503,"PART_OWNER_RESULT_MISMATCH");
  new FeePartPermitStore(tickets).recordVerifiedResult(tenant,op,digest,state,result);return state;
 }
}
