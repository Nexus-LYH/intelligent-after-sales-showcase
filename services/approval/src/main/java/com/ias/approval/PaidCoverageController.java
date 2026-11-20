package com.ias.approval;
import com.ias.trust.*;
import jakarta.servlet.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;
import static com.ias.approval.FeeApprovalPersistence.*;

/** Read-only proof that a completed fee approval currently covers a physical PART issue/use. */
@RestController
public final class PaidCoverageController {
 private final JdbcTemplate db;
 public PaidCoverageController(JdbcTemplate db){this.db=db;}
 @PostMapping("/internal/approval/paid-coverage")
 public Map<String,Object> coverage(@RequestBody Map<String,Object> b,HttpServletRequest r){
  if(!Chain.active()||!"approval".equals(Chain.role())||!r.isSecure()||!"stock-approval".equals(r.getAttribute("ias.chain.edge")))throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");
  if(!r.getParameterMap().isEmpty()||!b.keySet().equals(Set.of("tenantId","approvalId","ticketId","warehouseId","partId","quantity")))throw new Problem(400,"VALIDATION_ERROR");
  String tenant=text(b,"tenantId"),approval=uuid(b.get("approvalId")),ticket=uuid(b.get("ticketId")),warehouse=text(b,"warehouseId"),part=text(b,"partId"),quantity=text(b,"quantity");
  var current=db.queryForList("SELECT generation,state FROM approval_finance_generation WHERE tenant_id=? AND ticket_id=?",tenant,ticket);
  if(current.isEmpty())throw new Problem(503,"PAID_COVERAGE_UNAVAILABLE");
  var cr=current.getFirst();if(!"OPEN".equals(cr.get("state")))throw new Problem(409,"PAID_COVERAGE_GENERATION_STALE");String generation=cr.get("generation").toString();
  var rows=db.queryForList("SELECT a.id,a.state,a.snapshot,b.lines_json,b.coverage_digest FROM fee_approval_application a JOIN fee_approval_binding b ON b.tenant_id=a.tenant_id AND b.application_id=a.id WHERE a.tenant_id=? AND a.ticket_id=? AND a.state='COMPLETED'",tenant,ticket);
  boolean stale=false;
  for(var row:rows){
   try{
    var snap=Chain.JSON.readValue(row.get("snapshot").toString(),Map.class);var basis=(Map<?,?>)snap.get("basis");if(!generation.equals(Objects.toString(basis.get("generation"),""))){stale=true;continue;}
    var lines=Chain.JSON.readValue(row.get("lines_json").toString(),List.class);
    for(Object raw:lines){if(!(raw instanceof Map<?,?> line)||!"PART".equals(line.get("kind"))||!(line.get("partBinding") instanceof Map<?,?> bind))continue;
     if(!approval.equals(bind.get("approvalId"))||!warehouse.equals(bind.get("warehouseId"))||!part.equals(bind.get("partId"))||!quantity.equals(bind.get("quantity")))continue;
     String digest=row.get("coverage_digest").toString();if(!digest.matches("[0-9a-f]{64}"))throw new Problem(503,"PAID_COVERAGE_UNAVAILABLE");
     return Map.of("tenantId",tenant,"approvalId",approval,"ticketId",ticket,"warehouseId",warehouse,"partId",part,"quantity",quantity,"feeApplicationId",row.get("id").toString(),"coverageDigest",digest,"checkedAt",Instant.now().toString());
    }
   }catch(Problem problem){throw problem;}catch(Exception malformed){throw new Problem(503,"PAID_COVERAGE_UNAVAILABLE");}
  }
  throw new Problem(stale?409:503,stale?"PAID_COVERAGE_GENERATION_STALE":"PAID_COVERAGE_UNAVAILABLE");
 }
 private static String text(Map<String,Object>b,String k){Object v=b.get(k);if(!(v instanceof String s)||!s.matches("[1-9][0-9]{0,18}"))throw new Problem(400,"VALIDATION_ERROR");return s;}
 private static String uuid(Object v){if(!(v instanceof String s))throw new Problem(400,"VALIDATION_ERROR");try{if(!UUID.fromString(s).toString().equals(s))throw new Exception();return s;}catch(Exception e){throw new Problem(400,"VALIDATION_ERROR");}}
}
