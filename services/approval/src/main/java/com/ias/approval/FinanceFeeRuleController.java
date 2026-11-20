package com.ias.approval;
import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;
/** Only append-only operator-published owner records can supply T; no environment/default threshold. */
@RestController
public final class FinanceFeeRuleController {
 private final JdbcTemplate db;
 public FinanceFeeRuleController(JdbcTemplate db){this.db=db;}
 @ExceptionHandler(Problem.class)
 public org.springframework.http.ResponseEntity<?> known(Problem p){return org.springframework.http.ResponseEntity.status(p.status).header("Cache-Control","no-store").body(Map.of("code",p.code,"retryable",p.status==503));}
 @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
 public org.springframework.http.ResponseEntity<?> malformed(Exception p){return org.springframework.http.ResponseEntity.badRequest().header("Cache-Control","no-store").body(Map.of("code","VALIDATION_ERROR"));}
 @ExceptionHandler(Exception.class)
 public org.springframework.http.ResponseEntity<?> unavailable(Exception p){return org.springframework.http.ResponseEntity.status(503).header("Cache-Control","no-store").body(Map.of("code","DEPENDENCY_UNAVAILABLE","retryable",true));}
 @PostMapping("/internal/approval/fee-rule")
 public Map<String,Object> rule(@RequestBody Map<String,Object> body,HttpServletRequest request){
  if(!Chain.active()||!request.isSecure()||!"finance-approval".equals(request.getAttribute("ias.chain.edge")))throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");
  if(!body.keySet().equals(Set.of("sessionToken"))||!(body.get("sessionToken") instanceof String token)||token.isBlank())throw new Problem(400,"VALIDATION_ERROR");
  var f=Chain.call("approval-identity","/internal/identity/domain-authorizations",Map.of("sessionToken",token,"purpose","FINANCE_READ"),Chain.trace(request),Chain.deadline(request));Chain.fresh(f,Chain.deadline(request));
  long tenant=Long.parseLong(f.get("tenantId").toString());
  return published(db,tenant);
 }
 static Map<String,Object> published(JdbcTemplate db,long tenant){
  var rows=db.queryForList("SELECT rule_version,threshold_cents FROM approval_fee_rule_publication WHERE tenant_id=? ORDER BY publication_sequence DESC LIMIT 1",tenant);
  if(rows.isEmpty())throw new Problem(503,"FEE_RULE_UNAVAILABLE");var row=rows.getFirst();
  return Map.of("published",true,"tenantId",Long.toString(tenant),"version",row.get("rule_version"),"thresholdCents",row.get("threshold_cents").toString(),"checkedAt",Instant.now().toString());
 }
}
