package com.ias.finance;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/**
 * Immutable fee-group reducer, not an HTTP DTO or an authorization service.
 * Persistence must load the tenant-owned aggregate, validate current actors and
 * trusted approval/cost facts, then CAS the group version with receipt/audit/outbox.
 * All monetary amounts are CNY cents. This class never creates a receivable.
 */
public final class FeeGroup {
    private FeeGroup() { }

    public record Identity(long tenantId, long customerCompanyId, UUID faultGroupId) {
        public Identity {
            if (tenantId <= 0 || customerCompanyId <= 0) throw new IllegalArgumentException("Invalid group owner");
            Objects.requireNonNull(faultGroupId);
        }
    }
    public record PublishedRule(String version, long thresholdCents) {
        public PublishedRule {
            required(version);
            if (thresholdCents < 0) throw new IllegalArgumentException("Negative threshold");
        }
    }
    public record PlanLine(UUID id, UUID ticketId, UUID roundId, long amountCents, boolean executed) {
        public PlanLine {
            Objects.requireNonNull(id); Objects.requireNonNull(ticketId); Objects.requireNonNull(roundId);
            positive(amountCents);
        }
    }
    public record Submit(UUID operationId, UUID applicationId, long expectedVersion,
                         Set<UUID> replaces, List<PlanLine> additions, PublishedRule rule) {
        public Submit {
            Objects.requireNonNull(operationId); Objects.requireNonNull(applicationId);
            replaces = Set.copyOf(replaces); additions = List.copyOf(additions); Objects.requireNonNull(rule);
            if (expectedVersion < 0 || additions.isEmpty()) throw new IllegalArgumentException("Invalid submission");
            if (additions.stream().anyMatch(PlanLine::executed)) throw new IllegalArgumentException("New quote cannot assert execution");
        }
    }
    public record FinanceTrigger(UUID applicationId, String ruleVersion, long groupVersion) { }
    public record Basis(Identity owner, UUID applicationId, long groupVersion, long qCents, long cCents,
                        long hCents, long bCents, PublishedRule rule, boolean financialReviewRequired,
                        FinanceTrigger firstFinanceTrigger) { }
    public record Applied(Submit command, Basis basis) { }
    public record CostFact(UUID id, long deltaCents, UUID correctionOf, String evidenceRef) {
        public CostFact {
            Objects.requireNonNull(id); required(evidenceRef);
            if (deltaCents == 0 || deltaCents == Long.MIN_VALUE) throw new IllegalArgumentException("Invalid cost amount");
            if ((deltaCents < 0) != (correctionOf != null)) throw new IllegalArgumentException("Correction must reference original cost");
        }
    }
    /** Only the approval owner's authenticated completion fact may supply this. */
    public record ApprovalFact(UUID completionEventId, boolean supervisorCompleted, boolean financeCompleted) {
        public ApprovalFact { Objects.requireNonNull(completionEventId); }
    }
    public record State(Identity owner, long version, Map<UUID, PlanLine> lines, Map<UUID, CostFact> costFacts,
                        long highestApprovedCents, boolean everRequiredFinance, FinanceTrigger firstFinanceTrigger,
                        Map<UUID, Applied> submissions, Map<UUID, ApprovalFact> approvedApplications) {
        public State {
            Objects.requireNonNull(owner);
            if (version < 0 || highestApprovedCents < 0) throw new IllegalArgumentException("Invalid aggregate metadata");
            if (everRequiredFinance != (firstFinanceTrigger != null)) throw new IllegalArgumentException("Missing permanent trigger");
            lines = Map.copyOf(lines); costFacts = Map.copyOf(costFacts);
            submissions = Map.copyOf(submissions); approvedApplications = Map.copyOf(approvedApplications);
        }
        public long qCents() { return sum(lines.values().stream().map(PlanLine::amountCents).toList()); }
        public long cCents() { return sum(costFacts.values().stream().map(CostFact::deltaCents).toList()); }
        public long bCents() { return Math.max(qCents(), Math.max(cCents(), highestApprovedCents)); }
    }
    public record SubmissionResult(State state, Basis basis, boolean replay) { }

    public static State open(Identity owner) {
        return new State(owner, 0, Map.of(), Map.of(), 0, false, null, Map.of(), Map.of());
    }

    public static SubmissionResult submit(State before, Submit command) {
        Applied prior = before.submissions().get(command.operationId());
        if (prior != null) {
            if (!prior.command().equals(command)) throw new IllegalStateException("OPERATION_CONFLICT");
            return new SubmissionResult(before, prior.basis(), true);
        }
        version(before, command.expectedVersion());
        if (before.submissions().values().stream().anyMatch(a -> a.basis().applicationId().equals(command.applicationId())))
            throw new IllegalStateException("APPLICATION_ALREADY_SUBMITTED");
        Map<UUID, PlanLine> nextLines = new HashMap<>(before.lines());
        for (UUID id : command.replaces()) {
            PlanLine line = nextLines.get(id);
            if (line == null) throw new IllegalArgumentException("Replacement target is not effective");
            if (line.executed()) throw new IllegalStateException("EXECUTED_LINE_IS_IMMUTABLE");
            nextLines.remove(id);
        }
        Set<UUID> historicIds = new HashSet<>(before.lines().keySet());
        before.submissions().values().forEach(a -> a.command().additions().forEach(l -> historicIds.add(l.id())));
        for (PlanLine line : command.additions()) {
            if (!historicIds.add(line.id()) || nextLines.putIfAbsent(line.id(), line) != null)
                throw new IllegalArgumentException("Line identity cannot be reused");
        }
        long q = sum(nextLines.values().stream().map(PlanLine::amountCents).toList());
        long c = before.cCents(), h = before.highestApprovedCents(), b = Math.max(q, Math.max(c, h));
        boolean finance = before.everRequiredFinance() || b > command.rule().thresholdCents();
        long nextVersion = Math.incrementExact(before.version());
        FinanceTrigger trigger = before.firstFinanceTrigger();
        if (finance && trigger == null) trigger = new FinanceTrigger(command.applicationId(), command.rule().version(), nextVersion);
        Basis basis = new Basis(before.owner(), command.applicationId(), nextVersion, q, c, h, b, command.rule(), finance, trigger);
        Map<UUID, Applied> receipts = new HashMap<>(before.submissions());
        receipts.put(command.operationId(), new Applied(command, basis));
        State after = new State(before.owner(), nextVersion, nextLines, before.costFacts(), h, finance, trigger, receipts, before.approvedApplications());
        return new SubmissionResult(after, basis, false);
    }

