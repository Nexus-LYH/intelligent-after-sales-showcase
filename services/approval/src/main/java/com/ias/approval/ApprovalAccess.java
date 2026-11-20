package com.ias.approval;

import jakarta.servlet.http.HttpServletRequest;
import static com.ias.approval.PartsApproval.*;

/** Implement with the frozen trusted edge and online identity/ticket authorization; never bind from JSON. */
public interface ApprovalAccess {
    record Principal(String tenantId, String actorId) {
        public Principal {
            if (tenantId == null || actorId == null || !tenantId.matches("[1-9][0-9]{0,18}")
                    || !actorId.matches("[1-9][0-9]{0,18}")) throw new ApprovalFailure(403, "NOT_AUTHORIZED");
            try {
                Long.parseLong(tenantId); Long.parseLong(actorId);
            } catch (NumberFormatException e) { throw new ApprovalFailure(403, "NOT_AUTHORIZED"); }
        }
    }
    record SourceReference(String roundId,String companyId,String recordedAt) {}
    Principal principal(HttpServletRequest request);
    SubmissionAuthority submission(HttpServletRequest request, Principal principal, Submission command);
    DecisionAuthority decision(HttpServletRequest request, Principal principal, Application application);
    WithdrawalAuthority withdrawal(HttpServletRequest request, Principal principal, Application application);
    void read(HttpServletRequest request, Principal principal, Application application);
    /** Durable source-owner fence before a new local approval can commit. */
    default void register(HttpServletRequest request, Principal principal, Submission command, String commandDigest) {}
    default SourceReference registerReference(HttpServletRequest request,Principal principal,Submission command,String digest){register(request,principal,command,digest);return null;}
    default void ticketRead(HttpServletRequest request,Principal principal,String ticketId){throw new ApprovalFailure(503,"APPROVAL_TRUST_NOT_CONFIGURED");}
    default java.util.List<String> actions(HttpServletRequest request, Principal principal, Application application) {return java.util.List.of();}

    final class Unavailable implements ApprovalAccess {
        private ApprovalFailure unavailable() { return new ApprovalFailure(503, "APPROVAL_TRUST_NOT_CONFIGURED"); }
        public Principal principal(HttpServletRequest r) { throw unavailable(); }
        public SubmissionAuthority submission(HttpServletRequest r, Principal p, Submission c) { throw unavailable(); }
        public DecisionAuthority decision(HttpServletRequest r, Principal p, Application a) { throw unavailable(); }
        public WithdrawalAuthority withdrawal(HttpServletRequest r, Principal p, Application a) { throw unavailable(); }
        public void read(HttpServletRequest r, Principal p, Application a) { throw unavailable(); }
    }
}
