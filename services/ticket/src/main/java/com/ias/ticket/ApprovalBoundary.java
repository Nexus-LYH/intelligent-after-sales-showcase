package com.ias.ticket;

import com.ias.trust.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Ticket-owned intent, not an approval decision or proof of an approval commit. */
public final class ApprovalBoundary {
 final Tickets t; final StaffAccess access;
 public ApprovalBoundary(Tickets t, StaffAccess access){this.t=t;this.access=access;}
 Map<String,Object> visible(String ticket,Tickets.Actor actor){
  var rows=t.db.queryForList("SELECT * FROM ticket_record WHERE id=? AND tenant_id=?",Tickets.uuid(ticket),actor.tenant());
  if(rows.isEmpty())throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
  return authorize(rows.getFirst(),actor,false);
 }
 Map<String,Object> authorize(Map<String,Object> row,Tickets.Actor actor,boolean submit){
  if(!"EMPLOYEE".equals(actor.type()))throw new Problem(403,"ACCESS_DENIED");
  var f=access.approvalFacts(actor,submit?"APPROVAL_SUBMIT":"APPROVAL_READ");
  if(!Boolean.TRUE.equals(f.get("enabled"))||!Long.toString(actor.tenant()).equals(f.get("tenantId"))||!Long.toString(actor.user()).equals(f.get("userId")))throw new Problem(401,"SESSION_INVALID");
  if(!access.company(f,row.get("company_id"))&&!access.engineer(f,row))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
  if(submit&&(!access.engineer(f,row)||!access.permission(f,"ticket.record")||!access.permission(f,"approval.parts.submit")))throw new Problem(403,"ACCESS_DENIED");
  return row;
 }
 Map<String,Object> view(Map<String,Object> row){
  var out=new LinkedHashMap<String,Object>();
  for(String[] pair:new String[][]{{"ticketId","id"},{"roundId","round_id"},{"status","status"},{"assignmentId","assignment_id"}})out.put(pair[0],row.get(pair[1]));
  for(String[] pair:new String[][]{{"tenantId","tenant_id"},{"companyId","company_id"},{"departmentId","department_id"},{"engineerUserId","engineer_user_id"},{"rowVersion","row_version"}})out.put(pair[0],row.get(pair[1])==null?null:row.get(pair[1]).toString());
  out.put("checkedAt",Instant.now().toString());return out;
 }
 Map<String,Object> context(String ticket,String purpose,String assignment,Tickets.Actor actor){
  var row=visible(ticket,actor);var f=access.approvalFacts(actor,"APPROVAL_READ");
  if(Set.of("SUBMIT","CATALOG").contains(purpose)){
   if(!access.permission(f,"approval.parts.submit")||!access.engineer(f,row)||!access.permission(f,"ticket.record"))throw new Problem(403,"ACCESS_DENIED");
   current(row,row.get("round_id").toString(),Tickets.uuid(assignment));
  }else if(purpose.equals("REVIEW")){
   current(row,row.get("round_id").toString(),Tickets.uuid(assignment));
   if(!access.permission(f,"approval.parts.review")||!access.company(f,row.get("company_id"))||row.get("department_id")==null||!((List<?>)f.get("approvalReviewDepartmentIds")).contains(row.get("department_id").toString()))throw new Problem(403,"ACCESS_DENIED");
  }else if(!Set.of("READ","WITHDRAW").contains(purpose))throw Tickets.bad();
  var out=view(row);out.put("userId",Long.toString(actor.user()));out.put("departmentCode",Objects.equals(row.get("department_id")==null?null:row.get("department_id").toString(),f.get("departmentId"))?f.get("departmentCode"):null);return out;
 }
 Map<String,Object> register(String ticket,String round,String assignment,String operation,String digest,Tickets.Actor actor){
  Tickets.uuid(ticket);Tickets.uuid(round);Tickets.uuid(assignment);Tickets.uuid(operation);
  if(digest==null||!digest.matches("[0-9a-f]{64}"))throw Tickets.bad();
  return t.tx.execute(tx->{
   t.lockActor(actor.tenant(),actor.user());
   var rows=t.db.queryForList("SELECT * FROM ticket_record WHERE id=? AND tenant_id=? FOR UPDATE",ticket,actor.tenant());
   if(rows.isEmpty())throw new Problem(404,"TARGET_NOT_ACCESSIBLE");var row=authorize(rows.getFirst(),actor,true);
   var old=t.db.queryForList("SELECT * FROM ticket_approval_reference WHERE operation_id=?",operation);
   if(!old.isEmpty()){
    var previous=old.getFirst();
    if(Tickets.id(previous.get("tenant_id"))!=actor.tenant()||Tickets.id(previous.get("actor_user_id"))!=actor.user()||!ticket.equals(previous.get("ticket_id")))throw new Problem(404,"OPERATION_NOT_FOUND");
    if(!round.equals(previous.get("round_id"))||!assignment.equals(previous.get("assignment_id"))||!digest.equals(previous.get("command_digest")))throw new Problem(409,"IDEMPOTENCY_CONFLICT");
    // Current submission qualification is required even for an already registered intent.
    current(row,round,assignment);return receipt(row,operation,previous.get("recorded_at"));
   }
   current(row,round,assignment);
   if(Tickets.id(row.get("row_version"))==Long.MAX_VALUE)throw new Problem(409,"VERSION_CONFLICT");
   t.db.queryForList("SELECT id FROM ticket_assignment WHERE id=?",assignment);
   Timestamp at=t.db.queryForObject("SELECT UTC_TIMESTAMP(3)",Timestamp.class);
   t.db.update("INSERT INTO ticket_approval_reference(operation_id,tenant_id,actor_user_id,ticket_id,round_id,assignment_id,command_digest,recorded_at) VALUES(?,?,?,?,?,?,?,?)",operation,actor.tenant(),actor.user(),ticket,round,assignment,digest,at);
   t.db.update("UPDATE ticket_record SET row_version=row_version+1 WHERE id=?",ticket);
   t.db.update("INSERT INTO ticket_audit(operation_id,tenant_id,actor_user_id,action,occurred_at) VALUES(?,?,?,'APPROVAL_REFERENCE_REGISTERED',?)",operation,actor.tenant(),actor.user(),at);
   row.put("row_version",Tickets.id(row.get("row_version"))+1);return receipt(row,operation,at);
  });
 }
 static void current(Map<String,Object> row,String round,String assignment){
  if(!round.equals(row.get("round_id")))throw new Problem(409,"ROUND_CONFLICT");
  if(!assignment.equals(row.get("assignment_id")))throw new Problem(409,"ASSIGNMENT_CONFLICT");
  if(!"IN_PROGRESS".equals(row.get("status"))||row.get("current_cancel_request_id")!=null)throw new Problem(409,"TICKET_NOT_APPROVABLE");
 }
 Map<String,Object> receipt(Map<String,Object> row,String operation,Object at){var result=view(row);result.put("operationId",operation);result.put("referenceState","REGISTERED_INTENT");result.put("recordedAt",Tickets.utc(at));return result;}
 Map<String,Object> query(String ticket,String operation,Tickets.Actor actor){visible(ticket,actor);var rows=t.db.queryForList("SELECT * FROM ticket_approval_reference WHERE ticket_id=? AND operation_id=? AND tenant_id=? AND actor_user_id=?",Tickets.uuid(ticket),Tickets.uuid(operation),actor.tenant(),actor.user());if(rows.isEmpty())throw new Problem(404,"OPERATION_NOT_FOUND");var row=rows.getFirst();var out=new LinkedHashMap<String,Object>();out.put("ticketId",ticket);out.put("operationId",operation);out.put("roundId",row.get("round_id"));out.put("assignmentId",row.get("assignment_id"));out.put("commandDigest",row.get("command_digest"));out.put("recordedAt",Tickets.utc(row.get("recorded_at")));var settled=t.db.queryForList("SELECT * FROM ticket_approval_settlement WHERE operation_id=?",operation);out.put("referenceState",settled.isEmpty()?"REGISTERED_INTENT":settled.getFirst().get("state"));out.put("settlement",settled.isEmpty()?null:settlementView(settled.getFirst()));return out;}
 Map<String,Object> settle(Map<String,Object> body){
  String event=Tickets.uuid(body.get("eventId")),ticket=Tickets.uuid(body.get("ticketId")),operation=Tickets.uuid(body.get("operationId")),approval=Tickets.uuid(body.get("approvalId")),decision=Tickets.uuid(body.get("decisionOperationId"));long tenant=Tickets.id(body.get("tenantId"));String digest=body.get("commandDigest") instanceof String s?s:"",state=body.get("state") instanceof String s?s:"";
  if(!digest.matches("[0-9a-f]{64}")||!Set.of("REJECTED","WITHDRAWN","APPROVED").contains(state))throw Tickets.bad();
  var prior=t.db.queryForList("SELECT * FROM ticket_approval_reference WHERE operation_id=? AND tenant_id=? AND ticket_id=?",operation,tenant,ticket);if(prior.isEmpty())throw new Problem(404,"TARGET_NOT_ACCESSIBLE");long actor=Tickets.id(prior.getFirst().get("actor_user_id"));
  return t.tx.execute(tx->{
   t.lockActor(tenant,actor);var rows=t.db.queryForList("SELECT * FROM ticket_record WHERE id=? AND tenant_id=? FOR UPDATE",ticket,tenant);if(rows.isEmpty())throw new Problem(404,"TARGET_NOT_ACCESSIBLE");var row=rows.getFirst();
   var reference=t.db.queryForMap("SELECT * FROM ticket_approval_reference WHERE operation_id=? AND tenant_id=? AND ticket_id=?",operation,tenant,ticket);if(!digest.equals(reference.get("command_digest")))throw new Problem(409,"IDEMPOTENCY_CONFLICT");
   var old=t.db.queryForList("SELECT * FROM ticket_approval_settlement WHERE operation_id=? OR event_id=? OR decision_operation_id=?",operation,event,decision);
   if(!old.isEmpty()){var fact=old.getFirst();if(old.size()!=1||!operation.equals(fact.get("operation_id"))||!event.equals(fact.get("event_id"))||!decision.equals(fact.get("decision_operation_id"))||!approval.equals(fact.get("approval_id"))||!state.equals(fact.get("state"))||!digest.equals(fact.get("command_digest")))throw new Problem(409,"IDEMPOTENCY_CONFLICT");return settlementView(fact);}
   long version=Tickets.id(row.get("row_version"));if(version==Long.MAX_VALUE)throw new Problem(409,"VERSION_CONFLICT");var at=t.db.queryForObject("SELECT UTC_TIMESTAMP(3)",Timestamp.class);
   t.db.update("INSERT INTO ticket_approval_settlement(operation_id,event_id,tenant_id,ticket_id,approval_id,decision_operation_id,command_digest,state,settled_at) VALUES(?,?,?,?,?,?,?,?,?)",operation,event,tenant,ticket,approval,decision,digest,state,at);
   t.db.update("UPDATE ticket_record SET row_version=row_version+1 WHERE id=?",ticket);t.db.update("INSERT INTO ticket_audit(operation_id,tenant_id,actor_user_id,action,occurred_at) VALUES(?,?,0,'APPROVAL_REFERENCE_SETTLED',?)",event,tenant,at);
   return settlementView(t.db.queryForMap("SELECT * FROM ticket_approval_settlement WHERE operation_id=?",operation));
  });
 }
 Map<String,Object> settlementView(Map<String,Object>fact){return Map.of("operationId",fact.get("operation_id"),"eventId",fact.get("event_id"),"approvalId",fact.get("approval_id"),"decisionOperationId",fact.get("decision_operation_id"),"state",fact.get("state"),"settledAt",Tickets.utc(fact.get("settled_at")),"blocksCompletion",fact.get("state").equals("APPROVED"));}
 long blocking(String ticket){return t.db.queryForObject("SELECT COUNT(*) FROM ticket_approval_reference r LEFT JOIN ticket_approval_settlement s ON s.operation_id=r.operation_id WHERE r.ticket_id=? AND (s.operation_id IS NULL OR s.state='APPROVED')",Long.class,ticket);}
 void requireNoExternal(String ticket){if(blocking(ticket)>0)throw new Problem(409,"UNSUPPORTED_RELATED_BUSINESS");}
 void requireCompletionClear(String ticket){if(blocking(ticket)>0)throw new Problem(409,"RELATED_APPROVAL_OR_PARTS_PENDING");}
 void annotateClosure(Map<String,Object> check,String ticket){long count=blocking(ticket);if(count>0){check.put("relatedWorkflows","APPROVAL_OR_PARTS_PENDING");check.put("approvalIntentCount",Long.toString(count));check.put("externalOwnerCleanupVerified",false);var blockers=new ArrayList<String>((List<String>)check.get("blockers"));blockers.add("RELATED_APPROVAL_OR_PARTS_PENDING");check.put("blockers",blockers);check.put("canClose",false);}}
}
