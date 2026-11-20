package com.ias.approval;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static com.ias.approval.PartsApproval.*;
import static org.junit.jupiter.api.Assertions.*;

class PartsApprovalRevisionTest {
    private final Instant start = Instant.parse("2026-10-21T00:00:00Z");
    private Submission command(UUID operation, int quantity) {
        return new Submission(operation, "t", "engineer", "ticket", "assignment", "department", "warehouse",
                "part", quantity, "repair");
    }
    private SubmissionAuthority submitAuth() {
        return new SubmissionAuthority("t", "engineer", "ticket", "assignment", "department", "warehouse",
                true, true, true, true);
    }
    private WithdrawalAuthority withdrawalAuth() { return new WithdrawalAuthority("t", "engineer", true, true); }
    private Application initial() { return submit(UUID.randomUUID(), command(UUID.randomUUID(), 2), submitAuth(), null, start); }
    private Withdrawal withdrawCommand(Application app, UUID operation) {
        return new Withdrawal(operation, "t", "engineer", app.version(), "quantity correction");
    }
    private Application withdrawn(Application app) {
        return withdraw(app, withdrawCommand(app, UUID.randomUUID()), withdrawalAuth(), start.plusSeconds(1));
    }
    private void fails(String code, Runnable action) {
        assertEquals(code, assertThrows(Rejected.class, action::run).getMessage());
    }
    @Test void withdrawalAndNewRevisionPreserveOldImmutableFacts() {
        Application original = initial();
        Application withdrawn = withdrawn(original);
        Application next = resubmit(UUID.randomUUID(), command(UUID.randomUUID(), 3), submitAuth(), withdrawn, null,
                start.plusSeconds(2));
        assertEquals(State.PENDING, original.state());
        assertEquals(State.WITHDRAWN, withdrawn.state());
        assertSame(original.submission(), withdrawn.submission());
        assertEquals(2, original.submission().quantity());
        assertEquals(start, withdrawn.submittedAt());
        assertEquals(1, withdrawn.version());
        assertEquals(3, next.submission().quantity());
        assertEquals(0, next.version());
        assertEquals(new Revision(original.id(), 2, original.id()), next.revision());
        assertNotEquals(original.id(), next.id());
    }
    @Test void repeatedRevisionsKeepRootAndImmediatePredecessor() {
        Application first = initial();
        Application second = resubmit(UUID.randomUUID(), command(UUID.randomUUID(), 3), submitAuth(), withdrawn(first),
                null, start.plusSeconds(2));
        Application secondWithdrawn = withdraw(second, withdrawCommand(second, UUID.randomUUID()), withdrawalAuth(),
                start.plusSeconds(3));
        Application third = resubmit(UUID.randomUUID(), command(UUID.randomUUID(), 4), submitAuth(), secondWithdrawn,
                null, start.plusSeconds(4));
        assertEquals(new Revision(first.id(), 3, second.id()), third.revision());
        assertEquals(State.PENDING, second.state());
        assertEquals(3, second.submission().quantity());
    }
    @Test void withdrawalReplayIsExactAndRequiresCurrentScope() {
        Application original = initial();
        Withdrawal operation = withdrawCommand(original, UUID.randomUUID());
        Application result = withdraw(original, operation, withdrawalAuth(), start.plusSeconds(1));
        assertSame(result, withdraw(result, operation, withdrawalAuth(), start.plusSeconds(3)));
        fails("IDEMPOTENCY_CONFLICT", () -> withdraw(result,
                new Withdrawal(operation.operationId(), "t", "engineer", 0, "different"), withdrawalAuth(), start));
        fails("NOT_AUTHORIZED", () -> withdraw(result, operation,
                new WithdrawalAuthority("t", "engineer", true, false), start));
        fails("NOT_WITHDRAWABLE", () -> withdraw(result,
                withdrawCommand(result, UUID.randomUUID()), withdrawalAuth(), start));
    }
    @Test void onlyApplicantCanWithdrawWithFreshVersion() {
        Application original = initial();
        fails("NOT_AUTHORIZED", () -> withdraw(original,
                new Withdrawal(UUID.randomUUID(), "t", "other", 0, ""),
                new WithdrawalAuthority("t", "other", true, true), start));
        fails("NOT_AUTHORIZED", () -> withdraw(original,
                withdrawCommand(original, UUID.randomUUID()), new WithdrawalAuthority("other", "engineer", true, true), start));
        fails("VERSION_CONFLICT", () -> withdraw(original,
                new Withdrawal(UUID.randomUUID(), "t", "engineer", 1, ""), withdrawalAuth(), start));
    }
    @Test void approvedApplicationCannotBeRewrittenAsWithdrawnAndWithdrawnCannotBeDecided() {
        Application original = initial();
        DecisionAuthority supervisor = new DecisionAuthority("t", "supervisor", "department", true, true, true, true);
        Decision decision = new Decision(UUID.randomUUID(), "t", "supervisor", 0, Outcome.APPROVE, "");
        Application approved = decide(original, decision, supervisor, start);
        fails("NOT_WITHDRAWABLE", () -> withdraw(approved,
                withdrawCommand(approved, UUID.randomUUID()), withdrawalAuth(), start));
        Application withdrawn = withdrawn(original);
        fails("APPLICATION_WITHDRAWN", () -> decide(withdrawn,
                new Decision(UUID.randomUUID(), "t", "supervisor", 1, Outcome.APPROVE, ""), supervisor, start.plusSeconds(2)));
    }
    @Test void resubmissionNeedsWithdrawalNewIdentityAndCurrentQualification() {
        Application original = initial();
        Submission next = command(UUID.randomUUID(), 3);
        fails("REVISION_SOURCE_REQUIRED", () -> resubmit(UUID.randomUUID(), next, submitAuth(), original, null, start));
        Application withdrawn = withdrawn(original);
        fails("NEW_SUBMISSION_UUID_REQUIRED", () -> resubmit(UUID.randomUUID(), original.submission(), submitAuth(),
                withdrawn, null, start.plusSeconds(2)));
        fails("NEW_APPLICATION_ID_REQUIRED", () -> resubmit(original.id(), next, submitAuth(), withdrawn, null,
                start.plusSeconds(2)));
        fails("NOT_AUTHORIZED", () -> resubmit(UUID.randomUUID(), next,
                new SubmissionAuthority("t", "engineer", "ticket", "assignment", "department", "warehouse",
                        false, true, true, true), withdrawn, null, start.plusSeconds(2)));
        fails("INVALID_TIME", () -> resubmit(UUID.randomUUID(), next, submitAuth(), withdrawn, null, start));
    }
    @Test void replayBindsOriginalParametersAndRevisionLineage() {
        Application withdrawn = withdrawn(initial());
        Submission command = command(UUID.randomUUID(), 3);
        Application next = resubmit(UUID.randomUUID(), command, submitAuth(), withdrawn, null, start.plusSeconds(2));
        assertSame(next, resubmit(UUID.randomUUID(), command, submitAuth(), withdrawn, next, start.plusSeconds(3)));
        fails("IDEMPOTENCY_CONFLICT", () -> resubmit(UUID.randomUUID(), command(command.operationId(), 4), submitAuth(),
                withdrawn, next, start.plusSeconds(3)));
        Application unrelatedPredecessor = withdrawn(initial());
        fails("IDEMPOTENCY_CONFLICT", () -> resubmit(UUID.randomUUID(), command, submitAuth(), unrelatedPredecessor,
                next, start.plusSeconds(3)));
    }
    @Test void rejectedVersionCanBeCopiedWithoutOverwritingItsDecision() {
        Application first=initial();
        var decision=new Decision(UUID.randomUUID(),"t","supervisor",0,Outcome.REJECT,"not needed");
        Application rejected=decide(first,decision,new DecisionAuthority("t","supervisor","department",true,true,true,true),start);
        Application revised=resubmit(UUID.randomUUID(),command(UUID.randomUUID(),3),submitAuth(),rejected,null,start.plusSeconds(1));
        assertEquals(State.REJECTED,rejected.state());assertSame(decision,rejected.decision());
        assertEquals(State.PENDING,revised.state());assertNull(revised.decision());
        assertEquals(new Revision(first.id(),2,first.id()),revised.revision());
    }
}
