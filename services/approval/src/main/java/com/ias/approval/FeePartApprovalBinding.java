package com.ias.approval;

import com.ias.trust.Problem;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

/** Verifies fee PART coverage against the approval service's own approved material snapshots. */
final class FeePartApprovalBinding {
 static void catalog(long tenant,Map<String,Object> binding,Map<String,Object> proof){
  for(var e:Map.of("tenantId",Long.toString(tenant),"warehouseId",binding.get("warehouseId"),"partId",binding.get("partId"),"unitCode",binding.get("unitCode"),"warehouseActive",true,"partActive",true).entrySet())if(!Objects.equals(e.getValue(),proof.get(e.getKey())))throw fail();
 }
 static void claims(long tenant,long company,String group,List<Map<String,Object>> lines,Object raw){
  var expected=new HashMap<String,Map<String,Object>>();
  for(var line:lines){if(!"PART".equals(line.get("kind")))continue;if(!(line.get("partBinding") instanceof Map<?,?> binding))throw fail();String approval=Objects.toString(binding.get("approvalId"),"");
   if(expected.put(approval,Map.of("tenantId",Long.toString(tenant),"companyId",Long.toString(company),"groupId",group,"ticketId",line.get("ticketId"),"roundId",line.get("roundId"),"approvalId",approval,"binding",binding))!=null)throw fail();
  }
  if(!(raw instanceof List<?> claims)||claims.size()!=expected.size())throw fail();
  for(Object value:claims){if(!(value instanceof Map<?,?> claim))throw fail();var match=expected.remove(Objects.toString(claim.get("approvalId"),""));if(match==null||!match.equals(claim))throw fail();}if(!expected.isEmpty())throw fail();
 }
 interface Lookup {Map<String,Object> approved(String tenant,String actor,String approval,String warehouse);}
 static void verify(JdbcTemplate db,long tenant,long actor,Map<String,Object> ticket,List<Map<String,Object>> lines){verify((t,a,p,w)->StockExecutionContext.approved(db,t,a,p,w),tenant,actor,ticket,lines);}
 static void verify(Lookup lookup,long tenant,long actor,Map<String,Object> ticket,List<Map<String,Object>> lines){
  for(var line:lines){if(!"PART".equals(line.get("kind")))continue;
   if(!(line.get("partBinding") instanceof Map<?,?> binding))throw fail();
   var proof=lookup.approved(Long.toString(tenant),Long.toString(actor),Objects.toString(binding.get("approvalId"),""),Objects.toString(binding.get("warehouseId"),""));
   var expected=new LinkedHashMap<String,Object>();expected.put("tenantId",Long.toString(tenant));expected.put("approvalId",binding.get("approvalId"));expected.put("approvalVersion",binding.get("approvalVersion"));expected.put("warehouseId",binding.get("warehouseId"));expected.put("ticketId",line.get("ticketId"));expected.put("roundId",line.get("roundId"));expected.put("assignmentId",ticket.get("assignmentId"));expected.put("applicantUserId",ticket.get("engineerUserId"));expected.put("chargeMode","CUSTOMER_PAID");
   for(var e:expected.entrySet())if(e.getValue()==null||!Objects.equals(e.getValue(),proof.get(e.getKey())))throw fail();
   if(!(proof.get("lines") instanceof List<?> quantities)||quantities.size()!=1||!(quantities.getFirst() instanceof Map<?,?> part)||!part.keySet().equals(Set.of("partId","quantity"))||!Objects.equals(binding.get("partId"),part.get("partId"))||!Objects.equals(binding.get("quantity"),part.get("quantity")))throw fail();
  }
 }
 private static Problem fail(){return new Problem(503,"PART_APPROVAL_OWNER_BINDING_MISMATCH");}
}
