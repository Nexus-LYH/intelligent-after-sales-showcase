package com.ias.identity.auth;
import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

/** Current scoped qualifications, never role names or a browser-supplied reviewer identity. */
@RestController
public final class FeeRecipientsController {
 private final JdbcTemplate db;
 public FeeRecipientsController(JdbcTemplate db){this.db=db;}
 @PostMapping("/internal/identity/fee-recipients")
 public Map<String,Object> recipients(@RequestBody Map<String,Object> body,HttpServletRequest r){
  if(!Chain.active()||!r.isSecure()||!Set.of("approval-identity","notification-identity").contains(Objects.toString(r.getAttribute("ias.chain.edge"),"")))throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");
  if(!body.keySet().equals(Set.of("tenantId","companyId","departmentId","applicantUserId")))throw new Problem(400,"VALIDATION_ERROR");
  long tenant=id(body,"tenantId"),company=id(body,"companyId"),department=id(body,"departmentId"),applicant=id(body,"applicantUserId");
  return current(db,tenant,company,department,applicant);
 }
 static Map<String,Object> current(JdbcTemplate db,long tenant,long company,long department,long applicant){
  String qualified="SELECT DISTINCT u.id FROM identity_user u JOIN tenant t ON t.id=u.tenant_id JOIN identity_department d ON d.id=u.department_id AND d.tenant_id=u.tenant_id JOIN identity_approval_department_scope a ON a.user_id=u.id AND a.tenant_id=u.tenant_id JOIN identity_department src ON src.id=a.department_id AND src.tenant_id=a.tenant_id JOIN identity_employee_company_scope c ON c.user_id=u.id AND c.tenant_id=u.tenant_id WHERE u.tenant_id=? AND t.status=1 AND u.identity_type='EMPLOYEE' AND u.status=1 AND u.must_change_password=0 AND d.enabled=1 AND src.enabled=1 AND a.enabled=1 AND a.department_id=? AND c.enabled=1 AND c.company_id=? AND u.id<>? AND (SELECT COUNT(*) FROM identity_user_capability p WHERE p.user_id=u.id AND p.enabled=1 AND p.capability_code IN('approval.fees.read',?))=2 ORDER BY u.id";
  var supervisor=db.queryForList(qualified,Long.class,tenant,department,company,applicant,"approval.fees.review");
  var finance=db.queryForList(qualified,Long.class,tenant,department,company,applicant,"approval.fees.finance");
  boolean eligible=db.queryForObject("SELECT COUNT(*) FROM identity_user u JOIN tenant t ON t.id=u.tenant_id JOIN identity_department d ON d.id=u.department_id AND d.tenant_id=u.tenant_id WHERE u.id=? AND u.tenant_id=? AND u.identity_type='EMPLOYEE' AND u.status=1 AND u.must_change_password=0 AND t.status=1 AND d.enabled=1 AND d.id=? AND EXISTS(SELECT 1 FROM identity_employee_company_scope c WHERE c.user_id=u.id AND c.tenant_id=u.tenant_id AND c.company_id=? AND c.enabled=1) AND (SELECT COUNT(*) FROM identity_user_capability p WHERE p.user_id=u.id AND p.enabled=1 AND p.capability_code IN('approval.fees.read','approval.fees.submit','ticket.engineer.read','ticket.record'))=4",Long.class,applicant,tenant,department,company)==1;
  return Map.of("tenantId",Long.toString(tenant),"companyId",Long.toString(company),"departmentId",Long.toString(department),"applicantUserId",Long.toString(applicant),"supervisorUserIds",supervisor.stream().map(Object::toString).toList(),"financeUserIds",finance.stream().map(Object::toString).toList(),"applicantEligible",eligible,"checkedAt",Instant.now().toString());
 }
 private static long id(Map<String,Object>b,String k){if(!(b.get(k) instanceof String s)||!s.matches("[1-9][0-9]{0,18}"))throw new Problem(400,"VALIDATION_ERROR");try{return Long.parseLong(s);}catch(Exception e){throw new Problem(400,"VALIDATION_ERROR");}}
}
