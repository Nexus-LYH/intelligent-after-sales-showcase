package com.ias.identity.auth;

import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

@RestController
public final class DomainAuthorizationController {
    private final AuthService auth;private final JdbcTemplate db;private final TicketAuthorizationController ticket;
    public DomainAuthorizationController(AuthService auth,JdbcTemplate db,TicketAuthorizationController ticket){this.auth=auth;this.db=db;this.ticket=ticket;}
    @PostMapping("/internal/identity/domain-authorizations")
    public Map<String,Object> authorize(@RequestBody Map<String,Object>b,HttpServletRequest r){
        if(!Set.of("sessionToken","purpose","csrfToken").containsAll(b.keySet())||!b.keySet().containsAll(Set.of("sessionToken","purpose")))throw new Problem(400,"VALIDATION_ERROR");
        String purpose=text(b,"purpose");if(!DomainPolicy.PURPOSES.contains(purpose))throw new Problem(400,"VALIDATION_ERROR");
        String csrf=(String)b.get("csrfToken");String introspectPurpose=csrf==null?switch(purpose){case "APPROVAL_SUBMIT","APPROVAL_REVIEW"->"APPROVAL_READ";case "FEE_SUBMIT","FEE_REVIEW","FEE_FINANCE","FEE_RECOVER"->"FEE_READ";case "MONEY_WRITE"->"MONEY_READ";case "STOCK_MANAGE"->"STOCK_READ";case "NOTIFICATION_WRITE"->"NOTIFICATION_READ";case "FINANCE_WRITE"->"FINANCE_READ";default->purpose;}:purpose;
        var identity=auth.introspect(text(b,"sessionToken"),introspectPurpose,csrf,Chain.nanoDeadline(Chain.deadline(r)));
        if(!DomainPolicy.allowed(identity.get("identityType").toString(),(List<String>)identity.get("permissions"),purpose))throw new Problem(403,"ACCESS_DENIED");
        long tenant=TicketAuthorizationController.id(identity.get("tenantId")),user=TicketAuthorizationController.id(identity.get("userId"));
        var out=new LinkedHashMap<String,Object>();out.putAll(identity);
        if("EMPLOYEE".equals(identity.get("identityType"))){
            var facts=ticket.facts(tenant,user);
            if(!Boolean.TRUE.equals(facts.get("enabled"))||!Objects.equals(identity.get("securityVersion"),facts.get("securityVersion"))||!Objects.equals(identity.get("securityEpoch"),facts.get("securityEpoch")))throw new Problem(401,"SESSION_INVALID");
            if(!purpose.startsWith("NOTIFICATION_")&&!Boolean.TRUE.equals(facts.get("departmentEnabled")))throw new Problem(403,"ACCESS_DENIED");
            for(String k:List.of("enabled","departmentId","departmentCode","departmentEnabled","companyIds"))out.put(k,facts.get(k));
            out.put("approvalReviewDepartmentIds",db.queryForList("SELECT s.department_id FROM identity_approval_department_scope s JOIN identity_department d ON d.id=s.department_id AND d.tenant_id=s.tenant_id WHERE s.tenant_id=? AND s.user_id=? AND s.enabled=1 AND d.enabled=1 ORDER BY s.department_id",Long.class,tenant,user).stream().map(Object::toString).toList());
            out.put("warehouseIds",db.queryForList("SELECT warehouse_id FROM identity_warehouse_scope WHERE tenant_id=? AND user_id=? AND enabled=1 ORDER BY warehouse_id",Long.class,tenant,user).stream().map(Object::toString).toList());
        }else{out.put("enabled",true);out.put("departmentId",null);out.put("departmentCode",null);out.put("departmentEnabled",false);out.put("companyIds",List.of());out.put("approvalReviewDepartmentIds",List.of());out.put("warehouseIds",List.of());}
        out.put("checkedAt",Instant.now().toString());return out;
    }
    @PostMapping("/internal/identity/approval-recipients")
    public Map<String,Object> recipients(@RequestBody Map<String,Object>b){
        if(!b.keySet().equals(Set.of("tenantId","companyId","departmentId","applicantUserId")))throw new Problem(400,"VALIDATION_ERROR");
        long tenant=id(b,"tenantId"),company=id(b,"companyId"),department=id(b,"departmentId"),applicant=id(b,"applicantUserId");
        var users=db.queryForList("SELECT u.id FROM identity_user u JOIN tenant t ON t.id=u.tenant_id JOIN identity_department d ON d.id=u.department_id AND d.tenant_id=u.tenant_id JOIN identity_approval_department_scope a ON a.user_id=u.id AND a.tenant_id=u.tenant_id JOIN identity_department src ON src.id=a.department_id AND src.tenant_id=a.tenant_id JOIN identity_employee_company_scope c ON c.user_id=u.id AND c.tenant_id=u.tenant_id WHERE u.tenant_id=? AND t.status=1 AND u.identity_type='EMPLOYEE' AND u.status=1 AND u.must_change_password=0 AND d.enabled=1 AND src.enabled=1 AND a.enabled=1 AND a.department_id=? AND c.enabled=1 AND c.company_id=? AND u.id<>? AND (SELECT COUNT(*) FROM identity_user_capability p WHERE p.user_id=u.id AND p.enabled=1 AND p.capability_code IN('approval.parts.read','approval.parts.review','ticket.read'))=3 ORDER BY u.id",Long.class,tenant,department,company,applicant);
        boolean applicantEligible=db.queryForObject("SELECT COUNT(*) FROM identity_user u JOIN tenant t ON t.id=u.tenant_id JOIN identity_department d ON d.id=u.department_id AND d.tenant_id=u.tenant_id WHERE u.id=? AND u.tenant_id=? AND u.identity_type='EMPLOYEE' AND u.status=1 AND u.must_change_password=0 AND t.status=1 AND d.enabled=1",Long.class,applicant,tenant)==1;
        return Map.of("tenantId",Long.toString(tenant),"reviewerUserIds",users.stream().map(Object::toString).toList(),"applicantEligible",applicantEligible,"checkedAt",Instant.now().toString());
    }
    private static long id(Map<String,Object>b,String key){String value=text(b,key);if(!value.matches("[1-9][0-9]{0,18}"))throw new Problem(400,"VALIDATION_ERROR");return TicketAuthorizationController.id(value);}
    private static String text(Map<String,Object>b,String key){Object o=b.get(key);if(!(o instanceof String s)||s.isBlank())throw new Problem(400,"VALIDATION_ERROR");return s;}
}


