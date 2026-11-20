package com.ias.approval;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.Instant;
import java.util.*;
import static com.ias.approval.PartsApproval.*;
import static org.junit.jupiter.api.Assertions.*;

class TrustedApprovalAccessTest {
    final ApprovalAccess.Principal actor=new ApprovalAccess.Principal("1","2");
    final Submission command=new Submission(UUID.randomUUID(),"1","2",UUID.randomUUID().toString(),UUID.randomUUID().toString(),"5","11","101",1,"repair purpose");
    MockHttpServletRequest request(){var r=new MockHttpServletRequest();r.addHeader("Cookie","IAS-SESSION-DEV=synthetic-session-only");r.addHeader("Origin","http://127.0.0.1:5173");r.addHeader("X-CSRF-TOKEN","synthetic-csrf-only");return r;}
    Map<String,Object> fresh(Map<String,Object> value){var result=new HashMap<>(value);result.put("checkedAt",Instant.now().toString());return result;}
    Map<String,Object> answer(String path){
        if(path.endsWith("domain-authorizations"))return fresh(Map.of("tenantId","1","userId","2","enabled",true,"identityType","EMPLOYEE"));
        if(path.endsWith("approval-context"))return fresh(Map.of("tenantId","1","ticketId",command.ticketId(),"roundId","00000000-0000-0000-0000-000000000001","assignmentId",command.assignmentId(),"departmentId","5","engineerUserId","2","companyId","9","status","IN_PROGRESS"));
        if(path.endsWith("approval-check"))return fresh(Map.of("tenantId","1","userId","2","warehouseId","11","partId","101","warehouseActive",true,"partActive",true));
        if(path.endsWith("approval-recipients"))return fresh(Map.of("reviewerUserIds",List.of("3"),"applicantEligible",true));
        if(path.endsWith("register"))return Map.of("operationId",command.operationId().toString(),"referenceState","REGISTERED_INTENT","recordedAt",Instant.now().toString(),"tenantId","1","ticketId",command.ticketId(),"roundId","00000000-0000-0000-0000-000000000001","assignmentId",command.assignmentId());
        throw new AssertionError(path);
    }
    @Test void currentIdentityTicketAndStockMustAllMatchBeforeSubmission(){
        var calls=new ArrayList<String>();var adapter=new TrustedApprovalAccess((edge,path,body,r)->{calls.add(edge);return answer(path);});
        assertTrue(adapter.submission(request(),actor,command).qualifiedSupervisorExists());
        assertEquals(List.of("approval-identity","approval-ticket","approval-stock","approval-identity"),calls);
        for(String field:List.of("tenantId","userId","warehouseId","partId")){
            var denied=new TrustedApprovalAccess((edge,path,body,r)->{var f=new HashMap<>(answer(path));if(edge.equals("approval-stock"))f.put(field,"999");return f;});
            assertThrows(ApprovalFailure.class,()->denied.submission(request(),actor,command));
        }
    }
    @Test void absentSupervisorWaitsForConfigurationButMalformedOrStaleFactsNeverGrant(){
        var absent=new TrustedApprovalAccess((edge,path,body,r)->path.endsWith("approval-recipients")?fresh(Map.of("reviewerUserIds",List.of(),"applicantEligible",true)):answer(path));
        assertFalse(absent.submission(request(),actor,command).qualifiedSupervisorExists());
        var stale=new TrustedApprovalAccess((edge,path,body,r)->{var f=new HashMap<>(answer(path));if(edge.equals("approval-ticket"))f.put("checkedAt",Instant.now().minusSeconds(3).toString());return f;});
        assertEquals(503,assertThrows(ApprovalFailure.class,()->stale.submission(request(),actor,command)).status());
        var wrongIdentity=new TrustedApprovalAccess((edge,path,body,r)->{var f=new HashMap<>(answer(path));if(path.endsWith("domain-authorizations"))f.put("userId","999");return f;});
        assertEquals(401,assertThrows(ApprovalFailure.class,()->wrongIdentity.submission(request(),actor,command)).status());
    }
    @Test void csrfOriginAndSessionAreRequiredBeforeDependencyCalls(){
        var adapter=new TrustedApprovalAccess((edge,path,body,r)->{throw new AssertionError("must reject before network");});
        var noCookie=request();noCookie.removeHeader("Cookie");assertEquals(401,assertThrows(ApprovalFailure.class,()->adapter.submission(noCookie,actor,command)).status());
        var badOrigin=request();badOrigin.removeHeader("Origin");badOrigin.addHeader("Origin","https://untrusted.invalid");assertEquals(403,assertThrows(ApprovalFailure.class,()->adapter.submission(badOrigin,actor,command)).status());
        var noCsrf=request();noCsrf.removeHeader("X-CSRF-TOKEN");assertEquals(403,assertThrows(ApprovalFailure.class,()->adapter.submission(noCsrf,actor,command)).status());
    }
    @Test void sourceRegistrationCarriesFullDigestAndRejectsMismatchedReceipt(){
        String digest="a".repeat(64);var adapter=new TrustedApprovalAccess((edge,path,body,r)->{
            if(path.endsWith("register")){assertEquals(digest,body.get("commandDigest"));assertEquals(command.operationId().toString(),body.get("operationId"));}
            return answer(path);
        });adapter.register(request(),actor,command,digest);
        var wrong=new TrustedApprovalAccess((edge,path,body,r)->{var f=new HashMap<>(answer(path));if(path.endsWith("register"))f.put("assignmentId",UUID.randomUUID().toString());return f;});
        assertEquals(503,assertThrows(ApprovalFailure.class,()->wrong.register(request(),actor,command,digest)).status());
    }
    @Test void currentActionProjectionRechecksReviewerButHistoricalReceiptNeverGrantsActions(){
        var application=PartsApproval.submit(UUID.randomUUID(),command,new SubmissionAuthority("1","2",command.ticketId(),command.assignmentId(),"5","11",true,true,true,true),null,Instant.now());
        var adapter=new TrustedApprovalAccess((edge,path,body,r)->{
            var f=new HashMap<>(answer(path));if(path.endsWith("domain-authorizations"))f.put("userId","3");return f;
        });
        assertEquals(List.of("approve","reject"),adapter.actions(request(),new ApprovalAccess.Principal("1","3"),application));
        assertTrue(ApprovalView.of(application).allowedActions().isEmpty());
        var revoked=new TrustedApprovalAccess((edge,path,body,r)->{throw new ApprovalFailure(403,"NOT_AUTHORIZED");});
        assertTrue(revoked.actions(request(),new ApprovalAccess.Principal("1","3"),application).isEmpty());
        var down=new TrustedApprovalAccess((edge,path,body,r)->{throw new ApprovalFailure(503,"DEPENDENCY_UNAVAILABLE");});
        assertEquals(503,assertThrows(ApprovalFailure.class,()->down.actions(request(),new ApprovalAccess.Principal("1","3"),application)).status());
        var changedAssignment=new TrustedApprovalAccess((edge,path,body,r)->{
            var f=new HashMap<>(answer(path));if(path.endsWith("domain-authorizations"))f.put("userId","3");
            if(path.endsWith("approval-context")){assertEquals(command.assignmentId(),body.get("assignmentId"));f.put("assignmentId",UUID.randomUUID().toString());}return f;
        });
        assertTrue(changedAssignment.actions(request(),new ApprovalAccess.Principal("1","3"),application).isEmpty());
        assertEquals(409,assertThrows(ApprovalFailure.class,()->changedAssignment.decision(request(),new ApprovalAccess.Principal("1","3"),application)).status());
    }
}
