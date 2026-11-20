package com.ias.ticket;
import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

/** Current minimal fee scope plus the original committed freeze. Never grants ticket-body access. */
@RestController
public final class FeeApprovalContext {
 @FunctionalInterface interface Identity {Map<String,Object> read(String token,HttpServletRequest request);}
 private final Tickets t;private final Identity identity;
 @org.springframework.beans.factory.annotation.Autowired
 public FeeApprovalContext(Tickets t){this(t,(token,r)->{var f=Chain.call("ticket-identity","/internal/identity/domain-authorizations",Map.of("sessionToken",token,"purpose","FEE_READ"),Chain.trace(r),Chain.deadline(r));Chain.fresh(f,Chain.deadline(r));return f;});}
 FeeApprovalContext(Tickets t,Identity identity){this.t=t;this.identity=identity;}
 @PostMapping("/internal/ticket/fee-approval-context")
 public Map<String,Object> context(@RequestBody Map<String,Object>b,HttpServletRequest r){
  ApprovalBoundaryController.trusted(r,Set.of("approval-ticket"));
  if(!b.keySet().equals(Set.of("sessionToken","ticketId","groupId","quoteId"))||!(b.get("sessionToken") instanceof String token)||token.isBlank())throw Tickets.bad();
  return read(token,Tickets.uuid(b.get("ticketId")),Tickets.uuid(b.get("groupId")),Tickets.uuid(b.get("quoteId")),r);
 }
 Map<String,Object> read(String token,String ticket,String group,String quote,HttpServletRequest r){
  var f=identity.read(token,r);if(!Boolean.TRUE.equals(f.get("enabled"))||!"EMPLOYEE".equals(f.get("identityType"))||!Boolean.TRUE.equals(f.get("departmentEnabled"))||!((List<?>)f.getOrDefault("permissions",List.of())).contains("approval.fees.read"))throw new Problem(403,"ACCESS_DENIED");
  long tenant=Tickets.id(f.get("tenantId"));String actor=Long.toString(Tickets.id(f.get("userId")));
  var tickets=t.db.queryForList("SELECT r.* FROM ticket_record r WHERE r.id=? AND r.tenant_id=? AND EXISTS(SELECT 1 FROM ticket_create_operation o WHERE o.operation_id=r.operation_id AND o.state='COMPLETED')",ticket,tenant);
  if(tickets.isEmpty())throw new Problem(404,"TARGET_NOT_ACCESSIBLE");var row=tickets.getFirst();
  if(!((List<?>)f.getOrDefault("companyIds",List.of())).contains(row.get("company_id").toString()))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
  var fences=t.db.queryForList("SELECT f.* FROM ticket_finance_quote_fence f WHERE f.tenant_id=? AND f.ticket_id=? AND f.company_id=? AND f.group_id=? AND f.quote_id=?",tenant,ticket,row.get("company_id"),group,quote);
  if(fences.size()!=1)throw new Problem(404,"FEE_QUOTE_CONTEXT_NOT_FOUND");var fence=fences.getFirst();
  var co=t.db.queryForMap("SELECT * FROM ticket_finance_coordination WHERE freeze_id=?",fence.get("freeze_id"));
  long latest=t.db.queryForObject("SELECT MAX(generation) FROM ticket_finance_quote_fence WHERE tenant_id=? AND ticket_id=?",Long.class,tenant,ticket);
  boolean result=t.db.queryForObject("SELECT COUNT(*) FROM ticket_finance_quote_source_result WHERE freeze_id=? AND result_digest=?",Long.class,fence.get("freeze_id"),co.get("result_digest"))==1;
  long ack=t.db.queryForObject("SELECT COUNT(*) FROM ticket_finance_owner_ack WHERE freeze_id=? AND phase='RELEASED' AND owner_name IN('approval','stock')",Long.class,fence.get("freeze_id"));
  var out=new LinkedHashMap<String,Object>(FinanceFreezeCoordinator.wire(fence));
  out.put("currentActorUserId",actor);out.put("sourceActorUserId",fence.get("actor_user_id").toString());out.put("currentRoundId",row.get("round_id").toString());out.put("quoteRoundId",fence.get("round_id").toString());out.put("quoteVersion",Long.toString(Math.addExact(((Number)fence.get("expected_group_version")).longValue(),1)));
  out.put("coordinationState",co.get("state"));out.put("sourceState",co.get("source_state"));out.put("resultDigest",co.get("result_digest"));out.put("currentQuote",latest==((Number)fence.get("generation")).longValue());out.put("ownerAcksComplete","RELEASED".equals(co.get("state"))&&"COMMITTED".equals(co.get("source_state"))&&result&&ack==2);
  out.put("ticketStatus",row.get("status"));out.put("departmentId",row.get("department_id")==null?null:row.get("department_id").toString());out.put("assignmentId",row.get("assignment_id"));out.put("engineerUserId",row.get("engineer_user_id")==null?null:row.get("engineer_user_id").toString());out.put("callerIsCurrentEngineer",actor.equals(out.get("engineerUserId"))&&row.get("assignment_id")!=null);out.put("checkedAt",Instant.now().toString());return out;
 }
 @ExceptionHandler(Problem.class) org.springframework.http.ResponseEntity<Map<String,Object>> problem(Problem p){return org.springframework.http.ResponseEntity.status(p.status).header("Cache-Control","no-store").body(Map.of("code",p.code,"retryable",p.status==503));}
 @ExceptionHandler(Exception.class) org.springframework.http.ResponseEntity<Map<String,Object>> unavailable(Exception e){return problem(new Problem(503,"FEE_QUOTE_CONTEXT_UNAVAILABLE"));}
}
