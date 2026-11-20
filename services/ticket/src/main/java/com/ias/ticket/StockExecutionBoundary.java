package com.ias.ticket;

import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.sql.Timestamp;
import java.time.Instant;

/** Durable owner permit: no timeout/automatic release of an execution whose local result is unknown. */
@RestController
public class StockExecutionBoundary {
 private final Tickets t;private final StaffAccess access;
 public StockExecutionBoundary(Tickets t,StaffAccess access){this.t=t;this.access=access;}
 static void guard(Tickets t,String ticket){FeePartPermitBarrier.guard(t,ticket);FinanceQuoteBoundary.guard(t,ticket);if(t.db.queryForObject("SELECT COUNT(*) FROM ticket_stock_execution p LEFT JOIN ticket_stock_execution_result r ON r.operation_id=p.operation_id WHERE p.ticket_id=? AND r.operation_id IS NULL",Long.class,ticket)>0)throw new Problem(409,"STOCK_EXECUTION_PENDING");}
 static void guardForPhase(Tickets t,String ticket,String phase){if(!Set.of("RETURN","RELEASE").contains(phase)){guard(t,ticket);return;}if(t.db.queryForObject("SELECT COUNT(*) FROM ticket_stock_execution p LEFT JOIN ticket_stock_execution_result r ON r.operation_id=p.operation_id WHERE p.ticket_id=? AND r.operation_id IS NULL",Long.class,ticket)>0)throw new Problem(409,"STOCK_EXECUTION_PENDING");}
 static String digest(Object v){if(!(v instanceof String s)||!s.matches("[0-9a-f]{64}"))throw Tickets.bad();return s;}
 void trusted(HttpServletRequest r){ApprovalBoundaryController.trusted(r,Set.of("stock-ticket"));}
 record Identity(Tickets.Actor actor,Map<String,Object> facts){}
 Identity identity(Map<String,Object>b,HttpServletRequest r,boolean write,boolean engineer){
  String token=b.get("sessionToken") instanceof String s?s:"",csrf=b.get("csrfToken") instanceof String s?s:null;
  if(token.isBlank()||write&&(csrf==null||csrf.isBlank()))throw Tickets.bad();long deadline=Chain.deadline(r);String trace=Chain.trace(r);
  Map<String,Object> f;
  if(engineer)f=Chain.identity("ticket-identity",token,write?"TICKET_WORK":"TICKET_READ",csrf,trace,deadline);
  else{var body=new LinkedHashMap<String,Object>();body.put("sessionToken",token);body.put("purpose",write?"STOCK_MANAGE":"STOCK_READ");if(write)body.put("csrfToken",csrf);f=Chain.call("ticket-identity","/internal/identity/domain-authorizations",body,trace,deadline);}
  Chain.fresh(f,deadline);if(!"EMPLOYEE".equals(f.get("identityType")))throw new Problem(403,"ACCESS_DENIED");
  return new Identity(new Tickets.Actor(Tickets.id(f.get("tenantId")),Tickets.id(f.get("userId")),"EMPLOYEE",token,csrf,trace,deadline,Objects.toString(f.get("attempt"),"")),f);
 }
 @PostMapping("/internal/ticket/stock-executions/prepare")
 Map<String,Object> prepare(@RequestBody Map<String,Object>b,HttpServletRequest r){
  trusted(r);if(!b.keySet().equals(Set.of("sessionToken","csrfToken","operationId","commandDigest","ticketId","roundId","assignmentId","approvalId","warehouseId","phase")))throw Tickets.bad();
  String phase=b.get("phase").toString();if(!Set.of("RESERVE","ISSUE","USE","RETURN","RELEASE").contains(phase))throw Tickets.bad();
  String op=Tickets.uuid(b.get("operationId")),ticket=Tickets.uuid(b.get("ticketId")),round=Tickets.uuid(b.get("roundId")),assignment=Tickets.uuid(b.get("assignmentId")),approval=Tickets.uuid(b.get("approvalId")),hash=digest(b.get("commandDigest"));long warehouse=Tickets.id(b.get("warehouseId"));boolean engineer=phase.equals("USE");var who=identity(b,r,true,engineer);var a=who.actor();
  return t.tx.execute(tx->{t.lockActor(a.tenant(),a.user());var rows=t.db.queryForList("SELECT * FROM ticket_record WHERE id=? AND tenant_id=? FOR UPDATE",ticket,a.tenant());if(rows.isEmpty())throw new Problem(404,"TARGET_NOT_ACCESSIBLE");var row=rows.getFirst();var fresh=identity(b,r,true,engineer);if(fresh.actor().tenant()!=a.tenant()||fresh.actor().user()!=a.user())throw new Problem(401,"SESSION_INVALID");
   if(engineer){var f=access.facts(a);if(!access.engineer(f,row)||!access.permission(f,"ticket.record"))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");}
   else if(!((List<?>)fresh.facts().getOrDefault("warehouseIds",List.of())).contains(Long.toString(warehouse)))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
   var reference=t.db.queryForList("SELECT p.round_id,p.assignment_id FROM ticket_approval_reference p JOIN ticket_approval_settlement s ON s.operation_id=p.operation_id WHERE p.tenant_id=? AND p.ticket_id=? AND s.approval_id=? AND s.state='APPROVED'",a.tenant(),ticket,approval);
   if(reference.size()!=1||!round.equals(reference.getFirst().get("round_id"))||!assignment.equals(reference.getFirst().get("assignment_id")))throw new Problem(409,"PARTS_APPROVAL_NOT_SETTLED");
   if(!Set.of("RETURN","RELEASE").contains(phase)){
    if(!round.equals(row.get("round_id"))||!assignment.equals(row.get("assignment_id"))||!"IN_PROGRESS".equals(row.get("status"))||row.get("current_cancel_request_id")!=null)throw new Problem(409,"STOCK_EXECUTION_NOT_CURRENT");
    qualify(row,a);
   }
   var prior=t.db.queryForList("SELECT * FROM ticket_stock_execution WHERE operation_id=?",op);
   if(!prior.isEmpty()){var p=prior.getFirst();if(Tickets.id(p.get("tenant_id"))!=a.tenant()||Tickets.id(p.get("actor_user_id"))!=a.user()||!ticket.equals(p.get("ticket_id")))throw new Problem(404,"OPERATION_NOT_FOUND");match(p,b);return view(p);}
   guardForPhase(t,ticket,phase);long version=Tickets.id(row.get("row_version"));if(version==Long.MAX_VALUE)throw new Problem(409,"VERSION_CONFLICT");
   t.db.update("INSERT INTO ticket_stock_execution(operation_id,tenant_id,actor_user_id,ticket_id,round_id,assignment_id,approval_id,warehouse_id,phase,command_digest,recorded_at) VALUES(?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3))",op,a.tenant(),a.user(),ticket,round,assignment,approval,warehouse,phase,hash);
   t.db.update("UPDATE ticket_record SET row_version=row_version+1 WHERE id=?",ticket);t.db.update("INSERT INTO ticket_audit(operation_id,tenant_id,actor_user_id,action,occurred_at) VALUES(?,?,?,'STOCK_EXECUTION_PREPARED',UTC_TIMESTAMP(3))",op,a.tenant(),a.user());Chain.remaining(a.deadline());return view(t.db.queryForMap("SELECT * FROM ticket_stock_execution WHERE operation_id=?",op));
  });
 }
 void qualify(Map<String,Object>row,Tickets.Actor a){var qualification=Chain.call("ticket-identity","/internal/identity/stock-engineer-qualification",Map.of("tenantId",Long.toString(a.tenant()),"userId",row.get("engineer_user_id").toString(),"departmentId",row.get("department_id").toString()),a.trace(),a.deadline());Chain.fresh(qualification,a.deadline());if(!Long.toString(a.tenant()).equals(qualification.get("tenantId"))||!row.get("engineer_user_id").toString().equals(qualification.get("userId"))||!row.get("department_id").toString().equals(qualification.get("departmentId")))throw new Problem(503,"ENGINEER_QUALIFICATION_MISMATCH");if(!Boolean.TRUE.equals(qualification.get("eligible")))throw new Problem(409,"ENGINEER_NOT_CURRENTLY_ELIGIBLE");}
 private void match(Map<String,Object>p,Map<String,Object>b){for(String[] pair:new String[][]{{"command_digest","commandDigest"},{"round_id","roundId"},{"assignment_id","assignmentId"},{"approval_id","approvalId"},{"warehouse_id","warehouseId"},{"phase","phase"}})if(!p.get(pair[0]).toString().equals(b.get(pair[1]).toString()))throw new Problem(409,"IDEMPOTENCY_CONFLICT");}
 private Map<String,Object> view(Map<String,Object>p){String op=p.get("operation_id").toString();var result=t.db.queryForList("SELECT * FROM ticket_stock_execution_result WHERE operation_id=?",op);var out=new LinkedHashMap<String,Object>();for(String[] pair:new String[][]{{"operation_id","operationId"},{"command_digest","commandDigest"},{"ticket_id","ticketId"},{"round_id","roundId"},{"assignment_id","assignmentId"},{"approval_id","approvalId"},{"warehouse_id","warehouseId"},{"phase","phase"}})out.put(pair[1],p.get(pair[0]).toString());out.put("state",result.isEmpty()?"OPEN":"COMPLETED");out.put("recordedAt",Tickets.utc(p.get("recorded_at")));out.put("checkedAt",Instant.now().toString());return out;}
 @PostMapping("/internal/ticket/stock-executions/complete")
 Map<String,Object> complete(@RequestBody Map<String,Object>b,HttpServletRequest r){trusted(r);if(!b.keySet().equals(Set.of("tenantId","operationId","commandDigest","resultDigest")))throw Tickets.bad();String op=Tickets.uuid(b.get("operationId")),hash=digest(b.get("commandDigest")),result=digest(b.get("resultDigest"));long tenant=Tickets.id(b.get("tenantId"));var refs=t.db.queryForList("SELECT * FROM ticket_stock_execution WHERE operation_id=? AND tenant_id=?",op,tenant);if(refs.isEmpty())throw new Problem(404,"OPERATION_NOT_FOUND");var ref=refs.getFirst();return t.tx.execute(tx->{t.lockActor(tenant,Tickets.id(ref.get("actor_user_id")));t.db.queryForMap("SELECT id FROM ticket_record WHERE id=? AND tenant_id=? FOR UPDATE",ref.get("ticket_id"),tenant);if(!hash.equals(ref.get("command_digest")))throw new Problem(409,"IDEMPOTENCY_CONFLICT");var old=t.db.queryForList("SELECT result_digest FROM ticket_stock_execution_result WHERE operation_id=?",op);if(!old.isEmpty()){if(!result.equals(old.getFirst().get("result_digest")))throw new Problem(409,"IDEMPOTENCY_CONFLICT");return ack(op,hash,result);}
   t.db.update("INSERT INTO ticket_stock_execution_result VALUES(?,?,UTC_TIMESTAMP(3))",op,result);String audit=UUID.nameUUIDFromBytes(("stock-complete:"+tenant+":"+op).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();t.db.update("INSERT INTO ticket_audit(operation_id,tenant_id,actor_user_id,action,occurred_at) VALUES(?,?,0,'STOCK_EXECUTION_COMPLETED',UTC_TIMESTAMP(3))",audit,tenant);t.db.update("UPDATE ticket_record SET row_version=row_version+1 WHERE id=?",ref.get("ticket_id"));return ack(op,hash,result);
  });}
 static Map<String,Object> ack(String op,String hash,String result){return Map.of("operationId",op,"commandDigest",hash,"resultDigest",result,"state","COMPLETED","checkedAt",Instant.now().toString());}
 @PostMapping("/internal/ticket/stock-executions/query")
 Map<String,Object> query(@RequestBody Map<String,Object>b,HttpServletRequest r){trusted(r);if(!Set.of("sessionToken","ticketId","operationId","phase").containsAll(b.keySet())||!b.keySet().containsAll(Set.of("sessionToken","ticketId","operationId")))throw Tickets.bad();boolean engineer="USE".equals(b.get("phase"));if(b.containsKey("phase")&&!engineer)throw Tickets.bad();String ticket=Tickets.uuid(b.get("ticketId")),op=Tickets.uuid(b.get("operationId"));var who=identity(b,r,false,engineer);var rows=t.db.queryForList("SELECT * FROM ticket_stock_execution WHERE operation_id=? AND tenant_id=? AND actor_user_id=? AND ticket_id=?",op,who.actor().tenant(),who.actor().user(),ticket);if(rows.isEmpty())throw new Problem(404,"OPERATION_NOT_FOUND");if(engineer){var row=t.accessible(ticket,who.actor());var facts=access.facts(who.actor());if(!access.engineer(facts,row)||!access.permission(facts,"ticket.record"))throw new Problem(404,"OPERATION_NOT_FOUND");}else if(!((List<?>)who.facts().getOrDefault("warehouseIds",List.of())).contains(rows.getFirst().get("warehouse_id").toString()))throw new Problem(404,"OPERATION_NOT_FOUND");return view(rows.getFirst());}
 @ExceptionHandler(Problem.class)ResponseEntity<Map<String,Object>> problem(Problem p){return ResponseEntity.status(p.status).header("Cache-Control","no-store").body(Map.of("code",p.code,"retryable",p.status==503));}
}
