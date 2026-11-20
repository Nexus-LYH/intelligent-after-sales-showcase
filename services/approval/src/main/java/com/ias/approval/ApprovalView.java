package com.ias.approval;

import java.time.Instant;
import java.util.UUID;
import static com.ias.approval.PartsApproval.*;

/** Explicit wire projection: all optimistic versions/revision counters are decimal strings. */
public record ApprovalView(UUID id, Submission submission, State state, String version, Instant submittedAt,
                           DecisionView decision, Instant decidedAt, RevisionView revision,
                           WithdrawalView withdrawal, Instant withdrawnAt, java.util.List<String> allowedActions) {
    public ApprovalView {allowedActions=java.util.List.copyOf(allowedActions);}
    public ApprovalView withActions(java.util.List<String> actions) {
        return new ApprovalView(id,submission,state,version,submittedAt,decision,decidedAt,revision,withdrawal,withdrawnAt,actions);
    }
    public record DecisionView(UUID operationId, String tenantId, String actorId, String expectedVersion,
                               Outcome outcome, String reason) {}
    public record RevisionView(UUID rootApplicationId, String number, UUID previousApplicationId) {}
    public record WithdrawalView(UUID operationId, String tenantId, String actorId, String expectedVersion, String reason) {}
    public static ApprovalView of(Application value) {
        var decision = value.decision(); var withdrawal = value.withdrawal(); var revision = value.revision();
        return new ApprovalView(value.id(), value.submission(), value.state(), Long.toString(value.version()), value.submittedAt(),
                decision == null ? null : new DecisionView(decision.operationId(), decision.tenantId(), decision.actorId(),
                        Long.toString(decision.expectedVersion()), decision.outcome(), decision.reason()), value.decidedAt(),
                new RevisionView(revision.rootApplicationId(), Integer.toString(revision.number()), revision.previousApplicationId()),
                withdrawal == null ? null : new WithdrawalView(withdrawal.operationId(), withdrawal.tenantId(), withdrawal.actorId(),
                        Long.toString(withdrawal.expectedVersion()), withdrawal.reason()), value.withdrawnAt(),java.util.List.of());
    }
}
