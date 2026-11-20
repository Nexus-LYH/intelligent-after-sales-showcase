package com.ias.approval;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Pure transitions. Persistence must serialize commands and atomically store state, receipt, audit and outbox. */
public final class PartsApproval {
    private PartsApproval() {}
    public enum State { WAITING_CONFIGURATION, PENDING, APPROVED, REJECTED, WITHDRAWN }
    public enum Outcome { APPROVE, REJECT }
    public static final class Rejected extends RuntimeException {
        public Rejected(String code) { super(code); }
    }
    public record Submission(UUID operationId, String tenantId, String applicantId, String ticketId,
                             String assignmentId, String departmentId, String warehouseId,
                             String partId, int quantity, String purpose) {
        public Submission {
            Objects.requireNonNull(operationId);
            for (String value : new String[]{tenantId, applicantId, ticketId, assignmentId, departmentId,
                    warehouseId, partId, purpose}) required(value);
            if (quantity <= 0) throw new Rejected("INVALID_QUANTITY");
            if (characters(purpose) < 5 || characters(purpose) > 500) throw new Rejected("INVALID_PURPOSE");
        }
    }
    /** Resolved by a trusted adapter under the ticket/authorization protocol, never from browser claims. */
    public record SubmissionAuthority(String tenantId, String actorId, String ticketId,
                                     String assignmentId, String departmentId, String warehouseId,
                                     boolean currentEngineer, boolean ticketInProgress,
                                     boolean warehousePermitted, boolean qualifiedSupervisorExists) {}
    public record Decision(UUID operationId, String tenantId, String actorId, long expectedVersion,
                           Outcome outcome, String reason) {
        public Decision {
            Objects.requireNonNull(operationId); Objects.requireNonNull(outcome);
            required(tenantId); required(actorId);
            if (expectedVersion < 0) throw new Rejected("INVALID_VERSION");
            reason = reason == null ? "" : reason;
            if (outcome == Outcome.REJECT && reason.isBlank()) throw new Rejected("REASON_REQUIRED");
            if (characters(reason) > 500 || (outcome == Outcome.REJECT && characters(reason) < 5))
                throw new Rejected("INVALID_REASON");
        }
    }
    public record DecisionAuthority(String tenantId, String actorId, String departmentId,
                                    boolean active, boolean supervisor, boolean currentTicketScope,
                                    boolean ticketAllowsApproval) {}
    public record Withdrawal(UUID operationId, String tenantId, String actorId, long expectedVersion,
                             String reason) {
        public Withdrawal {
            Objects.requireNonNull(operationId); required(tenantId); required(actorId);
            if (expectedVersion < 0) throw new Rejected("INVALID_VERSION");
            reason = reason == null ? "" : reason;
            if (characters(reason) > 500) throw new Rejected("INVALID_REASON");
        }
    }
    public record WithdrawalAuthority(String tenantId, String actorId, boolean active,
                                      boolean currentTicketScope) {}
    /** Immutable revision lineage; request revision and optimistic row version are different counters. */
    public record Revision(UUID rootApplicationId, int number, UUID previousApplicationId) {
        public Revision {
            Objects.requireNonNull(rootApplicationId);
            if (number < 1 || ((number == 1) != (previousApplicationId == null)))
                throw new Rejected("INVALID_REVISION");
        }
    }
    public record Application(UUID id, Submission submission, State state, long version,
                              Instant submittedAt, Decision decision, Instant decidedAt,
                              Revision revision, Withdrawal withdrawal, Instant withdrawnAt) {
        public Application {
            Objects.requireNonNull(id); Objects.requireNonNull(submission); Objects.requireNonNull(state);
            Objects.requireNonNull(submittedAt);
            Objects.requireNonNull(revision);
            if (version < 0 || ((decision == null) != (decidedAt == null)))
                throw new Rejected("INVALID_SNAPSHOT");
            if ((revision.number() == 1 && !id.equals(revision.rootApplicationId()))
                    || id.equals(revision.previousApplicationId())
                    || ((withdrawal == null) != (withdrawnAt == null))
                    || ((state == State.WITHDRAWN) != (withdrawal != null)))
                throw new Rejected("INVALID_SNAPSHOT");
            boolean terminal = state == State.APPROVED || state == State.REJECTED;
            if (terminal != (decision != null)) throw new Rejected("INVALID_SNAPSHOT");
            if (decision != null && ((state == State.APPROVED) != (decision.outcome() == Outcome.APPROVE)
                    || !submission.tenantId().equals(decision.tenantId())
                    || submission.applicantId().equals(decision.actorId()) || decidedAt.isBefore(submittedAt)))
                throw new Rejected("INVALID_SNAPSHOT");
            if (withdrawal != null && (!submission.tenantId().equals(withdrawal.tenantId())
                    || !submission.applicantId().equals(withdrawal.actorId()) || withdrawnAt.isBefore(submittedAt)))
                throw new Rejected("INVALID_SNAPSHOT");
        }
    }

    /** existingByOperation is loaded by unique (tenant, applicant, submission UUID), in the same transaction. */
    public static Application submit(UUID id, Submission command, SubmissionAuthority authority,
                                     Application existingByOperation, Instant now) {
        Objects.requireNonNull(now);
        if (authority == null || !Objects.equals(command.tenantId(), authority.tenantId())
                || !Objects.equals(command.applicantId(), authority.actorId())
                || !Objects.equals(command.ticketId(), authority.ticketId())
                || !Objects.equals(command.assignmentId(), authority.assignmentId())
                || !Objects.equals(command.departmentId(), authority.departmentId())
                || !Objects.equals(command.warehouseId(), authority.warehouseId())
                || !authority.currentEngineer() || !authority.ticketInProgress() || !authority.warehousePermitted())
            throw new Rejected("NOT_AUTHORIZED");
        if (existingByOperation != null) {
            if (!existingByOperation.submission().equals(command)) throw new Rejected("IDEMPOTENCY_CONFLICT");
            return existingByOperation;
        }
        return new Application(id, command,
                authority.qualifiedSupervisorExists() ? State.PENDING : State.WAITING_CONFIGURATION,
                0, now, null, null, new Revision(id, 1, null), null, null);
    }

