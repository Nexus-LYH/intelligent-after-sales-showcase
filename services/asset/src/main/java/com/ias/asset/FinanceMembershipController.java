package com.ias.asset;
import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.time.Instant;
/** Membership state is not an enterprise-administrator assignment. */
@RestController
public final class FinanceMembershipController {
 private final JdbcTemplate db;
 public FinanceMembershipController(JdbcTemplate db){this.db=db;}
 @PostMapping("/internal/asset/finance-membership")
 public Map<String,Object> membership(@RequestBody Map<String,Object> body,HttpServletRequest request){
  if(!Chain.active()||!request.isSecure()||!Set.of("finance-asset","ticket-asset").contains(Objects.toString(request.getAttribute("ias.chain.edge"),"")))throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");
  if(!body.keySet().equals(Set.of("sessionToken","companyId"))||!(body.get("sessionToken") instanceof String token)||token.isBlank()||!(body.get("companyId") instanceof String company)||!company.matches("[1-9][0-9]{0,18}"))throw new Problem(400,"VALIDATION_ERROR");
  long companyId;try{companyId=Long.parseLong(company);}catch(Exception e){throw new Problem(400,"VALIDATION_ERROR");}
  var f=Chain.identity("asset-identity",token,"FINANCE_READ",null,Chain.trace(request),Chain.deadline(request));Chain.fresh(f,Chain.deadline(request));
  if(!"CUSTOMER".equals(f.get("identityType")))throw new Problem(403,"ACCESS_DENIED");
  long tenant=Long.parseLong(f.get("tenantId").toString()),user=Long.parseLong(f.get("userId").toString());
  if(db.queryForObject("SELECT COUNT(*) FROM asset_membership m JOIN asset_company c ON c.id=m.company_id AND c.tenant_id=m.tenant_id WHERE m.tenant_id=? AND m.user_id=? AND m.company_id=? AND m.state='ACTIVE' AND c.status='ENABLED'",Long.class,tenant,user,companyId)!=1)throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
  return Map.of("tenantId",Long.toString(tenant),"companyId",Long.toString(companyId),"userId",Long.toString(user),"active",true,"administrator",MembershipRoleService.administrator(db,tenant,companyId,user),"checkedAt",Instant.now().toString());
 }
}

