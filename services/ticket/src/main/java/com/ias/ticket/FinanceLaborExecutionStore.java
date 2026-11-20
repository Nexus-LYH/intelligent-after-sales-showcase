package com.ias.ticket;

import com.ias.trust.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static com.ias.ticket.FinanceLaborExecution.*;

/** Append-only execution evidence, serialized with quote prepare through ticket_record. */
public final class FinanceLaborExecutionStore {
 public interface Authority {
  Actor current(boolean write);
  Map<String,Object> completion(long tenant,long company,String group,String completion);
 }
 private final DataSource source;private final Authority authority;
 private final List<Connection> quarantined=Collections.synchronizedList(new ArrayList<>());
 public FinanceLaborExecutionStore(DataSource source,Authority authority){this.source=Objects.requireNonNull(source);this.authority=Objects.requireNonNull(authority);}
 private <T>T transaction(Function<JdbcTemplate,T> work){return transaction(false,work);}
 private <T>T transaction(boolean write,Function<JdbcTemplate,T> work){
  if(!quarantined.isEmpty())throw new Problem(503,"FEE_EXECUTION_CONNECTION_QUARANTINED");
  Connection c=null;boolean begun=false,unknown=false;
  try{c=source.getConnection();if(!c.getAutoCommit())throw new SQLException("DEDICATED_AUTOCOMMIT_CONNECTION_REQUIRED");c.setTransactionIsolation(write?Connection.TRANSACTION_READ_COMMITTED:Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);begun=true;
   T result=work.apply(new JdbcTemplate(new SingleConnectionDataSource(c,true)));unknown=true;c.commit();unknown=false;return result;
  }catch(Exception failure){if(c!=null&&begun)try{c.rollback();}catch(Exception rollback){failure.addSuppressed(rollback);unknown=true;}
   if(unknown&&c!=null)try{c.abort(Runnable::run);}catch(Exception abort){failure.addSuppressed(abort);quarantined.add(c);}
   if(!unknown&&failure instanceof Problem p)throw p;
   if(!unknown&&failure instanceof org.springframework.dao.DuplicateKeyException)throw new Problem(409,"FEE_EXECUTION_CONFLICT");
   var p=new Problem(503,"OPERATION_RESULT_UNKNOWN");p.initCause(failure);throw p;
  }finally{if(c!=null&&!quarantined.contains(c))try{c.close();}catch(SQLException ignored){}}
 }
 private Actor current(Actor original,boolean write){Actor now=authority.current(write);if(now.tenant()!=original.tenant()||now.user()!=original.user())throw new Problem(401,"SESSION_INVALID");return now;}
 private Map<String,Object> ticket(JdbcTemplate db,String ticket,Actor actor,boolean lock,boolean engineer){
  var rows=db.queryForList("SELECT r.* FROM ticket_record r WHERE r.id=? AND r.tenant_id=? AND EXISTS(SELECT 1 FROM ticket_create_operation o WHERE o.operation_id=r.operation_id AND o.state='COMPLETED')"+(lock?" FOR UPDATE":""),ticket,actor.tenant());
  if(rows.size()!=1)throw new Problem(404,"TARGET_NOT_ACCESSIBLE");var row=rows.getFirst();long company=((Number)row.get("company_id")).longValue();
  if(!actor.companies().contains(company)||actor.department()==null)throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
  if(engineer&&(row.get("engineer_user_id")==null||actor.user()!=((Number)row.get("engineer_user_id")).longValue()||!Objects.equals(actor.department(),row.get("department_id")==null?null:((Number)row.get("department_id")).longValue())||row.get("assignment_id")==null))throw new Problem(403,"CURRENT_ENGINEER_REQUIRED");return row;
 }
 @SuppressWarnings("unchecked") private static Map<String,Object> json(Object value){return Chain.JSON.readValue(value.toString(),Map.class);}
 public Map<String,Object> operation(String ticket,String operation){uuid(ticket);uuid(operation);Actor a=authority.current(false);return transaction(db->{ticket(db,ticket,current(a,false),false,true);var old=db.queryForList("SELECT * FROM ticket_fee_labor_operation WHERE tenant_id=? AND operation_id=? AND actor_user_id=? AND ticket_id=?",a.tenant(),operation,a.user(),ticket);if(old.size()!=1)throw new Problem(404,"OPERATION_NOT_OBSERVED");return json(old.getFirst().get("receipt_json"));});}
 public Map<String,Object> execute(Command command){Actor a=authority.current(false);return transaction(true,db->{
  var row=ticket(db,command.ticketId(),a,true,true);Actor checked=current(a,false);ticketScope(row,checked);
  var old=db.queryForList("SELECT * FROM ticket_fee_labor_operation WHERE tenant_id=? AND operation_id=?",a.tenant(),command.value("operationId"));
  if(!old.isEmpty()){var prior=old.getFirst();if(((Number)prior.get("actor_user_id")).longValue()!=a.user()||!command.ticketId().equals(prior.get("ticket_id"))||!command.digest().equals(prior.get("command_digest")))throw new Problem(409,"IDEMPOTENCY_CONFLICT");return json(prior.get("receipt_json"));}
  ticketScope(row,current(a,true));
  if(!"IN_PROGRESS".equals(row.get("status")))throw new Problem(409,"TICKET_NOT_IN_PROGRESS");
  if(((Number)row.get("row_version")).longValue()!=command.count("expectedVersion"))throw new Problem(409,"VERSION_CONFLICT");
  if(!command.value("roundId").equals(row.get("round_id"))||!command.value("assignmentId").equals(row.get("assignment_id")))throw new Problem(409,"EXECUTION_CONTEXT_CHANGED");
  var fence=fence(db,row,command.value("groupId"),command.value("quoteId"),command.count("quoteVersion"),command.count("generation"));
  var fact=authority.completion(a.tenant(),((Number)row.get("company_id")).longValue(),command.value("groupId"),command.value("completionId"));
  validateCompletion(fact,row,fence,command.value("completionId"));
  var lines=coverage(fact);var line=lines.stream().filter(l->command.value("lineId").equals(l.get("id"))).findFirst().orElseThrow(()->new Problem(409,"LINE_NOT_APPROVED"));
  if(!Set.of("LABOR","OTHER").contains(line.get("kind")))throw new Problem(409,"PART_EXECUTION_REQUIRES_STOCK_OWNER");
  if(!command.ticketId().equals(line.get("ticketId"))||!command.value("roundId").equals(line.get("roundId"))||command.count("amountCents")>number(line.get("amountCents"),false))throw new Problem(409,"EXECUTION_COVERAGE_MISMATCH");
  var records=db.queryForList("SELECT id FROM ticket_processing_record WHERE id=? AND ticket_id=? AND round_id=? AND assignment_id=? AND actor_user_id=? AND type='RECORDS'",command.value("processingRecordId"),command.ticketId(),command.value("roundId"),command.value("assignmentId"),a.user());
  if(records.size()!=1)throw new Problem(409,"PROCESSING_RECORD_MISMATCH");
  if(db.queryForObject("SELECT COUNT(*) FROM ticket_fee_labor_execution WHERE tenant_id=? AND group_id=? AND line_id=?",Long.class,a.tenant(),command.value("groupId"),command.value("lineId"))!=0)throw new Problem(409,"FEE_LINE_ALREADY_EXECUTED");
  // Recheck online authority after peer IO, still holding the common ticket lock.
  ticketScope(row,current(a,true));fresh(fact);
  String receipt=UUID.randomUUID().toString();var out=new LinkedHashMap<String,Object>();out.put("receiptId",receipt);out.put("operationId",command.value("operationId"));out.put("ticketId",command.ticketId());out.put("tenantId",Long.toString(a.tenant()));out.put("actorUserId",Long.toString(a.user()));out.put("companyId",row.get("company_id").toString());
  for(String k:List.of("roundId","assignmentId","groupId","completionId","quoteId","quoteVersion","generation","lineId","processingRecordId","amountCents"))out.put(k,command.body().get(k));out.put("kind",line.get("kind"));out.put("coverageDigest",fact.get("coverageDigest"));out.put("state","EXECUTED");out.put("ticketVersion",Long.toString(Math.addExact(command.count("expectedVersion"),1)));
  db.update("INSERT INTO ticket_fee_labor_execution VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",receipt,a.tenant(),row.get("company_id"),a.user(),command.ticketId(),command.value("roundId"),command.value("assignmentId"),command.value("groupId"),command.value("completionId"),command.value("quoteId"),command.count("quoteVersion"),command.count("generation"),command.value("lineId"),command.value("processingRecordId"),line.get("kind"),command.count("amountCents"),fact.get("coverageDigest"),command.digest(),command.value("operationId"),Timestamp.from(Instant.now()));
  db.update("INSERT INTO ticket_fee_labor_operation VALUES(?,?,?,?,?,?,?)",a.tenant(),command.value("operationId"),a.user(),command.ticketId(),command.digest(),Chain.JSON.writeValueAsString(out),Timestamp.from(Instant.now()));
  db.update("INSERT INTO ticket_fee_labor_audit VALUES(?,?,?,?,?,?,?,?)",a.tenant(),command.value("operationId"),a.user(),command.ticketId(),receipt,"FEE_LABOR_EXECUTED",command.digest(),Timestamp.from(Instant.now()));
  long eventVersion=Math.addExact(command.count("expectedVersion"),1);Instant occurred=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);var payload=new TreeMap<String,Object>();payload.put("ticketId",command.ticketId());payload.put("roundId",command.value("roundId"));payload.put("executionId",receipt);payload.put("feeGroupId",command.value("groupId"));payload.put("generation",Long.toString(command.count("generation")));payload.put("completionId",command.value("completionId"));payload.put("lineId",command.value("lineId"));payload.put("processingRecordId",command.value("processingRecordId"));payload.put("amountCents",Long.toString(command.count("amountCents")));payload.put("companyId",row.get("company_id").toString());payload.put("assignmentId",command.value("assignmentId"));payload.put("kind",line.get("kind"));var envelope=new LinkedHashMap<String,Object>();envelope.put("eventId",command.value("operationId"));envelope.put("type","ticket.fee_execution_recorded");envelope.put("schemaVersion",1);envelope.put("tenantId",Long.toString(a.tenant()));envelope.put("aggregateId",receipt);envelope.put("aggregateVersion",Long.toString(eventVersion));envelope.put("occurredAt",String.format(java.util.Locale.ROOT,"%s.%03dZ",occurred.toString().substring(0,19),occurred.getNano()/1_000_000));envelope.put("producer","ticket");envelope.put("correlationId",command.value("operationId"));envelope.put("payload",payload);String envelopeJson=Chain.JSON.writeValueAsString(envelope),eventDigest=FinanceFreezeCoordinator.sha(envelope);
  db.update("INSERT INTO ticket_fee_notification_outbox(event_id,tenant_id,event_type,aggregate_id,aggregate_version,fee_group_id,generation,ticket_id,round_id,assignment_id,company_id,department_id,applicant_user_id,envelope,event_digest,delivery_state,attempt_count,next_attempt_at,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING',0,?,?)",command.value("operationId"),a.tenant(),"ticket.fee_execution_recorded",receipt,eventVersion,command.value("groupId"),command.count("generation"),command.ticketId(),command.value("roundId"),command.value("assignmentId"),row.get("company_id"),row.get("department_id"),row.get("reporter_user_id"),envelopeJson,eventDigest,Timestamp.from(occurred),Timestamp.from(occurred));
  if(db.update("UPDATE ticket_record SET row_version=row_version+1 WHERE id=? AND row_version=?",command.ticketId(),command.count("expectedVersion"))!=1)throw new Problem(409,"VERSION_CONFLICT");return out;
 });}
 private static void ticketScope(Map<String,Object> row,Actor a){if(!a.companies().contains(((Number)row.get("company_id")).longValue())||a.department()==null||row.get("engineer_user_id")==null||a.user()!=((Number)row.get("engineer_user_id")).longValue()||row.get("department_id")==null||a.department()!=((Number)row.get("department_id")).longValue())throw new Problem(403,"CURRENT_ENGINEER_REQUIRED");}
 private Map<String,Object> fence(JdbcTemplate db,Map<String,Object> row,String group,String quote,long version,long generation){
  var fences=db.queryForList("SELECT * FROM ticket_finance_quote_fence WHERE tenant_id=? AND ticket_id=? ORDER BY generation DESC LIMIT 1",row.get("tenant_id"),row.get("id"));if(fences.size()!=1)throw new Problem(409,"FEE_EXECUTION_FENCE_MISSING");var f=fences.getFirst();
  if(!Objects.equals(row.get("company_id"),f.get("company_id"))||!row.get("round_id").equals(f.get("round_id"))||!group.equals(f.get("group_id"))||!quote.equals(f.get("quote_id"))||generation!=((Number)f.get("generation")).longValue()||version!=Math.addExact(((Number)f.get("expected_group_version")).longValue(),1))throw new Problem(409,"FEE_EXECUTION_STALE_GENERATION");
  var co=db.queryForList("SELECT * FROM ticket_finance_coordination WHERE freeze_id=?",f.get("freeze_id"));if(co.size()!=1||!"RELEASED".equals(co.getFirst().get("state"))||!"COMMITTED".equals(co.getFirst().get("source_state")))throw new Problem(409,"FEE_EXECUTION_FENCED");
  if(db.queryForObject("SELECT COUNT(*) FROM ticket_finance_quote_fence f JOIN ticket_finance_coordination c ON c.freeze_id=f.freeze_id WHERE f.tenant_id=? AND f.ticket_id=? AND c.state<>'RELEASED'",Long.class,row.get("tenant_id"),row.get("id"))!=0)throw new Problem(409,"FEE_EXECUTION_FENCED");
  if(db.queryForObject("SELECT COUNT(*) FROM ticket_finance_quote_source_result WHERE freeze_id=? AND result_digest=?",Long.class,f.get("freeze_id"),co.getFirst().get("result_digest"))!=1||db.queryForObject("SELECT COUNT(*) FROM ticket_finance_owner_ack WHERE freeze_id=? AND phase='RELEASED' AND owner_name IN('approval','stock')",Long.class,f.get("freeze_id"))!=2)throw new Problem(503,"FEE_EXECUTION_OWNER_ACK_MISSING");return f;
 }
 private static void validateCompletion(Map<String,Object> fact,Map<String,Object> row,Map<String,Object> fence,String completion){
  fresh(fact);var expected=new LinkedHashMap<String,Object>();expected.put("tenantId",row.get("tenant_id").toString());expected.put("companyId",row.get("company_id").toString());expected.put("ticketId",row.get("id"));expected.put("roundId",row.get("round_id"));
  expected.put("groupId",fence.get("group_id"));expected.put("quoteId",fence.get("quote_id"));expected.put("quoteVersion",Long.toString(Math.addExact(((Number)fence.get("expected_group_version")).longValue(),1)));expected.put("generation",fence.get("generation").toString());expected.put("faultRelationId",fence.get("fault_relation_id"));expected.put("quoteDigest",fence.get("command_digest"));expected.put("completionId",completion);expected.put("state","COMPLETED");match(fact,expected);
  try{uuid(fact.get("applicationId"));uuid(fact.get("supervisorReceiptId"));hex(fact.get("coverageDigest"));if(!(fact.get("financialReviewRequired") instanceof Boolean required))throw invalid();if(required)uuid(fact.get("financeReceiptId"));}catch(Problem e){throw invalid();}
 }
 public Map<String,Object> projection(Map<String,Object> request){
  if(!request.keySet().equals(Set.of("sessionToken","companyId","groupId","completionId","quoteId","quoteVersion","generation","operationId","authorityDigest","lines")))throw bad();
  for(String k:List.of("groupId","completionId","quoteId","operationId"))uuid(request.get(k));long company=number(request.get("companyId"),false),version=number(request.get("quoteVersion"),false),generation=number(request.get("generation"),false);hex(request.get("authorityDigest"));
  if(!(request.get("lines") instanceof List<?> wanted)||wanted.isEmpty()||wanted.size()>50)throw bad();Actor actor=authority.current(false);
  return transaction(db->{var now=current(actor,false);if(!now.companies().contains(company))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");var items=new ArrayList<Map<String,Object>>();var seen=new HashSet<String>();
   for(Object raw:wanted){if(!(raw instanceof Map<?,?> line)||!line.keySet().equals(Set.of("lineId","amountCents","ticketId","roundId","kind")))throw bad();String id=uuid(line.get("lineId")),ticketId=uuid(line.get("ticketId"));uuid(line.get("roundId"));long approved=number(line.get("amountCents"),false);if(!seen.add(id)||!Set.of("LABOR","OTHER","PART").contains(line.get("kind")))throw bad();
    var row=ticket(db,ticketId,now,false,false);if(company!=((Number)row.get("company_id")).longValue()||!line.get("roundId").equals(row.get("round_id")))throw new Problem(409,"EXECUTION_ROUND_CHANGED");
    var f=fence(db,row,request.get("groupId").toString(),request.get("quoteId").toString(),version,generation);var completion=authority.completion(actor.tenant(),company,request.get("groupId").toString(),request.get("completionId").toString());validateCompletion(completion,row,f,request.get("completionId").toString());
    var approvedLine=coverage(completion).stream().filter(x->id.equals(x.get("id"))).findFirst().orElseThrow(FinanceLaborExecution::invalid);for(String k:List.of("ticketId","roundId","kind","amountCents"))if(!Objects.equals(line.get(k),approvedLine.get(k)))throw invalid();
    var rows=db.queryForList("SELECT * FROM ticket_fee_labor_execution WHERE tenant_id=? AND group_id=? AND line_id=?",actor.tenant(),request.get("groupId"),id);if(rows.size()>1)throw invalid();var item=new LinkedHashMap<String,Object>();for(String k:List.of("lineId","ticketId","roundId","kind"))item.put(k,line.get(k));
    if("PART".equals(line.get("kind"))){item.put("state","OWNER_REQUIRED");items.add(item);continue;}
    if(rows.isEmpty()){item.put("state","NOT_EXECUTED");item.put("amountCents","0");}else{var e=rows.getFirst();if(!Objects.equals(e.get("completion_id"),request.get("completionId"))||!Objects.equals(e.get("quote_id"),request.get("quoteId"))||((Number)e.get("quote_version")).longValue()!=version||((Number)e.get("generation")).longValue()!=generation||!Objects.equals(e.get("ticket_id"),ticketId)||!Objects.equals(e.get("round_id"),line.get("roundId"))||!Objects.equals(e.get("kind"),line.get("kind"))||!Objects.equals(e.get("coverage_digest"),completion.get("coverageDigest")))throw invalid();long amount=((Number)e.get("amount_cents")).longValue();if(amount<=0||amount>approved)throw invalid();item.put("state","EXECUTED");item.put("amountCents",Long.toString(amount));item.put("executionReceiptId",e.get("receipt_id"));}items.add(item);
   }
   var last=current(actor,false);if(!last.companies().contains(company)||last.department()==null)throw new Problem(404,"TARGET_NOT_ACCESSIBLE");var out=new LinkedHashMap<String,Object>();out.put("tenantId",Long.toString(actor.tenant()));for(String k:List.of("companyId","groupId","completionId","quoteId","quoteVersion","generation","operationId","authorityDigest"))out.put(k,request.get(k));out.put("state","PROJECTION");items.sort(Comparator.comparing(x->x.get("lineId").toString()));out.put("items",items);out.put("checkedAt",Instant.now().toString());return out;
  });
 }
 public Map<String,Object> query(Map<String,Object> request){
  if(!request.keySet().equals(Set.of("sessionToken","groupId","completionId","quoteId","quoteVersion","generation","operationId","authorityDigest","lines")))throw bad();
  for(String k:List.of("groupId","completionId","quoteId","operationId"))uuid(request.get(k));long version=number(request.get("quoteVersion"),false),generation=number(request.get("generation"),false);hex(request.get("authorityDigest"));
  if(!(request.get("lines") instanceof List<?> wanted)||wanted.isEmpty()||wanted.size()>50)throw bad();var amounts=new TreeMap<String,String>();for(Object o:wanted){if(!(o instanceof Map<?,?> m)||!m.keySet().equals(Set.of("lineId","amountCents")))throw bad();String id=uuid(m.get("lineId"));number(m.get("amountCents"),false);if(amounts.put(id,m.get("amountCents").toString())!=null)throw bad();}
  Actor a=authority.current(false);return transaction(db->{Actor now=current(a,false);var rows=db.queryForList("SELECT * FROM ticket_fee_labor_execution WHERE tenant_id=? AND group_id=? AND completion_id=? ORDER BY ticket_id,line_id",a.tenant(),request.get("groupId"),request.get("completionId"));
   var selected=rows.stream().filter(r->amounts.containsKey(r.get("line_id").toString())).toList();if(selected.size()!=amounts.size())throw new Problem(404,"FEE_EXECUTION_NOT_OBSERVED");
   var items=new ArrayList<Map<String,Object>>();String coverage=null,company=null;
   for(var e:selected){if(!e.get("quote_id").equals(request.get("quoteId"))||((Number)e.get("quote_version")).longValue()!=version||((Number)e.get("generation")).longValue()!=generation||!e.get("amount_cents").toString().equals(amounts.get(e.get("line_id"))))throw new Problem(409,"EXECUTION_COVERAGE_MISMATCH");
    var ticket=ticket(db,e.get("ticket_id").toString(),now,false,false);var f=fence(db,ticket,e.get("group_id").toString(),e.get("quote_id").toString(),version,generation);if(!ticket.get("round_id").equals(e.get("round_id")))throw new Problem(409,"EXECUTION_ROUND_CHANGED");
    if(coverage!=null&&!coverage.equals(e.get("coverage_digest"))||company!=null&&!company.equals(e.get("company_id").toString()))throw invalid();coverage=e.get("coverage_digest").toString();company=e.get("company_id").toString();
    items.add(Map.of("lineId",e.get("line_id"),"ticketId",e.get("ticket_id"),"roundId",e.get("round_id"),"kind",e.get("kind"),"amountCents",e.get("amount_cents").toString(),"processingRecordId",e.get("processing_record_id"),"executionReceiptId",e.get("receipt_id")));
   }
   items.sort(Comparator.comparing(i->i.get("lineId").toString()));var out=new LinkedHashMap<String,Object>();out.put("tenantId",Long.toString(a.tenant()));out.put("actorUserId",Long.toString(a.user()));out.put("companyId",company);for(String k:List.of("groupId","completionId","quoteId","quoteVersion","generation","operationId","authorityDigest"))out.put(k,request.get(k));out.put("coverageDigest",coverage);out.put("state","EXECUTED");out.put("items",items);
   // Stable receipt identifies persisted W facts, never the caller's query operation UUID.
   out.put("receiptId",UUID.nameUUIDFromBytes(("fee-execution:"+sha(Map.of("tenantId",out.get("tenantId"),"groupId",request.get("groupId"),"completionId",request.get("completionId"),"items",items))).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
   Actor finalScope=current(a,false);if(finalScope.department()==null||!finalScope.companies().contains(Long.parseLong(company)))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");out.put("checkedAt",Instant.now().toString());return out;
  });
 }
}
