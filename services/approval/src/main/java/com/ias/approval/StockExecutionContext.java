package com.ias.approval;

import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;
import static com.ias.approval.PartsApproval.*;

/** Stock reads only the immutable approved material scope, never customer communication. */
@RestController
public final class StockExecutionContext {
 private final JdbcTemplate db;
 public StockExecutionContext(JdbcTemplate db){this.db=db;}
 @PostMapping("/internal/approval/stock-context")
 Map<String,Object> context(@RequestBody Map<String,Object>b,HttpServletRequest r){
  if(!Chain.active()||!r.isSecure()||!"stock-approval".equals(r.getAttribute("ias.chain.edge")))throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");
  if(!Set.of("sessionToken","approvalId","warehouseId","phase").containsAll(b.keySet())||!b.keySet().containsAll(Set.of("sessionToken","approvalId","warehouseId")))throw new Problem(400,"VALIDATION_ERROR");
  String token=text(b,"sessionToken"),approval=uuid(b.get("approvalId")),warehouse=id(b.get("warehouseId"));
  if(b.containsKey("phase")&&!(b.get("phase") instanceof String))throw new Problem(400,"VALIDATION_ERROR");String phase=b.getOrDefault("phase","READ").toString();if(!Set.of("READ","RESERVE","ISSUE","USE","RETURN","ENGINEER_READ").contains(phase))throw new Problem(400,"VALIDATION_ERROR");
  boolean engineer=Set.of("USE","ENGINEER_READ").contains(phase);
  var facts=engineer?Chain.identity("approval-identity",token,"TICKET_READ",null,Chain.trace(r),Chain.deadline(r)):Chain.call("approval-identity","/internal/identity/domain-authorizations",Map.of("sessionToken",token,"purpose","STOCK_READ"),Chain.trace(r),Chain.deadline(r));Chain.fresh(facts,Chain.deadline(r));
  if(!"EMPLOYEE".equals(facts.get("identityType")))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
  if(engineer){var permissions=(List<?>)facts.getOrDefault("permissions",List.of());if(!permissions.containsAll(List.of("ticket.engineer.read","ticket.record")))throw new Problem(403,"ACCESS_DENIED");}
  else if(!Boolean.TRUE.equals(facts.get("enabled"))||!((List<?>)facts.getOrDefault("warehouseIds",List.of())).contains(warehouse))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
  var out=approved(db,facts.get("tenantId").toString(),facts.get("userId").toString(),approval,warehouse);
  if(Set.of("RESERVE","ISSUE","USE").contains(phase)&&"CUSTOMER_PAID".equals(out.get("chargeMode")))requireExecutionGeneration(db,facts.get("tenantId").toString(),approval,out.get("ticketId").toString(),out);
  if(engineer){
   if(!facts.get("userId").equals(out.get("applicantUserId")))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
   var current=Chain.call("approval-ticket","/internal/ticket/approval-context",Map.of("sessionToken",token,"ticketId",out.get("ticketId"),"purpose","READ"),Chain.trace(r),Chain.deadline(r));Chain.fresh(current,Chain.deadline(r));
   requireEngineerContext(facts,out,current);
  }return out;
 }
 static Map<String,Object> approved(JdbcTemplate db,String tenant,String actor,String approval,String warehouse){
  var rows=db.queryForList("SELECT p.snapshot,b.round_id FROM parts_application p JOIN approval_source_reference b ON b.tenant_id=p.tenant_id AND b.application_id=p.id WHERE p.tenant_id=? AND p.id=?",tenant,approval);
  if(rows.isEmpty())throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
  var row=rows.getFirst();var a=Chain.JSON.readValue(row.get("snapshot").toString(),Application.class);var s=a.submission();
  if(!tenant.equals(s.tenantId())||!approval.equals(a.id().toString()))throw new Problem(503,"APPROVAL_SOURCE_MISMATCH");
  if(a.state()!=State.APPROVED)throw new Problem(409,"PARTS_APPROVAL_REQUIRED");if(!warehouse.equals(s.warehouseId()))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
  var policy=db.queryForList("SELECT charge_mode FROM approval_execution_policy WHERE tenant_id=? AND application_id=?",tenant,approval);
  return Map.ofEntries(Map.entry("tenantId",tenant),Map.entry("userId",actor),Map.entry("approvalId",approval),Map.entry("approvalVersion",Long.toString(a.version())),Map.entry("ticketId",s.ticketId()),Map.entry("roundId",row.get("round_id").toString()),Map.entry("assignmentId",s.assignmentId()),Map.entry("applicantUserId",s.applicantId()),Map.entry("warehouseId",warehouse),Map.entry("approvedAt",a.decidedAt().toString()),Map.entry("chargeMode",policy.isEmpty()?"UNKNOWN":policy.getFirst().get("charge_mode").toString()),Map.entry("lines",List.of(Map.of("partId",s.partId(),"quantity",Integer.toString(s.quantity())))),Map.entry("checkedAt",Instant.now().toString()));
 }
 static void requireEngineerContext(Map<String,Object> facts,Map<String,Object> original,Map<String,Object> current){if(!facts.get("tenantId").equals(current.get("tenantId"))||!original.get("ticketId").equals(current.get("ticketId"))||!facts.get("userId").equals(current.get("engineerUserId")))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");}
 static void requireExecutionGeneration(JdbcTemplate db,String tenant,String approval,String ticket,Map<String,Object> approved){
  var current=db.queryForList("SELECT generation,state FROM approval_finance_generation WHERE tenant_id=? AND ticket_id=?",tenant,ticket);
  if(current.isEmpty())throw new Problem(503,"APPROVAL_EXECUTION_GENERATION_UNRESOLVED");
  if(!"OPEN".equals(current.getFirst().get("state")))throw new Problem(409,"FINANCE_GENERATION_FENCED");
  String generation=current.getFirst().get("generation").toString();
  var fees=db.queryForList("SELECT a.id,a.snapshot,b.lines_json,b.ticket_context FROM fee_approval_application a JOIN fee_approval_binding b ON b.tenant_id=a.tenant_id AND b.application_id=a.id JOIN fee_approval_completion c ON c.tenant_id=a.tenant_id AND c.application_id=a.id LEFT JOIN fee_approval_termination z ON z.tenant_id=a.tenant_id AND z.application_id=a.id WHERE a.tenant_id=? AND a.ticket_id=? AND a.state='COMPLETED' AND z.application_id IS NULL",tenant,ticket);
  boolean sameId=false;
  for(var fee:fees){
   if(approval.equals(fee.get("id").toString()))sameId=true;
   try{
    var snapshot=Chain.JSON.readValue(fee.get("snapshot").toString(),Map.class);var basis=(Map<?,?>)snapshot.get("basis");if(!generation.equals(Objects.toString(basis.get("generation"),"")))continue;
    var context=Chain.JSON.readValue(fee.get("ticket_context").toString(),Map.class);if(!Objects.equals(context.get("assignmentId"),approved.get("assignmentId"))||!Objects.equals(context.get("engineerUserId"),approved.get("applicantUserId")))continue;
    var lines=Chain.JSON.readValue(fee.get("lines_json").toString(),List.class);var quantities=(List<?>)approved.get("lines");if(quantities.size()!=1||!(quantities.getFirst() instanceof Map<?,?> quantity))throw new Problem(503,"APPROVAL_EXECUTION_GENERATION_UNRESOLVED");
    for(Object raw:lines){if(!(raw instanceof Map<?,?> line)||!"PART".equals(line.get("kind"))||!(line.get("partBinding") instanceof Map<?,?> binding))continue;
     if(approval.equals(binding.get("approvalId"))&&Objects.equals(binding.get("approvalVersion"),approved.get("approvalVersion"))&&Objects.equals(binding.get("warehouseId"),approved.get("warehouseId"))&&Objects.equals(binding.get("partId"),quantity.get("partId"))&&Objects.equals(binding.get("quantity"),quantity.get("quantity"))&&ticket.equals(line.get("ticketId"))&&Objects.equals(line.get("roundId"),approved.get("roundId")))return;
    }
   }catch(Problem p){throw p;}catch(Exception e){throw new Problem(503,"APPROVAL_EXECUTION_GENERATION_UNRESOLVED");}
  }
  // A same-ID fee record is never a substitute for its immutable, exact part binding.
  if(sameId)throw new Problem(409,"APPROVAL_EXECUTION_GENERATION_STALE");
  requireIntentGeneration(db,tenant,approval,generation);
 }
 static void requireExecutionGeneration(JdbcTemplate db,String tenant,String approval,String ticket){
  var current=db.queryForList("SELECT generation,state FROM approval_finance_generation WHERE tenant_id=? AND ticket_id=?",tenant,ticket);
  if(current.isEmpty())throw new Problem(503,"APPROVAL_EXECUTION_GENERATION_UNRESOLVED");
  var row=current.getFirst();if(!"OPEN".equals(row.get("state")))throw new Problem(409,"FINANCE_GENERATION_FENCED");String generation=row.get("generation").toString();
  var fee=db.queryForList("SELECT state,snapshot FROM fee_approval_application WHERE tenant_id=? AND id=? AND ticket_id=?",tenant,approval,ticket);
  if(!fee.isEmpty()){
   var f=fee.getFirst();if(!"COMPLETED".equals(f.get("state")))throw new Problem(503,"APPROVAL_EXECUTION_GENERATION_UNRESOLVED");
   try{var snap=Chain.JSON.readValue(f.get("snapshot").toString(),Map.class);var basis=(Map<?,?>)snap.get("basis");var feeGeneration=Objects.toString(basis.get("generation"),"");if(!generation.equals(feeGeneration))throw new Problem(409,"APPROVAL_EXECUTION_GENERATION_STALE");return;}catch(Problem problem){throw problem;}catch(Exception malformed){throw new Problem(503,"APPROVAL_EXECUTION_GENERATION_UNRESOLVED");}
  }
  requireIntentGeneration(db,tenant,approval,generation);
 }
 private static void requireIntentGeneration(JdbcTemplate db,String tenant,String approval,String generation){
  var intents=db.queryForList("SELECT i.generation,o.action FROM approval_finance_write_intent i JOIN approval_operation o ON o.tenant_id=i.tenant_id AND o.actor_id=i.actor_id AND o.operation_id=i.operation_id WHERE o.tenant_id=? AND o.target_id=? AND o.receipt IS NOT NULL AND i.charge_mode='CUSTOMER_PAID' AND i.action IN ('SUBMIT','RESUBMIT','DECIDE')",tenant,approval);
  boolean submitted=false,approved=false;for(var intent:intents){if(!generation.equals(intent.get("generation").toString()))throw new Problem(409,"APPROVAL_EXECUTION_GENERATION_STALE");if("DECIDE".equals(intent.get("action")))approved=true;else submitted=true;}
  if(!submitted||!approved)throw new Problem(503,"APPROVAL_EXECUTION_GENERATION_UNRESOLVED");
 }
 static String text(Map<String,Object>b,String k){Object v=b.get(k);if(!(v instanceof String s)||s.isBlank())throw new Problem(400,"VALIDATION_ERROR");return s;}
 static String uuid(Object v){try{String s=(String)v;if(!UUID.fromString(s).toString().equals(s))throw new Exception();return s;}catch(Exception e){throw new Problem(400,"VALIDATION_ERROR");}}
 static String id(Object v){if(!(v instanceof String s)||!s.matches("[1-9][0-9]{0,18}"))throw new Problem(400,"VALIDATION_ERROR");try{Long.parseLong(s);return s;}catch(Exception e){throw new Problem(400,"VALIDATION_ERROR");}}
 @ExceptionHandler(Problem.class)org.springframework.http.ResponseEntity<Map<String,Object>> problem(Problem p){return org.springframework.http.ResponseEntity.status(p.status).header("Cache-Control","no-store").body(Map.of("code",p.code,"retryable",p.status==503));}
}
