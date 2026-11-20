package com.ias.finance;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ias.finance.FeeGroup.*;

class FeeGroupTest {
    final PublishedRule t2000 = new PublishedRule("APPROVAL_RULE_V1", cents("2000"));
    final PublishedRule t3000 = new PublishedRule("APPROVAL_RULE_V2", cents("3000"));
    State group() { return open(new Identity(1, 2, UUID.randomUUID())); }
    PlanLine line(String amount) { return new PlanLine(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), cents(amount), false); }
    Submit command(State state, List<PlanLine> additions, Set<UUID> replaces, PublishedRule rule) {
        return new Submit(UUID.randomUUID(), UUID.randomUUID(), state.version(), replaces, additions, rule);
    }
    SubmissionResult append(State state, String amount, PublishedRule rule) {
        return submit(state, command(state, List.of(line(amount)), Set.of(), rule));
    }
    State approve(SubmissionResult result) {
        return recordApproved(result.state(), result.state().version(), result.basis().applicationId(),
                new ApprovalFact(UUID.randomUUID(), true, result.basis().financialReviewRequired()));
    }

    @Test void approved1800Plus500AcrossRoundsRequiresFinanceOn2300WithoutDuplicatingNewCharge() {
        var first = append(group(), "1800", t2000);
        assertFalse(first.basis().financialReviewRequired());
        State approved = approve(first);
        var second = append(approved, "500", t2000);
        assertEquals(cents("2300"), second.basis().qCents());
        assertEquals(cents("1800"), second.basis().hCents());
        assertEquals(cents("2300"), second.basis().bCents());
        assertTrue(second.basis().financialReviewRequired());
        assertEquals(cents("500"), second.state().submissions().values().stream()
                .filter(a -> a.basis().applicationId().equals(second.basis().applicationId())).findFirst().orElseThrow().command().additions().getFirst().amountCents());
        assertEquals(first.state().owner(), second.state().owner());
        assertNotEquals(first.state().lines().values().iterator().next().roundId(),
                second.state().lines().values().stream().filter(l -> l.amountCents() == cents("500")).findFirst().orElseThrow().roundId());
    }
    @Test void thresholdIsInclusiveAndOneCentAboveRequiresFinance() {
        assertFalse(append(group(), "2000", t2000).basis().financialReviewRequired());
        assertTrue(append(group(), "2000.01", t2000).basis().financialReviewRequired());
    }
    @Test void explicitReplacementCountsOnlyNewLineAndKeepsApprovedHighWater() {
        var original = append(group(), "2300", t2000);
        State approved = approve(original);
        UUID replaced = approved.lines().keySet().iterator().next();
        var replacement = submit(approved, command(approved, List.of(line("1800")), Set.of(replaced), t2000));
        assertEquals(cents("1800"), replacement.basis().qCents());
        assertEquals(cents("2300"), replacement.basis().hCents());
        assertEquals(cents("2300"), replacement.basis().bCents());
        assertEquals(1, replacement.state().lines().size());
        assertEquals(1, original.state().lines().size());
        assertEquals(cents("2300"), original.basis().qCents());
    }
    @Test void verifiedActualCostRemainsFloorEvenWhenLowQuoteWasNeverApproved() {
        State initial = recordVerifiedCost(group(), 0, new CostFact(UUID.randomUUID(), cents("2300"), null, "VERIFIED_UNAUTHORIZED_ACTUAL_COST"));
        var proposal = append(initial, "1800", t2000);
        assertEquals(cents("1800"), proposal.basis().qCents());
        assertEquals(cents("2300"), proposal.basis().cCents());
        assertEquals(0, proposal.basis().hCents());
        assertEquals(cents("2300"), proposal.basis().bCents());
        assertTrue(proposal.basis().financialReviewRequired());
    }
    @Test void submissionAlonePermanentlyTriggersFinanceWhenThresholdLaterRises() {
        var first = append(group(), "2300", t2000); // no approval has occurred
        var second = append(first.state(), "100", t3000);
        assertEquals(0, second.basis().hCents());
        assertTrue(second.basis().bCents() < t3000.thresholdCents());
        assertTrue(second.basis().financialReviewRequired());
        assertEquals(first.basis().firstFinanceTrigger(), second.basis().firstFinanceTrigger());
        assertEquals(first.basis().applicationId(), second.basis().firstFinanceTrigger().applicationId());
        assertEquals(t2000, first.basis().rule());
    }
    @Test void lowReplacementDoesNotClearHistoryOrReuseOldLineIdentity() {
        var first = append(group(), "2300", t2000);
        UUID old = first.state().lines().keySet().iterator().next();
        var replacement = submit(first.state(), command(first.state(), List.of(line("100")), Set.of(old), t3000));
        assertEquals(cents("100"), replacement.basis().bCents());
        assertTrue(replacement.basis().financialReviewRequired());
        PlanLine oldIdentity = new PlanLine(old, UUID.randomUUID(), UUID.randomUUID(), cents("1"), false);
        assertThrows(IllegalArgumentException.class, () -> submit(replacement.state(), command(replacement.state(), List.of(oldIdentity), Set.of(), t3000)));
    }
    @Test void executedPreviousRoundLineCannotBeCoveredByReplacement() {
        var first = append(group(), "1800", t2000);
        UUID id = first.state().lines().keySet().iterator().next();
        State executed = protectExecutedLine(first.state(), first.state().version(), id);
        assertThrows(IllegalStateException.class, () -> submit(executed, command(executed, List.of(line("500")), Set.of(id), t2000)));
        assertEquals(cents("1800"), executed.qCents());
        assertTrue(executed.lines().get(id).executed());
        assertFalse(first.state().lines().get(id).executed());
    }
    @Test void replayReturnsOriginalFrozenBasisAndChangedBodyConflicts() {
        State initial = group();
        Submit command = command(initial, List.of(line("1800")), Set.of(), t2000);
        var first = submit(initial, command);
        var later = append(first.state(), "500", t3000);
        var replay = submit(later.state(), command);
        assertTrue(replay.replay()); assertSame(later.state(), replay.state()); assertEquals(first.basis(), replay.basis());
        Submit altered = new Submit(command.operationId(), command.applicationId(), command.expectedVersion(), command.replaces(), command.additions(), t3000);
        assertThrows(IllegalStateException.class, () -> submit(later.state(), altered));
    }
    @Test void staleVersionAndSameApplicationDifferentOperationDoNotCreateSecondSubmission() {
        State initial = group();
        Submit one = command(initial, List.of(line("1800")), Set.of(), t2000);
        var first = submit(initial, one);
        Submit stale = command(initial, List.of(line("400")), Set.of(), t2000);
        assertThrows(IllegalStateException.class, () -> submit(first.state(), stale));
        Submit duplicateApp = new Submit(UUID.randomUUID(), one.applicationId(), first.state().version(), Set.of(), List.of(line("1")), t2000);
        assertThrows(IllegalStateException.class, () -> submit(first.state(), duplicateApp));
        assertEquals(1, first.state().submissions().size());
    }
    @Test void missingReplacementTargetAndDuplicateAdditionsFailWithoutMutation() {
        State initial = group(); PlanLine line = line("100");
        assertThrows(IllegalArgumentException.class, () -> submit(initial, command(initial, List.of(line), Set.of(UUID.randomUUID()), t2000)));
        assertThrows(IllegalArgumentException.class, () -> submit(initial, command(initial, List.of(line, line), Set.of(), t2000)));
        assertTrue(initial.lines().isEmpty()); assertTrue(initial.submissions().isEmpty());
    }
    @Test void costCorrectionsAppendFactsCannotExceedOriginalAndDoNotLowerApprovedHighWater() {
        var proposal = append(group(), "2300", t2000); State state = approve(proposal);
        CostFact actual = new CostFact(UUID.randomUUID(), cents("2300"), null, "ACTUAL_PROOF");
        State posted = recordVerifiedCost(state, state.version(), actual);
        CostFact correction = new CostFact(UUID.randomUUID(), -cents("500"), actual.id(), "CONTROLLED_ERROR_CORRECTION_PROOF");
        State corrected = recordVerifiedCost(posted, posted.version(), correction);
        assertEquals(cents("1800"), corrected.cCents()); assertEquals(2, corrected.costFacts().size());
        assertEquals(actual, corrected.costFacts().get(actual.id()));
        assertEquals(cents("2300"), corrected.highestApprovedCents());
        assertTrue(corrected.everRequiredFinance());
        assertSame(corrected, recordVerifiedCost(corrected, posted.version(), correction));
        assertThrows(IllegalArgumentException.class, () -> recordVerifiedCost(corrected, corrected.version(), new CostFact(UUID.randomUUID(), -cents("1800.01"), actual.id(), "EXCESSIVE_CORRECTION")));
        assertThrows(IllegalArgumentException.class, () -> recordVerifiedCost(corrected, corrected.version(), new CostFact(UUID.randomUUID(), -1, UUID.randomUUID(), "MISSING_SOURCE")));
    }
    @Test void approvalsRequireFrozenNodesAndCompletionEventCannotBeReused() {
        var first = append(group(), "2300", t2000);
        var noFinance = new ApprovalFact(UUID.randomUUID(), true, false);
        assertThrows(IllegalStateException.class, () -> recordApproved(first.state(), first.state().version(), first.basis().applicationId(), noFinance));
        assertEquals(0, first.state().highestApprovedCents());
        var full = new ApprovalFact(UUID.randomUUID(), true, true);
        State approved = recordApproved(first.state(), first.state().version(), first.basis().applicationId(), full);
        assertSame(approved, recordApproved(approved, first.state().version(), first.basis().applicationId(), full));
        var second = append(approved, "100", t3000);
        assertThrows(IllegalStateException.class, () -> recordApproved(second.state(), second.state().version(), second.basis().applicationId(), full));
    }
    @Test void amountsAreExactCentsAndOverflowNeverWraps() {
        assertEquals(12345, cents("123.45")); assertEquals(0, cents("0"));
        assertThrows(IllegalArgumentException.class, () -> cents("0.001"));
        assertThrows(IllegalArgumentException.class, () -> cents("-0.01"));
        assertThrows(ArithmeticException.class, () -> cents("92233720368547758.08"));
        State initial = group();
        PlanLine enormous = new PlanLine(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Long.MAX_VALUE, false);
        var full = submit(initial, command(initial, List.of(enormous), Set.of(), t2000));
        assertThrows(ArithmeticException.class, () -> append(full.state(), "0.01", t2000));
        assertEquals(Long.MAX_VALUE, full.state().qCents());
        assertThrows(UnsupportedOperationException.class, () -> full.state().lines().clear());
    }
    @Test void netCostIsIndependentOfImmutableMapIterationOrder() {
        State initial = group();
        CostFact first = new CostFact(UUID.randomUUID(), Long.MAX_VALUE / 2, null, "VERIFIED_COST_A");
        State posted = recordVerifiedCost(initial, initial.version(), first);
        CostFact correction = new CostFact(UUID.randomUUID(), -first.deltaCents(), first.id(), "CONTROLLED_CORRECTION_A");
        State corrected = recordVerifiedCost(posted, posted.version(), correction);
        State largest = recordVerifiedCost(corrected, corrected.version(), new CostFact(UUID.randomUUID(), Long.MAX_VALUE, null, "VERIFIED_COST_B"));
        assertEquals(Long.MAX_VALUE, largest.cCents());
    }
}