    public static State recordApproved(State before, long expectedVersion, UUID applicationId, ApprovalFact fact) {
        ApprovalFact prior = before.approvedApplications().get(applicationId);
        if (prior != null) {
            if (!prior.equals(fact)) throw new IllegalStateException("APPROVAL_FACT_CONFLICT");
            return before;
        }
        version(before, expectedVersion);
        if (before.approvedApplications().values().stream().anyMatch(existing -> existing.completionEventId().equals(fact.completionEventId())))
            throw new IllegalStateException("APPROVAL_EVENT_ALREADY_USED");
        Basis basis = before.submissions().values().stream().map(Applied::basis)
                .filter(b -> b.applicationId().equals(applicationId)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown application"));
        if (!fact.supervisorCompleted() || (basis.financialReviewRequired() && !fact.financeCompleted()))
            throw new IllegalStateException("REQUIRED_APPROVAL_NODE_INCOMPLETE");
        Map<UUID, ApprovalFact> approved = new HashMap<>(before.approvedApplications()); approved.put(applicationId, fact);
        return new State(before.owner(), Math.incrementExact(before.version()), before.lines(), before.costFacts(),
                Math.max(before.highestApprovedCents(), basis.bCents()), before.everRequiredFinance(), before.firstFinanceTrigger(), before.submissions(), approved);
    }

    /** Verified unauthorized actual costs also belong in C; they do not authorize customer charges. */
    public static State recordVerifiedCost(State before, long expectedVersion, CostFact fact) {
        CostFact prior = before.costFacts().get(fact.id());
        if (prior != null) {
            if (!prior.equals(fact)) throw new IllegalStateException("COST_FACT_CONFLICT");
            return before;
        }
        version(before, expectedVersion);
        if (fact.correctionOf() != null) {
            CostFact original = before.costFacts().get(fact.correctionOf());
            if (original == null || original.deltaCents() <= 0) throw new IllegalArgumentException("Invalid correction source");
            long remaining = original.deltaCents();
            for (CostFact entry : before.costFacts().values())
                if (original.id().equals(entry.correctionOf())) remaining = Math.addExact(remaining, entry.deltaCents());
            if (Math.negateExact(fact.deltaCents()) > remaining) throw new IllegalArgumentException("Correction exceeds original remaining cost");
        }
        if (Math.addExact(before.cCents(), fact.deltaCents()) < 0) throw new IllegalArgumentException("Negative effective cost");
        Map<UUID, CostFact> next = new HashMap<>(before.costFacts()); next.put(fact.id(), fact);
        return new State(before.owner(), Math.incrementExact(before.version()), before.lines(), next, before.highestApprovedCents(),
                before.everRequiredFinance(), before.firstFinanceTrigger(), before.submissions(), before.approvedApplications());
    }

    public static State protectExecutedLine(State before, long expectedVersion, UUID lineId) {
        version(before, expectedVersion);
        PlanLine line = Optional.ofNullable(before.lines().get(lineId)).orElseThrow(() -> new IllegalArgumentException("Unknown line"));
        if (line.executed()) return before;
        Map<UUID, PlanLine> next = new HashMap<>(before.lines());
        next.put(lineId, new PlanLine(line.id(), line.ticketId(), line.roundId(), line.amountCents(), true));
        return new State(before.owner(), Math.incrementExact(before.version()), next, before.costFacts(), before.highestApprovedCents(),
                before.everRequiredFinance(), before.firstFinanceTrigger(), before.submissions(), before.approvedApplications());
    }

    public static long cents(String cny) {
        BigDecimal decimal = new BigDecimal(cny);
        if (decimal.signum() < 0 || decimal.scale() > 2) throw new IllegalArgumentException("Invalid CNY precision");
        return decimal.movePointRight(2).longValueExact();
    }
    private static long sum(Collection<Long> amounts) {
        // Cost facts include reversals; their map iteration order must not alter
        // whether a valid net balance overflows in an intermediate addition.
        BigInteger result = BigInteger.ZERO;
        for (long amount : amounts) result = result.add(BigInteger.valueOf(amount));
        return result.longValueExact();
    }
    private static void version(State state, long expected) {
        if (state.version() != expected) throw new IllegalStateException("VERSION_CONFLICT");
    }
    private static void positive(long value) { if (value <= 0) throw new IllegalArgumentException("Amount must be positive"); }
    private static void required(String value) { if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing required reference"); }
}