    /** Creates a distinct immutable application after withdrawal; never edits the old submission. */
    public static Application resubmit(UUID id, Submission command, SubmissionAuthority authority,
                                       Application withdrawn, Application existingByOperation, Instant now) {
        Objects.requireNonNull(withdrawn); Objects.requireNonNull(now);
        if (withdrawn.state() != State.WITHDRAWN && withdrawn.state() != State.REJECTED)
            throw new Rejected("REVISION_SOURCE_REQUIRED");
        if (!withdrawn.submission().tenantId().equals(command.tenantId())
                || !withdrawn.submission().applicantId().equals(command.applicantId())
                || !withdrawn.submission().ticketId().equals(command.ticketId()))
            throw new Rejected("NOT_AUTHORIZED");
        if (command.operationId().equals(withdrawn.submission().operationId()))
            throw new Rejected("NEW_SUBMISSION_UUID_REQUIRED");
        if (id.equals(withdrawn.id()) || id.equals(withdrawn.revision().rootApplicationId()))
            throw new Rejected("NEW_APPLICATION_ID_REQUIRED");
        Instant endedAt = withdrawn.withdrawnAt() != null ? withdrawn.withdrawnAt() : withdrawn.decidedAt();
        if (now.isBefore(endedAt)) throw new Rejected("INVALID_TIME");
        // Reuse submission validation, including current assignment and exact-parameter replay validation.
        Application candidate = submit(id, command, authority, existingByOperation, now);
        Revision expected = new Revision(withdrawn.revision().rootApplicationId(),
                Math.addExact(withdrawn.revision().number(), 1), withdrawn.id());
        if (existingByOperation != null) {
            if (!candidate.revision().equals(expected)) throw new Rejected("IDEMPOTENCY_CONFLICT");
            return candidate;
        }
        return new Application(candidate.id(), candidate.submission(), candidate.state(), candidate.version(),
                candidate.submittedAt(), null, null, expected, null, null);
    }

    public static Application withdraw(Application current, Withdrawal command, WithdrawalAuthority authority,
                                       Instant now) {
        Objects.requireNonNull(current); Objects.requireNonNull(now);
        if (authority == null || !current.submission().tenantId().equals(command.tenantId())
                || !current.submission().tenantId().equals(authority.tenantId())
                || !current.submission().applicantId().equals(command.actorId())
                || !command.actorId().equals(authority.actorId()) || !authority.active()
                || !authority.currentTicketScope()) throw new Rejected("NOT_AUTHORIZED");
        if (current.withdrawal() != null && current.withdrawal().operationId().equals(command.operationId())) {
            if (!current.withdrawal().equals(command)) throw new Rejected("IDEMPOTENCY_CONFLICT");
            return current;
        }
        if (current.state() != State.PENDING && current.state() != State.WAITING_CONFIGURATION)
            throw new Rejected("NOT_WITHDRAWABLE");
        if (current.version() != command.expectedVersion()) throw new Rejected("VERSION_CONFLICT");
        if (now.isBefore(current.submittedAt())) throw new Rejected("INVALID_TIME");
        return new Application(current.id(), current.submission(), State.WITHDRAWN,
                Math.addExact(current.version(), 1), current.submittedAt(), null, null,
                current.revision(), command, now);
    }

    /** Current authorization is checked even for a replay; no old receipt restores revoked access. */
    public static Application decide(Application current, Decision command, DecisionAuthority authority, Instant now) {
        Objects.requireNonNull(current); Objects.requireNonNull(now);
        Submission submitted = current.submission();
        if (authority == null || !Objects.equals(submitted.tenantId(), command.tenantId())
                || !Objects.equals(submitted.tenantId(), authority.tenantId())
                || !Objects.equals(command.actorId(), authority.actorId())
                || !Objects.equals(submitted.departmentId(), authority.departmentId())
                || submitted.applicantId().equals(command.actorId())
                || !authority.active() || !authority.supervisor() || !authority.currentTicketScope())
            throw new Rejected("NOT_AUTHORIZED");
        if (current.decision() != null && current.decision().operationId().equals(command.operationId())) {
            if (!current.decision().equals(command)) throw new Rejected("IDEMPOTENCY_CONFLICT");
            return current;
        }
        if (!authority.ticketAllowsApproval()) throw new Rejected("TICKET_NOT_APPROVABLE");
        if (current.decision() != null) throw new Rejected("ALREADY_DECIDED");
        if (current.state() == State.WITHDRAWN) throw new Rejected("APPLICATION_WITHDRAWN");
        if (current.version() != command.expectedVersion()) throw new Rejected("VERSION_CONFLICT");
        if (now.isBefore(current.submittedAt())) throw new Rejected("INVALID_TIME");
        // A formerly unconfigured task may proceed only when the current trusted supervisor check succeeds.
        return new Application(current.id(), submitted,
                command.outcome() == Outcome.APPROVE ? State.APPROVED : State.REJECTED,
                Math.addExact(current.version(), 1), current.submittedAt(), command, now,
                current.revision(), null, null);
    }
    private static void required(String value) {
        if (value == null || value.isBlank()) throw new Rejected("REQUIRED_FIELD");
        if (characters(value) > 500) throw new Rejected("INVALID_FIELD_LENGTH");
    }
    private static int characters(String text) { return text.codePointCount(0, text.length()); }
}
