package com.ias.ticket;

import com.ias.trust.*;
import jakarta.servlet.http.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** The stock service owns the immutable USE and fee execution facts. */
@RestController
public final class FinancePartBridge {
 interface Peer { Map<String,Object> send(String method,String path,Map<String,Object> body,Map<String,String> headers,Tickets.Actor actor); }
 interface ActorProvider { Tickets.Actor read(String ticket,HttpServletRequest request,boolean write); }
 private final Tickets tickets; private final StaffAccess access; private final Peer peer;
 private ActorProvider actorProvider=this::authorizedActor;
 @org.springframework.beans.factory.annotation.Autowired
 public FinancePartBridge(Tickets tickets,StaffAccess access){this(tickets,access,(method,path,body,headers,actor)->{
  if(!Chain.active()||!"ticket".equals(Chain.role()))throw new Problem(503,"TRUST_NOT_CONFIGURED");
  byte[] bytes=body==null?new byte[0]:Chain.JSON.writeValueAsBytes(body);
  var reply=Chain.send("ticket-stock",method,path,bytes,headers,actor.trace(),actor.deadline());
  var result=Chain.parse(reply.bytes());if(reply.status()>=400)throw new Problem(reply.status(),String.valueOf(result.getOrDefault("code","DEPENDENCY_UNAVAILABLE")));return result;
 });}
 FinancePartBridge(Tickets tickets,StaffAccess access,Peer peer){this.tickets=tickets;this.access=access;this.peer=peer;}
 private Tickets.Actor authorizedActor(String ticket,HttpServletRequest request,boolean write){
  if(!request.getParameterMap().isEmpty())throw Tickets.bad();if(write)Chain.source(request);String token=Chain.cookie(request),csrf=write?Chain.single(request,"X-CSRF-TOKEN"):null;String trace=Chain.trace(request);long deadline=Chain.deadline(request);
  var identity=Chain.identity("ticket-identity",token,write?"TICKET_WORK":"TICKET_READ",csrf,trace,deadline);Chain.fresh(identity,deadline);
  var actor=new Tickets.Actor(Tickets.id(identity.get("tenantId")),Tickets.id(identity.get("userId")),Objects.toString(identity.get("identityType")),token,csrf,trace,deadline,Objects.toString(identity.get("attempt"),""));var row=tickets.accessible(ticket,actor);var facts=access.facts(actor);
  if(!access.engineer(facts,row)||!access.permission(facts,"ticket.record"))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");return actor;
 }
 FinancePartBridge(Tickets tickets,StaffAccess access,Peer peer,ActorProvider actorProvider){this(tickets,access,peer);this.actorProvider=actorProvider;}
 @PostMapping("/api/ticket/tickets/{ticket}/part-fee-executions")
 public Map<String,Object> execute(@PathVariable String ticket,@RequestBody String raw,HttpServletRequest request){
  ticket=Tickets.uuid(ticket);var body=new FeePartCommand(FinanceLaborExecutionController.body(raw)).body();
  String operation=Tickets.uuid(body.get("operationId")),use=Tickets.uuid(body.get("useOperationId"));
  if(!operation.equals(Tickets.uuid(Chain.single(request,"Idempotency-Key"))))throw Tickets.bad();
  var actor=actorProvider.read(ticket,request,true);var row=tickets.accessible(ticket,actor);
  if(!"IN_PROGRESS".equals(row.get("status"))||row.get("current_cancel_request_id")!=null||!row.get("company_id").toString().equals(body.get("companyId")))throw new Problem(409,"STOCK_EXECUTION_NOT_CURRENT");
  var matches=tickets.db.queryForList("SELECT p.round_id,p.assignment_id,p.warehouse_id FROM ticket_stock_execution p JOIN ticket_stock_execution_result r ON r.operation_id=p.operation_id WHERE p.operation_id=? AND p.tenant_id=? AND p.actor_user_id=? AND p.ticket_id=? AND p.phase='USE'",use,actor.tenant(),actor.user(),ticket);
  if(matches.size()!=1||!Objects.equals(matches.getFirst().get("round_id"),row.get("round_id"))||!Objects.equals(matches.getFirst().get("assignment_id"),row.get("assignment_id"))||!matches.getFirst().get("warehouse_id").toString().equals(body.get("warehouseId")))throw new Problem(409,"STOCK_USE_NOT_CURRENT");
  // Durable permit first: stock verifies this exact operation/command before writing.
  var permits=new FeePartPermitStore(tickets);
  try{permits.prepare(actor,new FeePartPermitStore.Command(ticket,row.get("round_id").toString(),row.get("assignment_id").toString(),new FeePartCommand(body)));}
  catch(Problem problem){throw problem;}catch(Exception failure){throw new Problem(503,"PART_EXECUTION_FENCE_REQUIRED");}
  var payload=new TreeMap<String,Object>(body);payload.put("sessionToken",actor.token());
  var executed=peer.send("POST","/internal/stock/finance-part-executions",payload,Map.of("Idempotency-Key",operation,"X-Session-Token",actor.token(),"X-CSRF-TOKEN",actor.csrf()),actor);
  var recovery=new FeePartRecovery(tickets,req->peer.send("POST","/internal/stock/finance-part-executions/owner-result",req,Map.of(),actor));
  String state=recovery.reconcile(actor.tenant(),operation);
  if(!"COMMITTED".equals(state))throw new Problem(503,"PART_EXECUTION_NOT_COMMITTED");
  var out=new LinkedHashMap<String,Object>();out.put("operationId",operation);out.put("state",state);out.put("execution",executed);return out;
 }
 @GetMapping("/api/ticket/tickets/{ticket}/part-fee-execution-operations/{operationId}")
 public Map<String,Object> operation(@PathVariable String ticket,@PathVariable String operationId,HttpServletRequest request){
  ticket=Tickets.uuid(ticket);operationId=Tickets.uuid(operationId);var actor=actorProvider.read(ticket,request,false);
  var result=peer.send("GET","/internal/stock/finance-part-execution-operations/"+operationId,null,Map.of("X-Session-Token",actor.token()),actor);
  if(!ticket.equals(result.get("ticketId"))||!operationId.equals(result.get("operationId")))throw new Problem(404,"OPERATION_NOT_FOUND");return result;
 }
 @PostMapping("/api/ticket/tickets/{ticket}/part-fee-execution-operations/{operationId}/reconcile")
 public Map<String,Object> reconcile(@PathVariable String ticket,@PathVariable String operationId,HttpServletRequest request){
  final String target=Tickets.uuid(ticket),op=Tickets.uuid(operationId);if(!op.equals(Tickets.uuid(Chain.single(request,"Idempotency-Key"))))throw Tickets.bad();
  var actor=actorProvider.read(target,request,true);
  var permits=tickets.db.queryForList("SELECT operation_id FROM ticket_fee_part_permit WHERE tenant_id=? AND actor_user_id=? AND ticket_id=? AND operation_id=?",actor.tenant(),actor.user(),target,op);
  if(permits.size()!=1)throw new Problem(404,"OPERATION_NOT_FOUND");
  var recovery=new FeePartRecovery(tickets,body->{
   var result=peer.send("POST","/internal/stock/finance-part-executions/owner-result",body,Map.of(),actor);
   var current=actorProvider.read(target,request,true);if(current.tenant()!=actor.tenant()||current.user()!=actor.user())throw new Problem(404,"OPERATION_NOT_FOUND");
   return result;
  });
  return Map.of("operationId",op,"state",recovery.reconcile(actor.tenant(),op));
 }
 @PostMapping("/api/ticket/tickets/{ticket}/part-fee-execution-operations/{operationId}/refuse")
 public Map<String,Object> refuse(@PathVariable String ticket,@PathVariable String operationId,HttpServletRequest request){
  final String target=Tickets.uuid(ticket),op=Tickets.uuid(operationId);if(!op.equals(Tickets.uuid(Chain.single(request,"Idempotency-Key"))))throw Tickets.bad();
  var actor=actorProvider.read(target,request,true);
  var permits=tickets.db.queryForList("SELECT command_digest FROM ticket_fee_part_permit WHERE tenant_id=? AND actor_user_id=? AND ticket_id=? AND operation_id=?",actor.tenant(),actor.user(),target,op);
  if(permits.size()!=1)throw new Problem(404,"OPERATION_NOT_FOUND");String digest=permits.getFirst().get("command_digest").toString();
  var reply=peer.send("POST","/internal/stock/finance-part-executions/refuse",Map.of("tenantId",Long.toString(actor.tenant()),"operationId",op,"commandDigest",digest),Map.of("Idempotency-Key",op),actor);
  String state=Objects.toString(reply.get("state"),"");String result=Objects.toString(reply.get("resultDigest"),"");
  if(!Set.of("COMMITTED","PROVEN_NO_EXECUTION").contains(state)||!result.matches("[0-9a-f]{64}"))throw new Problem(503,"PART_OWNER_RESULT_MISMATCH");
  new FeePartPermitStore(tickets).recordVerifiedResult(actor.tenant(),op,digest,state,result);
  return Map.of("operationId",op,"state",state,"resultDigest",result);
 }
 @ModelAttribute public void noStore(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
 @ExceptionHandler(Problem.class) ResponseEntity<Map<String,Object>> problem(Problem p){return ResponseEntity.status(p.status).header("Cache-Control","no-store").body(Map.of("code",p.code,"retryable",p.status==503));}
 @ExceptionHandler(Exception.class) ResponseEntity<Map<String,Object>> unknown(Exception e){return problem(new Problem(503,"OPERATION_RESULT_UNKNOWN"));}
}
