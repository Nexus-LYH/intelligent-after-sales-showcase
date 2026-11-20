package com.ias.ticket;

import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** BE-0 must explicitly enable the TLS approval-ticket edge. No public gateway route. */
@RestController
public final class ApprovalBoundaryController {
 final Tickets t;final ApprovalBoundary boundary;
 public ApprovalBoundaryController(Tickets t,StaffAccess access){this.t=t;boundary=new ApprovalBoundary(t,access);}
 Tickets.Actor actor(Map<String,Object> body,HttpServletRequest request,boolean write){
  trusted(request,Set.of("approval-ticket","stock-ticket","notification-ticket"));
  if(!(body.get("sessionToken") instanceof String token)||token.isBlank()||write&&(!(body.get("csrfToken") instanceof String csrf)||csrf.isBlank()))throw Tickets.bad();
  long deadline=Chain.deadline(request);String trace=Chain.trace(request),csrf=(String)body.get("csrfToken");
  var identity=Chain.identity("ticket-identity",token,write?"APPROVAL_SUBMIT":"APPROVAL_READ",csrf,trace,deadline);Chain.fresh(identity,deadline);
  return new Tickets.Actor(Tickets.id(identity.get("tenantId")),Tickets.id(identity.get("userId")),identity.get("identityType").toString(),token,csrf,trace,deadline,identity.get("attempt").toString());
 }
 @PostMapping("/internal/ticket/approval-context") Map<String,Object> context(@RequestBody Map<String,Object> body,HttpServletRequest request){
  if(!Set.of("sessionToken","ticketId","purpose","assignmentId").containsAll(body.keySet())||!body.keySet().containsAll(Set.of("sessionToken","ticketId")))throw Tickets.bad();var a=actor(body,request,false);String purpose=body.getOrDefault("purpose","READ").toString();String edge=request.getAttribute("ias.chain.edge").toString();if(edge.equals("stock-ticket")&&!purpose.equals("CATALOG")||edge.equals("notification-ticket")&&!purpose.equals("READ"))throw new Problem(403,"ACCESS_DENIED");return boundary.context(Tickets.uuid(body.get("ticketId")),purpose,body.get("assignmentId")==null?null:Tickets.uuid(body.get("assignmentId")),a);
 }
 @PostMapping("/internal/ticket/approval-references/register") Map<String,Object> register(@RequestBody Map<String,Object> body,HttpServletRequest request){
  if(!body.keySet().equals(Set.of("sessionToken","csrfToken","ticketId","roundId","assignmentId","operationId","commandDigest")))throw Tickets.bad();
  trusted(request,Set.of("approval-ticket"));
  var a=actor(body,request,true);var result=boundary.register(Tickets.uuid(body.get("ticketId")),Tickets.uuid(body.get("roundId")),Tickets.uuid(body.get("assignmentId")),Tickets.uuid(body.get("operationId")),body.get("commandDigest") instanceof String s?s:null,a);Chain.remaining(a.deadline());return result;
 }
 @PostMapping("/internal/ticket/approval-references/query") Map<String,Object> query(@RequestBody Map<String,Object>body,HttpServletRequest request){if(!body.keySet().equals(Set.of("sessionToken","ticketId","operationId")))throw Tickets.bad();trusted(request,Set.of("approval-ticket"));return boundary.query(Tickets.uuid(body.get("ticketId")),Tickets.uuid(body.get("operationId")),actor(body,request,false));}
 @PostMapping("/internal/ticket/approval-references/settle") Map<String,Object> settle(@RequestBody Map<String,Object>body,HttpServletRequest request){trusted(request,Set.of("approval-ticket"));if(!body.keySet().equals(Set.of("eventId","tenantId","ticketId","operationId","commandDigest","approvalId","decisionOperationId","state")))throw Tickets.bad();return boundary.settle(body);}
 static void trusted(HttpServletRequest request,Set<String>edges){if(!Chain.active()||!request.isSecure()||!edges.contains(request.getAttribute("ias.chain.edge")))throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");}
}
