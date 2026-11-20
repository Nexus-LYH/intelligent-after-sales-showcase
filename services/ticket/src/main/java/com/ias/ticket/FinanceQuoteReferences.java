package com.ias.ticket;

import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Discover committed quote references only. Finance still owns quote state, prices and decisions. */
@RestController
public final class FinanceQuoteReferences {
 @FunctionalInterface interface Facts {Map<String,Object> call(String edge,String path,Map<String,Object> body,HttpServletRequest request);}
 private final Tickets tickets;private final Facts facts;
 @org.springframework.beans.factory.annotation.Autowired
 public FinanceQuoteReferences(Tickets tickets){this(tickets,(edge,path,body,r)->{var f=Chain.call(edge,path,body,Chain.trace(r),Chain.deadline(r));Chain.fresh(f,Chain.deadline(r));return f;});}
 FinanceQuoteReferences(Tickets tickets,Facts facts){this.tickets=tickets;this.facts=facts;}
 @ModelAttribute public void noStore(jakarta.servlet.http.HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
 @GetMapping("/api/ticket/tickets/{ticket}/finance-quote-references")
 public Map<String,Object> list(@PathVariable String ticket,HttpServletRequest request){
  return references(ticket,request,false);
 }
 @GetMapping("/api/ticket/tickets/{ticket}/fee-quote-references")
 public Map<String,Object> fees(@PathVariable String ticket,HttpServletRequest request){return references(ticket,request,true);}
 private Map<String,Object> references(String ticket,HttpServletRequest request,boolean fee){
  if(!Set.of("beforeGeneration").containsAll(request.getParameterMap().keySet()))throw Tickets.bad();
  String before=request.getParameter("beforeGeneration");
  if(before!=null&&(request.getParameterValues("beforeGeneration").length!=1||!before.matches("[1-9][0-9]{0,18}")||Tickets.id(before)<=0))throw Tickets.bad();
  String token=Chain.cookie(request);if(token==null||token.isBlank())throw new Problem(401,"SESSION_INVALID");
  var f=facts.call("ticket-identity","/internal/identity/domain-authorizations",Map.of("sessionToken",token,"purpose",fee?"FEE_READ":"FINANCE_READ"),request);
  if(!Boolean.TRUE.equals(f.get("enabled")))throw new Problem(401,"SESSION_INVALID");
  if(!((List<?>)f.getOrDefault("permissions",List.of())).contains(fee?"approval.fees.read":"finance.quote.read"))throw new Problem(403,"ACCESS_DENIED");
  long tenant=Tickets.id(f.get("tenantId")),actor=Tickets.id(f.get("userId"));
  var rows=tickets.db.queryForList("SELECT r.company_id,r.department_id,r.engineer_user_id,r.assignment_id FROM ticket_record r WHERE r.id=? AND r.tenant_id=? AND EXISTS(SELECT 1 FROM ticket_create_operation o WHERE o.operation_id=r.operation_id AND o.state='COMPLETED')",Tickets.uuid(ticket),tenant);
  if(rows.isEmpty())throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
  String company=rows.getFirst().get("company_id").toString();
  if("EMPLOYEE".equals(f.get("identityType"))){
   if(!Boolean.TRUE.equals(f.get("departmentEnabled"))||!((List<?>)f.getOrDefault("companyIds",List.of())).contains(company))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
   if(fee){
    var row=rows.getFirst();var permissions=(List<?>)f.getOrDefault("permissions",List.of());
    boolean engineer=Long.toString(actor).equals(Objects.toString(row.get("engineer_user_id"),""))&&row.get("assignment_id")!=null&&permissions.contains("approval.fees.submit");
    boolean reviewer=(permissions.contains("approval.fees.review")||permissions.contains("approval.fees.finance"))&&row.get("department_id")!=null&&((List<?>)f.getOrDefault("approvalReviewDepartmentIds",List.of())).contains(row.get("department_id").toString());
    if(!engineer&&!reviewer)throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
   }
  }else if("CUSTOMER".equals(f.get("identityType"))){
   if(fee)throw new Problem(403,"ACCESS_DENIED");
   var member=facts.call("ticket-asset","/internal/asset/finance-membership",Map.of("sessionToken",token,"companyId",company),request);
   if(!Long.toString(tenant).equals(member.get("tenantId"))||!Long.toString(actor).equals(member.get("userId"))||!company.equals(member.get("companyId"))||!Boolean.TRUE.equals(member.get("active"))||!Boolean.TRUE.equals(member.get("administrator")))throw new Problem(403,"ACCESS_DENIED");
  }else throw new Problem(403,"ACCESS_DENIED");
  var args=new ArrayList<Object>(List.of(tenant,ticket,company));String cursor="";
  if(before!=null){cursor=" AND f.generation<?";args.add(Tickets.id(before));}
  var found=tickets.db.queryForList("SELECT f.group_id,f.quote_id,f.expected_group_version,f.round_id,f.generation FROM ticket_finance_quote_fence f JOIN ticket_finance_coordination c ON c.freeze_id=f.freeze_id JOIN ticket_finance_quote_source_result s ON s.freeze_id=f.freeze_id WHERE f.tenant_id=? AND f.ticket_id=? AND f.company_id=? AND c.source_state='COMMITTED' AND c.result_digest=s.result_digest"+cursor+" ORDER BY f.generation DESC LIMIT 21",args.toArray());
  var visible=found.stream().limit(20).map(row->Map.of("groupId",row.get("group_id").toString(),"quoteId",row.get("quote_id").toString(),"quoteVersion",Long.toString(Math.addExact(((Number)row.get("expected_group_version")).longValue(),1)),"roundId",row.get("round_id").toString(),"generation",row.get("generation").toString())).toList();
  var out=new LinkedHashMap<String,Object>();out.put("items",visible);out.put("nextBeforeGeneration",found.size()>20?visible.getLast().get("generation"):null);return out;
 }
 @ExceptionHandler(Problem.class) org.springframework.http.ResponseEntity<Map<String,Object>> problem(Problem p){return org.springframework.http.ResponseEntity.status(p.status).header("Cache-Control","no-store").body(Map.of("code",p.code,"retryable",p.status==503));}
 @ExceptionHandler(Exception.class) org.springframework.http.ResponseEntity<Map<String,Object>> unavailable(Exception e){return problem(new Problem(503,"FINANCE_QUOTE_REFERENCES_UNAVAILABLE"));}
}
