package com.ias.ticket;

import com.ias.trust.Problem;
import java.util.*;

/** Local persistence building block only: no endpoint or authority to grant paid execution. */
final class FeePartPermitStore {
 record Command(String ticketId, String roundId, String assignmentId, FeePartCommand request) {}
 private final Tickets tickets;
 FeePartPermitStore(Tickets tickets) { this.tickets = tickets; }

 Map<String,Object> activeProof(long tenant,long actor,FeePartCommand command){
  if(tenant<=0||actor<=0||command==null)throw Tickets.bad();
  var body=command.body();String op=Tickets.uuid(body.get("operationId"));
  var rows=tickets.db.queryForList("SELECT p.*,r.result_type FROM ticket_fee_part_permit p LEFT JOIN ticket_fee_part_permit_result r ON r.operation_id=p.operation_id WHERE p.tenant_id=? AND p.actor_user_id=? AND p.operation_id=?",tenant,actor,op);
  if(rows.size()!=1)throw new Problem(404,"OPERATION_NOT_FOUND");var row=rows.getFirst();
  if(!command.digest().equals(row.get("command_digest"))||!com.ias.trust.Chain.JSON.writeValueAsString(body).equals(row.get("binding_json")))throw new Problem(409,"IDEMPOTENCY_CONFLICT");
  if(row.get("result_type")!=null)throw new Problem(409,"FEE_PART_PERMIT_COMPLETED");
  return Map.of("tenantId",Long.toString(tenant),"actorUserId",Long.toString(actor),"operationId",op,"commandDigest",command.digest(),"ticketId",row.get("ticket_id"),"roundId",row.get("round_id"),"assignmentId",row.get("assignment_id"),"state","PREPARED");
 }

 void prepare(Tickets.Actor actor, Command command) {
  if(command==null||command.request()==null)throw Tickets.bad();
  var request=command.request().body();String op=Tickets.uuid(request.get("operationId")), ticket=Tickets.uuid(command.ticketId());
  String round=Tickets.uuid(command.roundId()), assignment=Tickets.uuid(command.assignmentId()), use=Tickets.uuid(request.get("useOperationId"));
  long company=Tickets.id(request.get("companyId")),warehouse=Tickets.id(request.get("warehouseId"));
  String digest=command.request().digest(),binding=com.ias.trust.Chain.JSON.writeValueAsString(request);
  tickets.tx.executeWithoutResult(tx->{
   tickets.lockActor(actor.tenant(),actor.user());
   var rows=tickets.db.queryForList("SELECT * FROM ticket_record WHERE id=? AND tenant_id=? FOR UPDATE",ticket,actor.tenant());
   if(rows.isEmpty())throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
   var row=rows.getFirst();
   if(!"IN_PROGRESS".equals(row.get("status"))||row.get("current_cancel_request_id")!=null||!round.equals(row.get("round_id"))||!assignment.equals(row.get("assignment_id"))||Tickets.id(row.get("company_id"))!=company||Tickets.id(row.get("engineer_user_id"))!=actor.user())throw new Problem(409,"STOCK_EXECUTION_NOT_CURRENT");
   var uses=tickets.db.queryForList("SELECT p.round_id,p.assignment_id,p.warehouse_id,p.approval_id FROM ticket_stock_execution p JOIN ticket_stock_execution_result r ON r.operation_id=p.operation_id WHERE p.operation_id=? AND p.tenant_id=? AND p.actor_user_id=? AND p.ticket_id=? AND p.phase='USE'",use,actor.tenant(),actor.user(),ticket);
   if(uses.size()!=1||!round.equals(uses.getFirst().get("round_id"))||!assignment.equals(uses.getFirst().get("assignment_id"))||Tickets.id(uses.getFirst().get("warehouse_id"))!=warehouse)throw new Problem(409,"STOCK_USE_NOT_CURRENT");
   if(!request.get("orderId").equals(uses.getFirst().get("approval_id")))throw new Problem(409,"STOCK_USE_ORDER_MISMATCH");
   var previous=tickets.db.queryForList("SELECT * FROM ticket_fee_part_permit WHERE operation_id=?",op);
   if(!previous.isEmpty()){
    var p=previous.getFirst();
    if(Tickets.id(p.get("tenant_id"))!=actor.tenant()||Tickets.id(p.get("actor_user_id"))!=actor.user()||!ticket.equals(p.get("ticket_id")))throw new Problem(404,"OPERATION_NOT_FOUND");
    if(Tickets.id(p.get("company_id"))!=company||!round.equals(p.get("round_id"))||!assignment.equals(p.get("assignment_id"))||!digest.equals(p.get("command_digest"))||!binding.equals(p.get("binding_json")))throw new Problem(409,"IDEMPOTENCY_CONFLICT");
    return;
   }
   FeePartPermitBarrier.guard(tickets,ticket);FinanceQuoteBoundary.guard(tickets,ticket);
   tickets.db.update("INSERT INTO ticket_fee_part_permit VALUES(?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3))",op,actor.tenant(),actor.user(),company,ticket,round,assignment,digest,binding);
   tickets.db.update("INSERT INTO ticket_audit(operation_id,tenant_id,actor_user_id,action,occurred_at) VALUES(?,?,?,'FEE_PART_PERMIT_PREPARED',UTC_TIMESTAMP(3))",op,actor.tenant(),actor.user());
  });
 }

