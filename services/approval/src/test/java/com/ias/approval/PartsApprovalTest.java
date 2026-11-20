package com.ias.approval;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static com.ias.approval.PartsApproval.*;
import static org.junit.jupiter.api.Assertions.*;

class PartsApprovalTest {
    private final Instant now = Instant.parse("2026-10-20T12:00:00Z");
    private final Submission submission = new Submission(UUID.randomUUID(), "tenant", "engineer", "ticket",
            "assignment", "department", "warehouse", "part", 2, "replace damaged part");
    private SubmissionAuthority submitAuth(boolean supervisorExists) {
        return new SubmissionAuthority("tenant", "engineer", "ticket", "assignment", "department", "warehouse",
                true, true, true, supervisorExists);
    }
    private DecisionAuthority auth(String actor, String department, boolean active, boolean allowed) {
        return new DecisionAuthority("tenant", actor, department, active, true, true, allowed);
    }
    private Application pending() { return submit(UUID.randomUUID(), submission, submitAuth(true), null, now); }
    private Decision decision(UUID id, String actor, Outcome outcome, String reason) {
        return new Decision(id, "tenant", actor, 0, outcome, reason);
    }
    private void rejected(String code, Runnable action) {
        assertEquals(code, assertThrows(Rejected.class, action::run).getMessage());
    }
    @Test void noSupervisorWaitsWithoutAutomaticApproval() {
        Application waiting = submit(UUID.randomUUID(), submission, submitAuth(false), null, now);
        assertEquals(State.WAITING_CONFIGURATION, waiting.state());
        assertNull(waiting.decision());
        assertEquals(State.APPROVED, decide(waiting, decision(UUID.randomUUID(), "supervisor", Outcome.APPROVE, ""),
                auth("supervisor", "department", true, true), now).state());
    }
    @Test void sameSubmissionUuidReturnsOriginalSnapshot() {
        Application original = pending();
        assertSame(original, submit(UUID.randomUUID(), submission, submitAuth(true), original, now.plusSeconds(5)));
    }
    @Test void sameSubmissionUuidWithDifferentParametersConflicts() {
        Application original = pending();
        Submission changed = new Submission(submission.operationId(), "tenant", "engineer", "ticket", "assignment",
                "department", "warehouse", "part", 3, submission.purpose());
        rejected("IDEMPOTENCY_CONFLICT", () -> submit(UUID.randomUUID(), changed, submitAuth(true), original, now));
    }
    @Test void submittingRequiresCurrentOwnerAndWarehouseScope() {
        SubmissionAuthority changed = new SubmissionAuthority("tenant", "engineer", "ticket", "old-assignment",
                "department", "warehouse", true, true, true, true);
        rejected("NOT_AUTHORIZED", () -> submit(UUID.randomUUID(), submission, changed, null, now));
        SubmissionAuthority finalChanged = new SubmissionAuthority("tenant", "engineer", "ticket", "assignment", "department", "warehouse",
                true, true, false, true);
        rejected("NOT_AUTHORIZED", () -> submit(UUID.randomUUID(), submission, finalChanged, null, now));
    }
    @Test void approveDoesNotMutateSubmittedSnapshotAndReplaysExactlyOnce() {
        Application original = pending();
        Decision command = decision(UUID.randomUUID(), "supervisor", Outcome.APPROVE, "approved");
        Application approved = decide(original, command, auth("supervisor", "department", true, true), now);
        assertEquals(State.PENDING, original.state());
        assertEquals(1, approved.version());
        assertSame(submission, approved.submission());
        assertSame(approved, decide(approved, command, auth("supervisor", "department", true, false), now));
        Decision changed = decision(command.operationId(), "supervisor", Outcome.REJECT, "reject");
        rejected("IDEMPOTENCY_CONFLICT", () -> decide(approved, changed,
                auth("supervisor", "department", true, true), now));
        rejected("ALREADY_DECIDED", () -> decide(approved,
                decision(UUID.randomUUID(), "supervisor", Outcome.REJECT, "reject"),
                auth("supervisor", "department", true, true), now));
    }
    @Test void revokedSupervisorCannotReadOldDecisionReceipt() {
        Decision command = decision(UUID.randomUUID(), "supervisor", Outcome.APPROVE, "");
        Application approved = decide(pending(), command, auth("supervisor", "department", true, true), now);
        rejected("NOT_AUTHORIZED", () -> decide(approved, command, auth("supervisor", "department", false, true), now));
    }
    @Test void selfApprovalWrongDepartmentAndCrossTenantAreDenied() {
        Application original = pending();
        rejected("NOT_AUTHORIZED", () -> decide(original, decision(UUID.randomUUID(), "engineer", Outcome.APPROVE, ""),
                auth("engineer", "department", true, true), now));
        Decision command = decision(UUID.randomUUID(), "supervisor", Outcome.APPROVE, "");
        rejected("NOT_AUTHORIZED", () -> decide(original, command, auth("supervisor", "other", true, true), now));
        rejected("NOT_AUTHORIZED", () -> decide(original, command,
                new DecisionAuthority("other", "supervisor", "department", true, true, true, true), now));
    }
    @Test void terminalTicketAndStaleVersionCannotCreateDecision() {
        Application original = pending();
        rejected("TICKET_NOT_APPROVABLE", () -> decide(original,
                decision(UUID.randomUUID(), "supervisor", Outcome.APPROVE, ""),
                auth("supervisor", "department", true, false), now));
        rejected("VERSION_CONFLICT", () -> decide(original,
                new Decision(UUID.randomUUID(), "tenant", "supervisor", 1, Outcome.APPROVE, ""),
                auth("supervisor", "department", true, true), now));
    }
    @Test void rejectionRequiresReasonAndPositiveQuantity() {
        rejected("REASON_REQUIRED", () -> decision(UUID.randomUUID(), "supervisor", Outcome.REJECT, " "));
        rejected("INVALID_QUANTITY", () -> new Submission(UUID.randomUUID(), "tenant", "engineer", "ticket",
                "assignment", "department", "warehouse", "part", 0, "purpose"));
        Application rejected = decide(pending(), decision(UUID.randomUUID(), "supervisor", Outcome.REJECT, "not needed"),
                auth("supervisor", "department", true, true), now);
        assertEquals(State.REJECTED, rejected.state());
    }
}
