package com.ias.approval;

import com.ias.trust.Chain;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public final class ApprovalInternalController {
    private final ApprovalQueries queries;private final ApprovalAccess access;
    public ApprovalInternalController(ApprovalQueries queries,ApprovalAccess access){this.queries=queries;this.access=access;}
    @PostMapping("/internal/approval/notification-context") public Map<String,Object> context(@RequestBody Map<String,Object> body,HttpServletRequest request){
        if(!Chain.active()||!request.isSecure()||!"notification-approval".equals(request.getAttribute("ias.chain.edge")))throw new ApprovalFailure(403,"SERVICE_AUTHENTICATION_FAILED");
        String purpose=Objects.toString(body.get("purpose"),"");var required=Set.of("eventId","tenantId","approvalId","eventType","aggregateVersion","purpose");var allowed=new HashSet<>(required);if(purpose.equals("READ"))allowed.add("sessionToken");
        if(!Set.of("DELIVERY","READ").contains(purpose)||!body.keySet().equals(allowed))throw new ApprovalFailure(400,"INVALID_REQUEST");
        String tenant=string(body,"tenantId"),version=string(body,"aggregateVersion");if(!tenant.matches("[1-9][0-9]{0,18}")||!version.matches("0|[1-9][0-9]{0,18}"))throw new ApprovalFailure(400,"INVALID_REQUEST");
        var fact=queries.fact(uuid(body,"eventId"),tenant,uuid(body,"approvalId"),string(body,"eventType"),version);
        if(purpose.equals("READ")){
            if(!(access instanceof TrustedApprovalAccess trusted))throw new ApprovalFailure(503,"APPROVAL_TRUST_NOT_CONFIGURED");
            trusted.readAsSession(request,string(body,"sessionToken"),fact.application());
        }
        return queries.context(fact);
    }
    static String string(Map<String,Object> body,String field){if(!(body.get(field) instanceof String value)||value.isBlank())throw new ApprovalFailure(400,"INVALID_REQUEST");return value;}
    static UUID uuid(Map<String,Object> body,String field){String value=string(body,field);try{var id=UUID.fromString(value);if(!id.toString().equals(value))throw new Exception();return id;}catch(Exception e){throw new ApprovalFailure(400,"INVALID_REQUEST");}}
    @ExceptionHandler(ApprovalFailure.class) org.springframework.http.ResponseEntity<Map<String,Object>> failure(ApprovalFailure failure){return org.springframework.http.ResponseEntity.status(failure.status()).header("Cache-Control","no-store").body(Map.of("code",failure.getMessage(),"retryable",failure.status()==503,"traceId",UUID.randomUUID().toString()));}
}
