package com.ias.identity.auth;
import com.ias.trust.*;import jakarta.servlet.http.HttpServletRequest;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.web.bind.annotation.*;import java.time.Instant;import java.util.*;
/** Read-only exact owner facts for administrator liveness, never a session/object authorization grant. */
@RestController
public final class MembershipRoleEligibilityController {
 private final JdbcTemplate db;public MembershipRoleEligibilityController(JdbcTemplate db){this.db=db;}
 @PostMapping("/internal/identity/customer-eligibility") public Map<String,Object> eligible(@RequestBody Map<String,Object> body,HttpServletRequest request){
  if(!Chain.active()||!request.isSecure()||!"asset-identity".equals(request.getAttribute("ias.chain.edge")))throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");
  if(!body.keySet().equals(Set.of("tenantId","userIds"))||!(body.get("userIds") instanceof List<?> values)||values.isEmpty()||values.size()>100)throw new Problem(400,"VALIDATION_ERROR");long tenant=StockEngineerQualificationController.positive(body.get("tenantId"));var users=new TreeSet<Long>();for(Object value:values)if(!users.add(StockEngineerQualificationController.positive(value)))throw new Problem(400,"VALIDATION_ERROR");return facts(db,tenant,users);
 }
 static Map<String,Object> facts(JdbcTemplate db,long tenant,SortedSet<Long> users){var args=new ArrayList<Object>();args.add(tenant);args.addAll(users);var eligible=db.queryForList("SELECT u.id FROM identity_user u JOIN tenant t ON t.id=u.tenant_id WHERE u.tenant_id=? AND u.id IN("+String.join(",",Collections.nCopies(users.size(),"?"))+") AND u.identity_type='CUSTOMER' AND u.status=1 AND u.must_change_password=0 AND t.status=1 ORDER BY u.id",Long.class,args.toArray());return Map.of("tenantId",Long.toString(tenant),"userIds",users.stream().map(Object::toString).toList(),"eligibleUserIds",eligible.stream().map(Object::toString).toList(),"checkedAt",Instant.now().toString());}
}
