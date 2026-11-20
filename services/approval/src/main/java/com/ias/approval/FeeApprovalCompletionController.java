package com.ias.approval;

import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import javax.sql.DataSource;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.sql.*;
import java.time.Instant;
import java.util.*;

@RestController
public final class FeeApprovalCompletionController {
    private final DataSource source;
    public FeeApprovalCompletionController(DataSource source){this.source=source;}
    @PostMapping("/internal/approval/fee-completions/query")public Map<String,Object> query(@RequestBody Map<String,Object> body,HttpServletRequest request){
        edge(request);
        if(!body.keySet().equals(Set.of("tenantId","companyId","groupId","completionId")))throw new ApprovalFailure(400,"INVALID_REQUEST");
        String tenant=positive(body,"tenantId"),company=positive(body,"companyId");UUID group=id(body,"groupId"),completion=id(body,"completionId");
        try(var c=source.getConnection()){
            var payload=FeeApprovalPersistence.object(c,"SELECT payload FROM fee_approval_completion WHERE tenant_id=? AND completion_id=?",tenant,completion);
            if(payload==null||!company.equals(payload.get("companyId"))||!group.toString().equals(payload.get("groupId"))||!"COMPLETED".equals(payload.get("state")))throw new ApprovalFailure(404,"FEE_COMPLETION_NOT_FOUND");
            var out=new LinkedHashMap<>(payload);out.put("checkedAt",Instant.now().toString());return out;
        }catch(SQLException e){throw new ApprovalFailure(503,"DEPENDENCY_UNAVAILABLE");}
    }
    @PostMapping("/internal/approval/fee-completions/group")public Map<String,Object> group(@RequestBody Map<String,Object> body,HttpServletRequest request){
        edge(request);if(!body.keySet().equals(Set.of("tenantId","companyId","groupId")))throw new ApprovalFailure(400,"INVALID_REQUEST");
        String tenant=positive(body,"tenantId"),company=positive(body,"companyId");UUID group=id(body,"groupId");
        try(var c=source.getConnection();var p=c.prepareStatement("SELECT payload FROM fee_approval_completion WHERE tenant_id=? AND company_id=? AND group_id=? ORDER BY completion_id LIMIT 1001")){
            FeeApprovalPersistence.bind(p,tenant,company,group);var completions=new ArrayList<Object>();try(var r=p.executeQuery()){while(r.next()){if(completions.size()==1000)throw new ApprovalFailure(503,"FEE_COMPLETION_GROUP_LIMIT");var fact=FeeApprovalPersistence.map(r.getString(1));if(!tenant.equals(fact.get("tenantId"))||!company.equals(fact.get("companyId"))||!group.toString().equals(fact.get("groupId"))||!"COMPLETED".equals(fact.get("state")))throw new ApprovalFailure(503,"FEE_COMPLETION_FACT_INCONSISTENT");completions.add(fact);}}
            return Map.of("tenantId",tenant,"companyId",company,"groupId",group.toString(),"completions",completions,"checkedAt",Instant.now().toString());
        }catch(SQLException e){throw new ApprovalFailure(503,"DEPENDENCY_UNAVAILABLE");}
    }
    private static void edge(HttpServletRequest request){if(!Chain.active()||!request.isSecure()||!"approval".equals(Chain.role())||!Set.of("finance-approval","ticket-approval","stock-approval").contains(Objects.toString(request.getAttribute("ias.chain.edge"),"")))throw new ApprovalFailure(403,"SERVICE_AUTHENTICATION_FAILED");}
    private static UUID id(Map<String,Object>b,String k){Object v=b.get(k);if(!(v instanceof String s))throw new ApprovalFailure(400,"INVALID_REQUEST");return FeeApprovalService.id(s);}
    private static String positive(Map<String,Object>b,String k){Object v=b.get(k);if(!(v instanceof String s)||!s.matches("[1-9][0-9]{0,18}"))throw new ApprovalFailure(400,"INVALID_REQUEST");try{Long.parseLong(s);return s;}catch(Exception e){throw new ApprovalFailure(400,"INVALID_REQUEST");}}
    @ExceptionHandler(ApprovalFailure.class)ResponseEntity<Map<String,Object>> failure(ApprovalFailure e){return ResponseEntity.status(e.status()).header("Cache-Control","no-store").body(Map.of("code",e.getMessage(),"retryable",e.status()==503));}
}
