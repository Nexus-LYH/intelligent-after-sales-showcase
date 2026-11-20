package com.ias.approval;

import com.ias.trust.Chain;
import com.ias.trust.Problem;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import static com.ias.approval.PartsApproval.*;

/** PN-1 online adapters. Claims from browser JSON or identity-looking headers are never authority. */
public final class TrustedApprovalAccess implements ApprovalAccess {
    @FunctionalInterface interface Calls { Map<String,Object> call(String edge,String path,Map<String,Object> body,HttpServletRequest request); }
    private final Calls calls;
    public TrustedApprovalAccess(){this((edge,path,body,r)->{
        try{return Chain.call(edge,path,body,Chain.trace(r),Chain.deadline(r));}
        catch(Problem failure){throw new ApprovalFailure(failure.status,failure.code);}
    });}
    TrustedApprovalAccess(Calls calls){this.calls=calls;}
    private static String token(HttpServletRequest r){String token=Chain.cookie(r);if(token==null||token.isBlank())throw new ApprovalFailure(401,"SESSION_INVALID");return token;}
    private Map<String,Object> call(String edge,String path,Map<String,Object> body,HttpServletRequest r){
        var result=calls.call(edge,path,body,r);
        try{Chain.fresh(result,Chain.deadline(r));}catch(Problem failure){throw new ApprovalFailure(failure.status,failure.code);}
        return result;
    }
    private Map<String,Object> identity(HttpServletRequest r,String purpose,boolean write){
        var body=new LinkedHashMap<String,Object>();body.put("sessionToken",token(r));body.put("purpose",purpose);
        if(write){try{Chain.source(r);body.put("csrfToken",Chain.single(r,"X-CSRF-TOKEN"));}catch(Problem failure){throw new ApprovalFailure(failure.status,failure.code);}}
        var f=call("approval-identity","/internal/identity/domain-authorizations",body,r);
        if(!Boolean.TRUE.equals(f.get("enabled"))||!"EMPLOYEE".equals(f.get("identityType")))throw new ApprovalFailure(403,"NOT_AUTHORIZED");
        return f;
    }
    private void match(Principal p,Map<String,Object> f){if(!p.tenantId().equals(f.get("tenantId"))||!p.actorId().equals(f.get("userId")))throw new ApprovalFailure(401,"SESSION_INVALID");}
    private Map<String,Object> context(HttpServletRequest r,Principal p,Submission s,String purpose){
        var body=new LinkedHashMap<String,Object>();body.put("sessionToken",token(r));body.put("ticketId",s.ticketId());body.put("purpose",purpose);
        if(Set.of("SUBMIT","CATALOG","REVIEW").contains(purpose))body.put("assignmentId",s.assignmentId());
        var f=call("approval-ticket","/internal/ticket/approval-context",body,r);
        if(!p.tenantId().equals(f.get("tenantId"))||!s.ticketId().equals(f.get("ticketId")))throw new ApprovalFailure(404,"TARGET_NOT_ACCESSIBLE");
        return f;
    }
    @Override public Principal principal(HttpServletRequest r){
        if(!Chain.active()||!r.isSecure()||!"approval".equals(Chain.role())||!"gateway-approval".equals(r.getAttribute("ias.chain.edge")))throw new ApprovalFailure(403,"SERVICE_AUTHENTICATION_FAILED");
        var f=identity(r,"APPROVAL_READ",false);return new Principal((String)f.get("tenantId"),(String)f.get("userId"));
    }
    @Override public SubmissionAuthority submission(HttpServletRequest r,Principal p,Submission s){
        match(p,identity(r,"APPROVAL_SUBMIT",true));var f=context(r,p,s,"SUBMIT");
        if(!s.assignmentId().equals(f.get("assignmentId"))||!s.departmentId().equals(f.get("departmentId"))||!p.actorId().equals(f.get("engineerUserId"))||!"IN_PROGRESS".equals(f.get("status")))throw new ApprovalFailure(409,"TICKET_NOT_APPROVABLE");
        var stock=call("approval-stock","/internal/stock/approval-check",Map.of("sessionToken",token(r),"warehouseId",s.warehouseId(),"partId",s.partId()),r);
        match(p,stock);
        if(!s.warehouseId().equals(stock.get("warehouseId"))||!s.partId().equals(stock.get("partId"))||!Boolean.TRUE.equals(stock.get("warehouseActive"))||!Boolean.TRUE.equals(stock.get("partActive")))throw new ApprovalFailure(404,"TARGET_NOT_ACCESSIBLE");
        var recipients=call("approval-identity","/internal/identity/approval-recipients",Map.of("tenantId",p.tenantId(),"companyId",f.get("companyId"),"departmentId",s.departmentId(),"applicantUserId",p.actorId()),r);
        if(!(recipients.get("reviewerUserIds") instanceof List<?> reviewers)||!Boolean.TRUE.equals(recipients.get("applicantEligible")))throw new ApprovalFailure(503,"DEPENDENCY_UNAVAILABLE");
        return new SubmissionAuthority(p.tenantId(),p.actorId(),s.ticketId(),s.assignmentId(),s.departmentId(),s.warehouseId(),true,true,true,!reviewers.isEmpty());
    }
    @Override public void register(HttpServletRequest r,Principal p,Submission s,String digest){
        registerReference(r,p,s,digest);
    }
    @Override public SourceReference registerReference(HttpServletRequest r,Principal p,Submission s,String digest){
        var f=context(r,p,s,"SUBMIT");
        var body=new LinkedHashMap<String,Object>();body.put("sessionToken",token(r));body.put("csrfToken",r.getHeader("X-CSRF-TOKEN"));
        body.put("ticketId",s.ticketId());body.put("roundId",f.get("roundId"));body.put("assignmentId",s.assignmentId());body.put("operationId",s.operationId().toString());body.put("commandDigest",digest);
        var result=calls.call("approval-ticket","/internal/ticket/approval-references/register",body,r);
        if(!s.operationId().toString().equals(result.get("operationId"))||!"REGISTERED_INTENT".equals(result.get("referenceState"))
                ||!p.tenantId().equals(result.get("tenantId"))||!s.ticketId().equals(result.get("ticketId"))
                ||!s.assignmentId().equals(result.get("assignmentId"))||!Objects.equals(f.get("roundId"),result.get("roundId")))throw new ApprovalFailure(503,"OPERATION_RESULT_UNKNOWN");
        if(!(result.get("recordedAt") instanceof String at)||!(f.get("companyId") instanceof String company)||!company.matches("[1-9][0-9]{0,18}"))throw new ApprovalFailure(503,"OPERATION_RESULT_UNKNOWN");
        try{java.time.Instant.parse(at);UUID.fromString((String)f.get("roundId"));Long.parseLong(company);}catch(Exception failure){throw new ApprovalFailure(503,"OPERATION_RESULT_UNKNOWN");}
        return new SourceReference((String)f.get("roundId"),company,at);
    }
    @Override public void ticketRead(HttpServletRequest r,Principal p,String ticket){
        match(p,identity(r,"APPROVAL_READ",false));
        var f=call("approval-ticket","/internal/ticket/approval-context",Map.of("sessionToken",token(r),"ticketId",ticket,"purpose","READ"),r);
        if(!p.tenantId().equals(f.get("tenantId"))||!ticket.equals(f.get("ticketId")))throw new ApprovalFailure(404,"TARGET_NOT_ACCESSIBLE");
    }
    public void readAsSession(HttpServletRequest request,String session,Application application){
        if(session==null||session.isBlank()||!session.matches("[A-Za-z0-9_-]{1,512}"))throw new ApprovalFailure(401,"SESSION_INVALID");
        var wrapped=new jakarta.servlet.http.HttpServletRequestWrapper(request){
            @Override public java.util.Enumeration<String> getHeaders(String name){return name.equalsIgnoreCase("Cookie")?Collections.enumeration(List.of("IAS-SESSION-DEV="+session)):super.getHeaders(name);}
        };
        var f=identity(wrapped,"APPROVAL_READ",false);read(wrapped,new Principal((String)f.get("tenantId"),(String)f.get("userId")),application);
    }
    @Override public DecisionAuthority decision(HttpServletRequest r,Principal p,Application a){
        match(p,identity(r,"APPROVAL_REVIEW",true));var f=context(r,p,a.submission(),"REVIEW");
        if(!a.submission().assignmentId().equals(f.get("assignmentId")))throw new ApprovalFailure(409,"ASSIGNMENT_CONFLICT");
        return new DecisionAuthority(p.tenantId(),p.actorId(),Objects.toString(f.get("departmentId"),""),true,true,true,Set.of("IN_PROGRESS","WAITING_INFORMATION").contains(f.get("status")));
    }
    @Override public WithdrawalAuthority withdrawal(HttpServletRequest r,Principal p,Application a){
        match(p,identity(r,"APPROVAL_READ",true));context(r,p,a.submission(),"WITHDRAW");
        return new WithdrawalAuthority(p.tenantId(),p.actorId(),true,true);
    }
    @Override public void read(HttpServletRequest r,Principal p,Application a){
        match(p,identity(r,"APPROVAL_READ",false));context(r,p,a.submission(),"READ");
    }
    private boolean currently(Runnable check){
        try{check.run();return true;}catch(ApprovalFailure failure){if(failure.status()==403||failure.status()==404)return false;throw failure;}
    }
    @Override public List<String> actions(HttpServletRequest r,Principal p,Application a){
        var actions=new ArrayList<String>();boolean applicant=p.actorId().equals(a.submission().applicantId());
        if(applicant&&(a.state()==State.PENDING||a.state()==State.WAITING_CONFIGURATION)
                &&currently(()->context(r,p,a.submission(),"WITHDRAW")))actions.add("withdraw");
        if(!applicant&&a.state()==State.PENDING&&currently(()->{
            match(p,identity(r,"APPROVAL_REVIEW",false));var f=context(r,p,a.submission(),"REVIEW");
            if(!a.submission().assignmentId().equals(f.get("assignmentId"))||!a.submission().departmentId().equals(f.get("departmentId"))||!Set.of("IN_PROGRESS","WAITING_INFORMATION").contains(f.get("status")))throw new ApprovalFailure(403,"NOT_AUTHORIZED");
        })){actions.add("approve");actions.add("reject");}
        if(applicant&&(a.state()==State.REJECTED||a.state()==State.WITHDRAWN)&&currently(()->{
            match(p,identity(r,"APPROVAL_SUBMIT",false));context(r,p,a.submission(),"SUBMIT");
        }))actions.add("revise");
        return List.copyOf(actions);
    }
}
