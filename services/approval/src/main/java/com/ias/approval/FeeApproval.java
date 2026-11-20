package com.ias.approval;

import java.util.*;

/** Immutable fee-approval reducer. All authority facts must be server verified. Amounts are CNY cents. */
public final class FeeApproval {
    private FeeApproval() {}
    public enum State { WAITING_CUSTOMER, WAITING_CONFIGURATION, PENDING_SUPERVISOR, WAITING_FINANCE, COMPLETED, REJECTED, WITHDRAWN, TERMINATED }
    public enum Action { SUBMIT, REFRESH, APPROVE, REJECT, WITHDRAW }
    public record Actor(long tenantId,long userId) {
        public Actor { if(tenantId<=0||userId<=0)throw fail(400,"INVALID_ACTOR"); }
    }
    /** Persist this entire immutable authority snapshot; never reconstruct it from a browser boolean. */
    public record Basis(long tenantId,long companyId,UUID ticketId,UUID roundId,UUID faultRelationId,
                        UUID groupId,UUID quoteId,long quoteVersion,long groupVersion,long generation,
                        long q,long c,long h,long b,long threshold,String ruleVersion,
                        boolean everFinance,boolean financialReviewRequired,boolean chargesCustomer,
                        String quoteDigest,String freezeDigest) {
        public Basis {
            if(tenantId<=0||companyId<=0||quoteVersion<1||groupVersion<0||generation<0||q<0||c<0||h<0||threshold<0||b!=Math.max(q,Math.max(c,h)))throw fail(400,"INVALID_FROZEN_BASIS");
            Objects.requireNonNull(ticketId);Objects.requireNonNull(roundId);Objects.requireNonNull(faultRelationId);Objects.requireNonNull(groupId);Objects.requireNonNull(quoteId);
            if(ruleVersion==null||ruleVersion.isBlank()||!hex(quoteDigest)||!hex(freezeDigest)||financialReviewRequired!=(everFinance||b>threshold))throw fail(400,"INVALID_FROZEN_ROUTING");
        }
    }
    public record Confirmation(UUID id,UUID quoteId,long quoteVersion,String quoteDigest,long companyId) {
        public Confirmation { Objects.requireNonNull(id);Objects.requireNonNull(quoteId);if(quoteVersion<1||companyId<=0||!hex(quoteDigest))throw fail(400,"INVALID_CONFIRMATION"); }
    }
    /** Returned only by the trusted Authority port; qualifications are current at the mutation boundary. */
    public record Facts(Basis basis,Confirmation confirmation,boolean currentQuote,boolean ownerAcksComplete,
                        boolean currentPermission,boolean eligibleSupervisorAvailable,boolean eligibleFinanceAvailable,
                        boolean actorSupervisor,boolean actorFinance) { public Facts {Objects.requireNonNull(basis);} }
    public record Command(UUID operationId,UUID applicationId,Action action,long expectedVersion,Basis basis,String reason) {
        public Command {Objects.requireNonNull(operationId);Objects.requireNonNull(applicationId);Objects.requireNonNull(action);Objects.requireNonNull(basis);if(expectedVersion<0)throw fail(400,"INVALID_VERSION");if(reason!=null&&reason.length()>500)throw fail(400,"REASON_TOO_LONG");}
    }
    public record Application(UUID id,long applicantId,Basis basis,State state,long version,Confirmation confirmation,
                              Long supervisorId,Long financeId,String terminalReason) {}

