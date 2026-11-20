package com.ias.approval;
import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.*;
import java.time.Instant;
import static com.ias.approval.StockExecutionContext.*;

/** Finance reads exact approved material ownership before creating a quote. */
@RestController
public final class FinancePartBindingController {
 interface Peer {Map<String,Object> call(String edge,String path,Map<String,Object> body,HttpServletRequest r);}
 private final FeePartApprovalBinding.Lookup approved;private final Peer peer;
 @org.springframework.beans.factory.annotation.Autowired
 public FinancePartBindingController(JdbcTemplate db){this((t,a,p,w)->StockExecutionContext.approved(db,t,a,p,w),(e,p,b,r)->Chain.call(e,p,b,Chain.trace(r),Chain.deadline(r)));}
 FinancePartBindingController(FeePartApprovalBinding.Lookup approved,Peer peer){this.approved=approved;this.peer=peer;}
 Map<String,Object> call(String edge,String path,Map<String,Object> b,HttpServletRequest r){var result=peer.call(edge,path,b,r);Chain.fresh(result,Chain.deadline(r));return result;}
 @PostMapping("/internal/approval/finance-part-binding")
 public Map<String,Object> binding(@RequestBody Map<String,Object> b,HttpServletRequest r){
  if(!Chain.active()||!"approval".equals(Chain.role())||!r.isSecure()||!"finance-approval".equals(r.getAttribute("ias.chain.edge")))throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");
  if(!r.getParameterMap().isEmpty()||!b.keySet().equals(Set.of("sessionToken","approvalId","warehouseId","ticketId","roundId")))throw new Problem(400,"VALIDATION_ERROR");
  String token=text(b,"sessionToken"),approval=uuid(b.get("approvalId")),warehouse=id(b.get("warehouseId")),ticket=uuid(b.get("ticketId")),round=uuid(b.get("roundId"));
  var identity=call("approval-identity","/internal/identity/domain-authorizations",Map.of("sessionToken",token,"purpose","FINANCE_READ"),r);
  if(!Boolean.TRUE.equals(identity.get("enabled"))||!"EMPLOYEE".equals(identity.get("identityType"))||!((List<?>)identity.getOrDefault("permissions",List.of())).contains("finance.quote.read"))throw new Problem(403,"ACCESS_DENIED");
  String tenant=id(identity.get("tenantId")),actor=id(identity.get("userId"));
  var current=call("approval-ticket","/internal/ticket/approval-context",Map.of("sessionToken",token,"ticketId",ticket,"purpose","READ"),r);
  if(!tenant.equals(current.get("tenantId"))||!ticket.equals(current.get("ticketId"))||!round.equals(current.get("roundId"))||!((List<?>)identity.getOrDefault("companyIds",List.of())).contains(current.get("companyId")))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
  var original=approved.approved(tenant,actor,approval,warehouse);
  if(!ticket.equals(original.get("ticketId"))||!round.equals(original.get("roundId"))||!Objects.equals(current.get("assignmentId"),original.get("assignmentId"))||!Objects.equals(current.get("engineerUserId"),original.get("applicantUserId"))||!"CUSTOMER_PAID".equals(original.get("chargeMode")))throw new Problem(409,"PART_APPROVAL_NOT_CURRENT");
  var rows=(List<?>)original.get("lines");if(rows.size()!=1||!(rows.getFirst() instanceof Map<?,?> part))throw new Problem(503,"PART_APPROVAL_FACT_INVALID");
  var catalog=call("approval-stock","/internal/stock/part-catalog-proof",Map.of("tenantId",tenant,"warehouseId",warehouse,"partId",part.get("partId")),r);
  if(!tenant.equals(catalog.get("tenantId"))||!warehouse.equals(catalog.get("warehouseId"))||!Objects.equals(part.get("partId"),catalog.get("partId"))||!Boolean.TRUE.equals(catalog.get("warehouseActive"))||!Boolean.TRUE.equals(catalog.get("partActive")))throw new Problem(503,"PART_CATALOG_FACT_MISMATCH");
  var out=new LinkedHashMap<>(original);out.put("companyId",current.get("companyId"));out.put("unitCode",text(catalog,"unitCode"));out.put("checkedAt",Instant.now().toString());return out;
 }
 @ExceptionHandler(Problem.class) ResponseEntity<Map<String,Object>> problem(Problem p){return ResponseEntity.status(p.status).header("Cache-Control","no-store").body(Map.of("code",p.code,"retryable",p.status==503));}
 @ExceptionHandler(Exception.class) ResponseEntity<Map<String,Object>> unavailable(Exception e){return problem(new Problem(503,"PART_APPROVAL_FACT_UNAVAILABLE"));}
}
