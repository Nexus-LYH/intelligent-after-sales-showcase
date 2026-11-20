package com.ias.ticket;

import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@RestController
public class Workflow {
 final Tickets t;final StaffAccess access;
 public Workflow(Tickets t,StaffAccess access){this.t=t;this.access=access;}
 static String initial(Object impact){return switch(impact.toString()){case "UNUSABLE"->"P1";case "AFFECTED"->"P2";default->"P3";};}
 static int responseMinutes(String p){return p.equals("P1")?30:p.equals("P2")?120:480;}
 static int resolutionHours(String p){return p.equals("P1")?4:p.equals("P2")?24:72;}
 Map<String,Object> fields(String action,Map<String,Object>b,String assignment){
  var required=new HashSet<>(Set.of("expectedVersion","roundId"));var optional=new HashSet<String>();
  switch(action){case "accept"->{required.addAll(Set.of("categoryCode","priority","acceptanceMessage"));optional.add("priorityChangeReason");}case "reject"->required.add("reason");case "assign"->{required.addAll(Set.of("departmentId","engineerUserId"));optional.add("note");}case "release-invalid-assignment"->required.addAll(Set.of("assignmentId","reason"));case "records"->{required.addAll(Set.of("assignmentId","body","workHours"));optional.add("visibility");}case "assignment-accept"->{}default->throw Tickets.bad();}
  var allowed=new HashSet<>(required);allowed.addAll(optional);if(!allowed.containsAll(b.keySet())||!b.keySet().containsAll(required))throw Tickets.bad();
  var data=new TreeMap<String,Object>(b);for(String k:List.of("expectedVersion","departmentId","engineerUserId"))if(data.containsKey(k)){if(!(data.get(k) instanceof String))throw Tickets.bad();data.put(k,Long.toString(Tickets.id(data.get(k))));}Tickets.uuid(data.get("roundId"));if(data.containsKey("assignmentId"))Tickets.uuid(data.get("assignmentId"));if(assignment!=null)data.put("assignmentId",Tickets.uuid(assignment));
  if(action.equals("accept")){if(!Set.of("HARDWARE","SOFTWARE","NETWORK","OTHER").contains(data.get("categoryCode"))||!Set.of("P1","P2","P3").contains(data.get("priority")))throw Tickets.bad();Tickets.text(data.get("acceptanceMessage"),1,500,true);if(data.containsKey("priorityChangeReason"))Tickets.text(data.get("priorityChangeReason"),5,500,true);}
  if(data.containsKey("reason"))Tickets.text(data.get("reason"),5,500,true);if(data.containsKey("note"))Tickets.text(data.get("note"),0,500,true);
  if(action.equals("records")){Tickets.text(data.get("body"),1,2000,true);if(!(data.get("workHours") instanceof String hours)||!hours.matches("(?:0|[1-9][0-9]?)(?:\\.[0-9]{1,2})?")||new BigDecimal(hours).compareTo(new BigDecimal("24"))>0)throw Tickets.bad();data.putIfAbsent("visibility","INTERNAL");if(!Set.of("INTERNAL","CUSTOMER_VISIBLE").contains(data.get("visibility")))throw Tickets.bad();}
  return data;
 }
 Tickets.Actor actor(HttpServletRequest r){Chain.source(r);long deadline=Chain.deadline(r);var v=Chain.identity("ticket-identity",Chain.cookie(r),"TICKET_WORK",r.getHeader("X-CSRF-TOKEN"),Chain.trace(r),deadline);Chain.fresh(v,deadline);return new Tickets.Actor(Tickets.id(v.get("tenantId")),Tickets.id(v.get("userId")),v.get("identityType").toString(),Chain.cookie(r),r.getHeader("X-CSRF-TOKEN"),Chain.trace(r),deadline,v.get("attempt").toString());}
 @PostMapping("/api/ticket/tickets/{key}/{action:accept|reject|assign|release-invalid-assignment|records}") Map<String,Object> act(@PathVariable String key,@PathVariable String action,@RequestBody Map<String,Object>b,HttpServletRequest r){return perform(key,action,fields(action,b,null),actor(r),Tickets.uuid(Chain.single(r,"Idempotency-Key")));}
 @PostMapping("/api/ticket/tickets/{key}/assignments/{assignment}/accept") Map<String,Object> acceptAssignment(@PathVariable String key,@PathVariable String assignment,@RequestBody Map<String,Object>b,HttpServletRequest r){return perform(key,"assignment-accept",fields("assignment-accept",b,assignment),actor(r),Tickets.uuid(Chain.single(r,"Idempotency-Key")));}
 Map<String,Object> perform(String key,String action,Map<String,Object>b,Tickets.Actor a,String operation){
  Tickets.uuid(key);t.accessible(key,a);String digest=Tickets.hash(new TreeMap<>(b));
  return t.tx.execute(tx->{
   t.lockActor(a.tenant(),a.user());var row=t.db.queryForMap("SELECT * FROM ticket_record WHERE id=? AND tenant_id=? FOR UPDATE",key,a.tenant());
   // Revalidate session and current scope after acquiring the ticket lock, including replays.
   Chain.fresh(Chain.identity("ticket-identity",a.token(),"TICKET_WORK",a.csrf(),a.trace(),a.deadline()),a.deadline());var f=access.facts(a);
   if(!access.company(f,row.get("company_id"))&&!access.engineer(f,row))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
   var old=t.db.queryForList("SELECT * FROM ticket_action_operation WHERE operation_id=? FOR UPDATE",operation);
   if(!old.isEmpty()){var o=old.getFirst();if(Tickets.id(o.get("tenant_id"))!=a.tenant()||Tickets.id(o.get("actor_user_id"))!=a.user()||!o.get("ticket_id").equals(key))throw new Problem(404,"OPERATION_NOT_FOUND");if(!action.equals(o.get("action"))||!digest.equals(o.get("request_digest")))throw new Problem(409,"IDEMPOTENCY_CONFLICT");return Tickets.json(o.get("result_json"));}
   if(!action.equals("records"))StockExecutionBoundary.guard(t,key);if(!row.get("round_id").equals(b.get("roundId")))throw new Problem(409,"ROUND_CONFLICT");if(Tickets.id(row.get("row_version"))!=Tickets.id(b.get("expectedVersion")))throw new Problem(409,"VERSION_CONFLICT");
   String status=row.get("status").toString(),next=status,record=null,assignment=row.get("assignment_id")==null?null:row.get("assignment_id").toString();long version=Tickets.id(row.get("row_version"));if(version==Long.MAX_VALUE)throw new Problem(409,"VERSION_CONFLICT");
   if(assignment!=null)t.db.queryForList("SELECT id FROM ticket_assignment WHERE id=? FOR UPDATE",assignment);
   String text=null,visibility="CUSTOMER_VISIBLE",type=action.toUpperCase(Locale.ROOT);BigDecimal hours=BigDecimal.ZERO;
   switch(action){
    case "accept"->{access.customerService(f,row,"ticket.accept");state(status,"PENDING_ACCEPTANCE");String priority=b.get("priority").toString();if(!priority.equals(row.get("initial_priority"))&&!b.containsKey("priorityChangeReason"))throw Tickets.bad();next="WAITING_ASSIGNMENT";text=b.get("acceptanceMessage").toString();record=UUID.randomUUID().toString();
     t.db.update("UPDATE ticket_record SET category_code=?,priority=?,response_due_at=IF(first_response_at IS NULL,TIMESTAMPADD(MINUTE,?,round_started_at),response_due_at),resolution_due_at=TIMESTAMPADD(HOUR,?,round_started_at) WHERE id=?",b.get("categoryCode"),priority,Rounds.limit(new Rounds(t).rule(row),"responseMinutes",priority),Rounds.limit(new Rounds(t).rule(row),"resolutionHours",priority),key);
     t.db.update("INSERT INTO ticket_sla_target_history(ticket_id,round_id,operation_id,old_priority,new_priority,reason,submitted_at,response_due_at,resolution_due_at,previous_response_overdue,policy_version,created_at) SELECT id,round_id,?,?,?, ?,round_started_at,response_due_at,TIMESTAMPADD(HOUR,?,round_started_at),?,policy_version,UTC_TIMESTAMP(3) FROM ticket_record WHERE id=?",operation,row.get("priority"),priority,b.get("priorityChangeReason"),Rounds.limit(new Rounds(t).rule(row),"resolutionHours",priority),Instant.parse(Tickets.utc(row.get("response_due_at"))).isBefore(row.get("first_response_at")==null?Instant.now():Completion.instant(row.get("first_response_at")))?1:0,key);
    }
    case "reject"->{access.customerService(f,row,"ticket.reject");state(status,"PENDING_ACCEPTANCE");next="REJECTED";text=b.get("reason").toString();}
    case "assign"->{access.customerService(f,row,"ticket.assign");state(status,"WAITING_ASSIGNMENT");access.department(f,b.get("departmentId"));if(!access.target(a,b.get("engineerUserId"),b.get("departmentId")))throw new Problem(409,"TARGET_INELIGIBLE");assignment=UUID.randomUUID().toString();t.db.update("INSERT INTO ticket_assignment(id,ticket_id,round_id,tenant_id,department_id,engineer_user_id,assigned_by,note,created_at) VALUES(?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3))",assignment,key,b.get("roundId"),a.tenant(),Tickets.id(b.get("departmentId")),Tickets.id(b.get("engineerUserId")),a.user(),b.get("note"));t.db.update("UPDATE ticket_record SET assignment_id=?,department_id=?,engineer_user_id=? WHERE id=?",assignment,Tickets.id(b.get("departmentId")),Tickets.id(b.get("engineerUserId")),key);next="ASSIGNED";visibility="INTERNAL";text=b.getOrDefault("note","").toString();}
    case "assignment-accept","records"->{if(!access.engineer(f,row)||!access.permission(f,action.equals("records")?"ticket.record":"ticket.assignment.accept")||action.equals("assignment-accept")&&!access.permission(f,"ticket.record"))throw new Problem(403,"ACCESS_DENIED");if(!Objects.equals(assignment,b.get("assignmentId")))throw new Problem(409,"ASSIGNMENT_CONFLICT");if(!(action.equals("records")&&status.equals("WAITING_INFORMATION")&&"IN_PROGRESS".equals(new Supplements(t,access).current(row,true).get("resume_state"))))state(status,action.equals("records")?"IN_PROGRESS":"ASSIGNED");if(action.equals("records")){text=b.get("body").toString();visibility=b.get("visibility").toString();hours=new BigDecimal(b.get("workHours").toString());}else{next="IN_PROGRESS";text="指定工程师已接单";visibility="INTERNAL";}}
    case "release-invalid-assignment"->{access.customerService(f,row,"ticket.assign");state(status,"ASSIGNED");if(!Objects.equals(assignment,b.get("assignmentId")))throw new Problem(409,"ASSIGNMENT_CONFLICT");access.department(f,row.get("department_id"));if(access.target(a,row.get("engineer_user_id"),row.get("department_id")))throw new Problem(409,"TARGET_STILL_ELIGIBLE");new Transfers(t,access).terminate(row,a,operation,"SOURCE_ASSIGNMENT_RELEASED",new Completion(t,access).now());next="WAITING_ASSIGNMENT";text=b.get("reason").toString();visibility="INTERNAL";t.db.update("UPDATE ticket_record SET assignment_id=NULL,department_id=NULL,engineer_user_id=NULL WHERE id=?",key);}
    default->throw Tickets.bad();
   }
   if(record==null)record=UUID.randomUUID().toString();t.db.update("INSERT INTO ticket_processing_record(id,ticket_id,round_id,assignment_id,operation_id,actor_user_id,type,body,work_hours,visibility,qualification_json,row_version,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3))",record,key,b.get("roundId"),assignment,operation,a.user(),type,text,hours,visibility,Chain.JSON.writeValueAsString(qualification(f)),version+1);
   if(visibility.equals("CUSTOMER_VISIBLE")&&(action.equals("accept")||action.equals("records")))new Rounds(t).first(row,a,record,new Completion(t,access).now(),operation);
   t.db.update("UPDATE ticket_record SET status=?,row_version=row_version+1 WHERE id=?",next,key);
   if(action.equals("accept")&&row.get("reporter_user_id")!=null){
    var occurred=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    var payload=new TreeMap<String,String>();payload.put("ticketId",key);payload.put("roundId",b.get("roundId").toString());payload.put("companyId",row.get("company_id").toString());payload.put("stateCode",next);payload.put("action","ACCEPT");payload.put("processingRecordId",record);
    var envelope=new LinkedHashMap<String,Object>();envelope.put("eventId",operation);envelope.put("type","ticket.accepted");envelope.put("schemaVersion",1);envelope.put("tenantId",Long.toString(a.tenant()));envelope.put("aggregateId",key);envelope.put("aggregateVersion",Long.toString(version+1));envelope.put("occurredAt",String.format(java.util.Locale.ROOT,"%s.%03dZ",occurred.toString().substring(0,19),occurred.getNano()/1_000_000));envelope.put("producer","ticket");envelope.put("correlationId",operation);envelope.put("payload",payload);
    String json=Chain.JSON.writeValueAsString(envelope),eventDigest=Tickets.hash(json);
    t.db.update("INSERT INTO ticket_customer_notification_outbox(event_id,tenant_id,event_type,aggregate_id,aggregate_version,ticket_id,round_id,company_id,recipient_user_id,state_code,envelope,event_digest,delivery_state,attempt_count,next_attempt_at,created_at) VALUES(?,?,'ticket.accepted',?,?,?,?,?,?,?,?,?,'PENDING',0,?,?)",operation,a.tenant(),key,version+1,key,b.get("roundId"),row.get("company_id"),row.get("reporter_user_id"),next,json,eventDigest,Timestamp.from(occurred),Timestamp.from(occurred));
   }
   var out=new LinkedHashMap<String,Object>();out.put("operationId",operation);out.put("action",action);out.put("state","COMPLETED");out.put("ticketId",key);out.put("status",next);out.put("roundId",b.get("roundId"));out.put("rowVersion",Long.toString(version+1));out.put("recordId",record);out.put("assignmentId",action.equals("release-invalid-assignment")?null:assignment);
   t.db.update("INSERT INTO ticket_action_operation(operation_id,tenant_id,actor_user_id,ticket_id,action,request_digest,result_json,created_at) VALUES(?,?,?,?,?,?,?,UTC_TIMESTAMP(3))",operation,a.tenant(),a.user(),key,action,digest,Chain.JSON.writeValueAsString(out));
   t.db.update("INSERT INTO ticket_audit(operation_id,tenant_id,actor_user_id,action,occurred_at) VALUES(?,?,?,?,UTC_TIMESTAMP(3))",operation,a.tenant(),a.user(),"WORKFLOW_"+type);Chain.remaining(a.deadline());return out;
  });
 }
 Map<String,Object> qualification(Map<String,Object>f){var out=new LinkedHashMap<String,Object>();for(String field:List.of("userId","tenantId","departmentId","departmentEnabled","securityVersion","securityEpoch","checkedAt"))out.put(field,f.get(field));out.put("permissions",((List<?>)f.get("permissions")).stream().filter(p->p.toString().startsWith("ticket.")).toList());return out;}
 static void state(String actual,String expected){if(!actual.equals(expected))throw new Problem(409,"INVALID_STATE");}
 @GetMapping("/api/ticket/action-operations/{operation}") Map<String,Object> operation(@PathVariable String operation,HttpServletRequest r){var a=t.actor(r,false);var rows=t.db.queryForList("SELECT * FROM ticket_action_operation WHERE operation_id=? AND tenant_id=? AND actor_user_id=?",Tickets.uuid(operation),a.tenant(),a.user());if(rows.isEmpty())throw new Problem(404,"OPERATION_NOT_FOUND");var o=rows.getFirst();if("transfer-decision".equals(o.get("action")))return new Transfers(t,access).narrowReceipt(o,a);t.accessible(o.get("ticket_id").toString(),a);return Tickets.json(o.get("result_json"));}
 @GetMapping("/api/ticket/tickets/{key}/assignment-candidates") Map<String,Object> candidates(@PathVariable String key,HttpServletRequest r){var a=t.actor(r,false);var row=t.accessible(key,a);var f=access.facts(a);access.customerService(f,row,"ticket.assign");for(var e:r.getParameterMap().entrySet())if(!Set.of("departmentId","keyword","page","size").contains(e.getKey())||e.getValue().length!=1)throw Tickets.bad();String department=Long.toString(Tickets.id(r.getParameter("departmentId")));access.department(f,department);var b=new HashMap<String,Object>();b.put("sessionToken",a.token());b.put("departmentId",department);for(String p:List.of("keyword","page","size"))if(r.getParameter(p)!=null)b.put(p,r.getParameter(p));var out=Chain.call("ticket-identity","/internal/identity/ticket-candidates",b,a.trace(),a.deadline());Chain.fresh(out,a.deadline());out.remove("checkedAt");return out;}
 @GetMapping("/api/ticket/tickets/{key}/records") Map<String,Object> records(@PathVariable String key,HttpServletRequest r){var a=t.actor(r,false);var visible=t.accessible(key,a);String selectedRound=new Rounds(t).selected(r,visible);for(var e:r.getParameterMap().entrySet())if(!Set.of("page","size","roundId").contains(e.getKey())||e.getValue().length!=1)throw Tickets.bad();long page=r.getParameter("page")==null?1:Tickets.id(r.getParameter("page")),size=r.getParameter("size")==null?20:Tickets.id(r.getParameter("size"));if(page>1000000||!Set.of(20L,50L,100L).contains(size))throw Tickets.bad();String where="ticket_id=? AND round_id=?"+(a.type().equals("CUSTOMER")?" AND visibility='CUSTOMER_VISIBLE'":"");long total=t.db.queryForObject("SELECT COUNT(*) FROM ticket_processing_record WHERE "+where,Long.class,key,selectedRound);return Map.of("items",t.db.queryForList("SELECT * FROM ticket_processing_record WHERE "+where+" ORDER BY occurred_at,id LIMIT ? OFFSET ?",key,selectedRound,size,(page-1)*size).stream().map(row->recordView(row,a.type().equals("CUSTOMER"))).toList(),"total",total,"page",page,"size",size);}
 Map<String,Object> recordView(Map<String,Object> row,boolean customer){
  var out=new LinkedHashMap<String,Object>();
  for(String[] field:new String[][]{{"recordId","id"},{"type","type"},{"body","body"},{"visibility","visibility"}}){
   out.put(field[0],row.get(field[1]));
  }
  out.put("actorUserId",row.get("actor_user_id").toString());
  out.put("occurredAt",Tickets.utc(row.get("occurred_at")));
  out.put("rowVersion",row.get("row_version").toString());
  if(!customer){
   out.put("operationId",row.get("operation_id").toString());
   out.put("roundId",row.get("round_id").toString());
   out.put("workHours",row.get("work_hours").toString());
   out.put("assignmentId",row.get("assignment_id"));
   out.put("qualificationSnapshot",Tickets.json(row.get("qualification_json")));
  }
  return out;
 }
}