 /** Caller must verify the stock owner's immutable result before invoking; currently no caller exists. */
 void recordVerifiedResult(long tenant, String operationId, String commandDigest, String resultType, String resultDigest) {
  String op=Tickets.uuid(operationId), command=StockExecutionBoundary.digest(commandDigest), result=StockExecutionBoundary.digest(resultDigest);
  if(tenant<=0||resultType==null||!Set.of("COMMITTED","PROVEN_NO_EXECUTION").contains(resultType))throw Tickets.bad();
  var references=tickets.db.queryForList("SELECT actor_user_id,ticket_id FROM ticket_fee_part_permit WHERE tenant_id=? AND operation_id=?",tenant,op);
  if(references.size()!=1)throw new Problem(404,"OPERATION_NOT_FOUND");
  var reference=references.getFirst();long actor=Tickets.id(reference.get("actor_user_id"));String ticket=reference.get("ticket_id").toString();
  tickets.tx.executeWithoutResult(tx->{
   tickets.lockActor(tenant,actor);
   var rows=tickets.db.queryForList("SELECT id FROM ticket_record WHERE id=? AND tenant_id=? FOR UPDATE",ticket,tenant);
   if(rows.size()!=1)throw new Problem(404,"OPERATION_NOT_FOUND");
   var permits=tickets.db.queryForList("SELECT command_digest FROM ticket_fee_part_permit WHERE tenant_id=? AND operation_id=? AND ticket_id=? AND actor_user_id=?",tenant,op,ticket,actor);
   if(permits.size()!=1)throw new Problem(404,"OPERATION_NOT_FOUND");
   if(!command.equals(permits.getFirst().get("command_digest")))throw new Problem(409,"IDEMPOTENCY_CONFLICT");
   var previous=tickets.db.queryForList("SELECT result_type,result_digest FROM ticket_fee_part_permit_result WHERE operation_id=?",op);
   if(!previous.isEmpty()){
    if(!resultType.equals(previous.getFirst().get("result_type"))||!result.equals(previous.getFirst().get("result_digest")))throw new Problem(409,"IDEMPOTENCY_CONFLICT");
    return;
   }
   tickets.db.update("INSERT INTO ticket_fee_part_permit_result VALUES(?,?,?,UTC_TIMESTAMP(3))",op,resultType,result);
   String audit=UUID.nameUUIDFromBytes(("fee-part-complete:"+tenant+":"+op).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
   tickets.db.update("INSERT INTO ticket_audit(operation_id,tenant_id,actor_user_id,action,occurred_at) VALUES(?,?,0,'FEE_PART_PERMIT_COMPLETED',UTC_TIMESTAMP(3))",audit,tenant);
  });
 }
}
