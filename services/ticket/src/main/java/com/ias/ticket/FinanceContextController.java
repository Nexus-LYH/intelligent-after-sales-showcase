package com.ias.ticket;
import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;
/** Service ownership is the immutable ticket company, never current device ownership. */
@RestController
public final class FinanceContextController {
 private final Tickets tickets;
 public FinanceContextController(Tickets tickets){this.tickets=tickets;}
 @PostMapping("/internal/ticket/finance-context")
 public Map<String,Object> context(@RequestBody Map<String,Object> body,HttpServletRequest request){
  ApprovalBoundaryController.trusted(request,Set.of("finance-ticket"));
  if(!body.keySet().containsAll(Set.of("sessionToken","ticketId"))||!Set.of("sessionToken","ticketId","purpose","groupId","expectedGroupVersion","quoteId","operationId","commandDigest").containsAll(body.keySet()))throw Tickets.bad();
  if(!(body.get("sessionToken") instanceof String token)||token.isBlank())throw Tickets.bad();
  String ticket=Tickets.uuid(body.get("ticketId"));
  boolean fee="FEE_READ".equals(body.get("purpose"));
  if(fee&&!body.keySet().equals(Set.of("sessionToken","ticketId","purpose")))throw Tickets.bad();
  var f=Chain.call("ticket-identity","/internal/identity/domain-authorizations",Map.of("sessionToken",token,"purpose",fee?"FEE_READ":"FINANCE_READ"),Chain.trace(request),Chain.deadline(request));Chain.fresh(f,Chain.deadline(request));
  long tenant=Tickets.id(f.get("tenantId")),user=Tickets.id(f.get("userId"));
  var rows=tickets.db.queryForList("SELECT r.* FROM ticket_record r WHERE r.id=? AND r.tenant_id=? AND EXISTS(SELECT 1 FROM ticket_create_operation o WHERE o.operation_id=r.operation_id AND o.state='COMPLETED')",ticket,tenant);
  if(rows.isEmpty())throw new Problem(404,"TARGET_NOT_ACCESSIBLE");var row=rows.getFirst();
  boolean visible=false;
  if("EMPLOYEE".equals(f.get("identityType")))visible=Boolean.TRUE.equals(f.get("enabled"))&&Boolean.TRUE.equals(f.get("departmentEnabled"))&&((List<?>)f.getOrDefault("companyIds",List.of())).contains(row.get("company_id").toString());
  if("CUSTOMER".equals(f.get("identityType"))){var actor=new Tickets.Actor(tenant,user,"CUSTOMER",token,null,Chain.trace(request),Chain.deadline(request),f.get("attempt").toString());visible=tickets.companies(actor).contains(row.get("company_id").toString());}
  if(!visible)throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
  if(fee&&!"EMPLOYEE".equals(f.get("identityType")))throw new Problem(403,"ACCESS_DENIED");
  if(body.containsKey("purpose")&&!fee){if(!"QUOTE".equals(body.get("purpose")))throw Tickets.bad();if(!body.keySet().equals(Set.of("sessionToken","ticketId","purpose","groupId","expectedGroupVersion","quoteId","operationId","commandDigest")))throw Tickets.bad();Tickets.uuid(body.get("groupId"));Tickets.uuid(body.get("quoteId"));Tickets.uuid(body.get("operationId"));Tickets.idOrZero(body.get("expectedGroupVersion"));if(!(body.get("commandDigest") instanceof String digest)||!digest.matches("[0-9a-f]{64}"))throw Tickets.bad();return new FinanceQuoteBoundary(tickets).quote(body,request);}
  if(!fee&&!body.keySet().equals(Set.of("sessionToken","ticketId")))throw Tickets.bad();
  return Map.of("tenantId",Long.toString(tenant),"companyId",row.get("company_id").toString(),"ticketId",ticket,"roundId",row.get("round_id"),"faultRelationId",ticket,"checkedAt",Instant.now().toString());
 }
}
