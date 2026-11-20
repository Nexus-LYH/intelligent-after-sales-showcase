package com.ias.identity.auth;
import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;
/** Trusted ticket owner rechecks current engineer facts; this never grants a warehouse action. */
@RestController
public final class StockEngineerQualificationController {
 private final JdbcTemplate db;
 public StockEngineerQualificationController(JdbcTemplate db){this.db=db;}
 @PostMapping("/internal/identity/stock-engineer-qualification")
 public Map<String,Object> qualification(@RequestBody Map<String,Object> body,HttpServletRequest request){
  if(!Chain.active()||!request.isSecure()||!"ticket-identity".equals(request.getAttribute("ias.chain.edge")))throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");
  if(!body.keySet().equals(Set.of("tenantId","userId","departmentId")))throw new Problem(400,"VALIDATION_ERROR");
  return facts(db,positive(body.get("tenantId")),positive(body.get("userId")),positive(body.get("departmentId")));
 }
 static long positive(Object value){if(!(value instanceof String s)||!s.matches("[1-9][0-9]{0,18}"))throw new Problem(400,"VALIDATION_ERROR");try{return Long.parseLong(s);}catch(Exception e){throw new Problem(400,"VALIDATION_ERROR");}}
 static Map<String,Object> facts(JdbcTemplate db,long tenant,long user,long department){
  long count=db.queryForObject("SELECT COUNT(*) FROM identity_user u JOIN tenant t ON t.id=u.tenant_id JOIN identity_department d ON d.tenant_id=u.tenant_id AND d.id=u.department_id WHERE u.tenant_id=? AND u.id=? AND u.department_id=? AND u.identity_type='EMPLOYEE' AND u.status=1 AND u.must_change_password=0 AND t.status=1 AND d.enabled=1 AND (SELECT COUNT(*) FROM identity_user_capability c WHERE c.user_id=u.id AND c.enabled=1 AND c.capability_code IN('ticket.engineer.read','ticket.record','ticket.assignment.accept'))=3",Long.class,tenant,user,department);
  return Map.of("tenantId",Long.toString(tenant),"userId",Long.toString(user),"departmentId",Long.toString(department),"eligible",count==1,"checkedAt",Instant.now().toString());
 }
}