    public static Application apply(Application old,Actor actor,Command cmd,Facts facts) {
        if(actor.tenantId()!=cmd.basis().tenantId()||!facts.currentPermission())throw fail(403,"CURRENT_FEE_PERMISSION_REQUIRED");
        if(old!=null&&(!old.id().equals(cmd.applicationId())||!old.basis().equals(cmd.basis())))throw fail(409,"APPLICATION_BASIS_CONFLICT");
        if(old!=null&&old.version()!=cmd.expectedVersion())throw fail(409,"VERSION_CONFLICT");
        if(cmd.action()==Action.WITHDRAW){
            if(old==null||old.applicantId()!=actor.userId())throw fail(403,"APPLICANT_REQUIRED");
            active(old);return next(old,State.WITHDRAWN,old.confirmation(),old.supervisorId(),old.financeId(),reason(cmd));
        }
        // All decisions/refresh require the exact currently effective server-owned quote and completed owner barrier.
        if(!cmd.basis().equals(facts.basis())||!facts.currentQuote()||!facts.ownerAcksComplete())throw fail(409,"STALE_QUOTE_OR_INCOMPLETE_OWNER_ACK");
        Confirmation confirmation=facts.confirmation();
        if(confirmation!=null&&!matches(cmd.basis(),confirmation))throw fail(409,"CONFIRMATION_BINDING_CONFLICT");
        if(old!=null&&old.confirmation()!=null&&!old.confirmation().equals(confirmation))throw fail(409,"CONFIRMATION_CHANGED");
        if(cmd.action()==Action.SUBMIT){
            if(old!=null||cmd.expectedVersion()!=0)throw fail(409,"APPLICATION_EXISTS");
            return new Application(cmd.applicationId(),actor.userId(),cmd.basis(),route(cmd.basis(),confirmation,facts),1,confirmation,null,null,null);
        }
        if(old==null)throw fail(404,"FEE_APPLICATION_NOT_FOUND");active(old);
        if(cmd.action()==Action.REFRESH){
            if(old.state()!=State.WAITING_CUSTOMER&&old.state()!=State.WAITING_CONFIGURATION)throw fail(409,"NOT_WAITING");
            State state=old.supervisorId()==null?route(old.basis(),confirmation,facts):facts.eligibleFinanceAvailable()?State.WAITING_FINANCE:State.WAITING_CONFIGURATION;
            return next(old,state,confirmation,old.supervisorId(),old.financeId(),null);
        }
        if(old.basis().chargesCustomer()&&confirmation==null)throw fail(409,"WAITING_CUSTOMER");
        if(actor.userId()==old.applicantId())throw fail(403,"SELF_APPROVAL_FORBIDDEN");
        if(old.state()==State.PENDING_SUPERVISOR){if(!facts.actorSupervisor())throw fail(403,"CURRENT_SUPERVISOR_REQUIRED");}
        else if(old.state()==State.WAITING_FINANCE){if(!facts.actorFinance())throw fail(403,"CURRENT_FINANCE_REQUIRED");}
        else throw fail(409,"NO_DECIDABLE_INTERNAL_TASK");
        if(cmd.action()==Action.REJECT)return next(old,State.REJECTED,confirmation,old.supervisorId(),old.financeId(),reason(cmd));
        if(cmd.action()!=Action.APPROVE)throw fail(400,"INVALID_ACTION");
        if(old.state()==State.PENDING_SUPERVISOR){
            State next=old.basis().financialReviewRequired()?(facts.eligibleFinanceAvailable()?State.WAITING_FINANCE:State.WAITING_CONFIGURATION):State.COMPLETED;
            return next(old,next,confirmation,actor.userId(),null,null);
        }
        return next(old,State.COMPLETED,confirmation,old.supervisorId(),actor.userId(),null);
    }
    private static State route(Basis b,Confirmation c,Facts f){if(b.chargesCustomer()&&c==null)return State.WAITING_CUSTOMER;return f.eligibleSupervisorAvailable()?State.PENDING_SUPERVISOR:State.WAITING_CONFIGURATION;}
    private static Application next(Application a,State s,Confirmation c,Long supervisor,Long finance,String reason){return new Application(a.id(),a.applicantId(),a.basis(),s,Math.incrementExact(a.version()),c,supervisor,finance,reason);}
    private static void active(Application a){if(Set.of(State.COMPLETED,State.REJECTED,State.WITHDRAWN,State.TERMINATED).contains(a.state()))throw fail(409,"TERMINAL_HISTORY_IMMUTABLE");}
    private static boolean matches(Basis b,Confirmation c){return b.quoteId().equals(c.quoteId())&&b.quoteVersion()==c.quoteVersion()&&b.companyId()==c.companyId()&&b.quoteDigest().equals(c.quoteDigest());}
    private static String reason(Command c){if(c.reason()==null||c.reason().isBlank())throw fail(400,"REASON_REQUIRED");return c.reason().strip();}
    private static boolean hex(String s){return s!=null&&s.matches("[0-9a-f]{64}");}
    static ApprovalFailure fail(int status,String code){return new ApprovalFailure(status,code);}
}
