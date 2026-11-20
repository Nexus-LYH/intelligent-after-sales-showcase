package com.ias.approval;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.json.JsonMapper;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import static com.ias.approval.PartsApproval.*;

public final class ApprovalService {
    public record SubmitBody(UUID operationId, String ticketId, String assignmentId, String departmentId,
                             String warehouseId, String partId, int quantity, String purpose, UUID previousApplicationId,String chargeMode) {
        public SubmitBody(UUID operationId,String ticketId,String assignmentId,String departmentId,String warehouseId,String partId,int quantity,String purpose,UUID previousApplicationId){this(operationId,ticketId,assignmentId,departmentId,warehouseId,partId,quantity,purpose,previousApplicationId,null);}
        public SubmitBody {
            canonicalUuid(ticketId); canonicalUuid(assignmentId);
            positiveId(departmentId);
            positiveId(warehouseId); positiveId(partId);
            if(chargeMode!=null&&!java.util.Set.of("FREE","CUSTOMER_PAID").contains(chargeMode))throw new ApprovalFailure(400,"INVALID_CHARGE_MODE");
        }
    }
    public record DecisionBody(UUID operationId, String expectedVersion, Outcome outcome, String reason) {}
    public record WithdrawBody(UUID operationId, String expectedVersion, String reason) {}
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ApprovalStore store;
    private final ApprovalAccess access;
    private final Clock clock;
    public ApprovalService(ApprovalStore store, ApprovalAccess access, Clock clock) {
        this.store = store; this.access = access; this.clock = clock;
    }
    public Application submit(HttpServletRequest request, SubmitBody body) {
        var principal = access.principal(request);
        var command = new Submission(body.operationId(), principal.tenantId(), principal.actorId(), body.ticketId(),
                body.assignmentId(), body.departmentId(), body.warehouseId(), body.partId(), body.quantity(), body.purpose());
        String action = body.previousApplicationId() == null ? "SUBMIT" : "RESUBMIT";
        // Missing mode preserves the original command digest and old UUID replay semantics.
        String hash = body.chargeMode()==null?hash(action, body.previousApplicationId(), command):policyHash(action,body.previousApplicationId(),command,body.chargeMode());
        return store.transact(tx -> {
            var old = tx.peekReceipt(principal.tenantId(), principal.actorId(), command.operationId());
            if(old==null){
                tx.guardMutation(principal.tenantId(),principal.actorId(),command.operationId(),hash,action,body.ticketId(),body.chargeMode(),null);
                old=tx.receipt(principal.tenantId(),principal.actorId(),command.operationId());
            }
            if (old != null) {
                checkReceipt(old, hash, action);
                var current = tx.application(principal.tenantId(), old.targetId());
                access.read(request, principal, current);
                // A historical read receipt is not current permission to POST a business command.
                var currentSubmission = access.submission(request, principal, command);
                return PartsApproval.submit(old.targetId(), command, currentSubmission, old.result(), clock.instant());
            }
            tx.reserve(principal.tenantId(), principal.actorId(), command.operationId(), hash, action);
            var authority = access.submission(request, principal, command);
            Application result;
            if (body.previousApplicationId() == null) result = PartsApproval.submit(UUID.randomUUID(), command, authority, null, clock.instant());
            else {
                var previous = tx.application(principal.tenantId(), body.previousApplicationId());
                access.read(request, principal, previous);
                result = resubmit(UUID.randomUUID(), command, authority, previous, null, clock.instant());
            }
            var sourceReference=access.registerReference(request, principal, command, hash);
            tx.insert(result);
            tx.bindExecutionPolicy(result,body.chargeMode());
            if(sourceReference!=null)tx.bindSource(result,sourceReference,hash);
            tx.complete(principal.tenantId(), principal.actorId(), command.operationId(), hash, action, result, "approval.submitted");
            return result;
        });
    }
    public Application decide(HttpServletRequest request, UUID target, DecisionBody body) {
        var principal = access.principal(request);
        var command = new Decision(body.operationId(), principal.tenantId(), principal.actorId(), version(body.expectedVersion()), body.outcome(), body.reason());
        String hash = hash("DECIDE", target, command);
        return store.transact(tx -> {
            var old = tx.peekReceipt(principal.tenantId(), principal.actorId(), command.operationId());
            if(old==null){tx.guardMutation(principal.tenantId(),principal.actorId(),command.operationId(),hash,body.outcome()==Outcome.REJECT?"DECIDE_REJECT":"DECIDE",null,null,target);old=tx.receipt(principal.tenantId(),principal.actorId(),command.operationId());}
            if (old != null) checkReceipt(old, hash, "DECIDE");
            else tx.reserve(principal.tenantId(), principal.actorId(), command.operationId(), hash, "DECIDE");
            var previous = tx.application(principal.tenantId(), target);
            var authority = access.decision(request, principal, previous);
            if (old != null) return PartsApproval.decide(old.result(), command, authority, clock.instant());
            var result = PartsApproval.decide(previous, command, authority, clock.instant());
            tx.update(previous, result);
            tx.complete(principal.tenantId(), principal.actorId(), command.operationId(), hash, "DECIDE", result,
                    command.outcome() == Outcome.APPROVE ? "approval.approved" : "approval.rejected");
            return result;
        });
    }
    public Application withdraw(HttpServletRequest request, UUID target, WithdrawBody body) {
        var principal = access.principal(request);
        var command = new Withdrawal(body.operationId(), principal.tenantId(), principal.actorId(), version(body.expectedVersion()), body.reason());
        String hash = hash("WITHDRAW", target, command);
        return store.transact(tx -> {
            var old = tx.peekReceipt(principal.tenantId(), principal.actorId(), command.operationId());
            if(old==null){tx.guardMutation(principal.tenantId(),principal.actorId(),command.operationId(),hash,"WITHDRAW",null,null,target);old=tx.receipt(principal.tenantId(),principal.actorId(),command.operationId());}
            if (old != null) checkReceipt(old, hash, "WITHDRAW");
            else tx.reserve(principal.tenantId(), principal.actorId(), command.operationId(), hash, "WITHDRAW");
            var previous = tx.application(principal.tenantId(), target);
            var authority = access.withdrawal(request, principal, previous);
            if (old != null) return PartsApproval.withdraw(old.result(), command, authority, clock.instant());
            var result = PartsApproval.withdraw(previous, command, authority, clock.instant());
            tx.update(previous, result);
            tx.complete(principal.tenantId(), principal.actorId(), command.operationId(), hash, "WITHDRAW", result, "approval.withdrawn");
            return result;
        });
    }
    public Application get(HttpServletRequest request, UUID target) {
        var principal = access.principal(request);
        return store.transact(tx -> {
            var result = tx.application(principal.tenantId(), target);
            access.read(request, principal, result);
            return result;
        });
    }
    public ApprovalAccess.Principal ticketRead(HttpServletRequest request,String ticket){
        var principal=access.principal(request);access.ticketRead(request,principal,ticket);return principal;
    }
    public ApprovalView currentView(HttpServletRequest request, UUID target) {
        var principal=access.principal(request);
        return store.transact(tx->{
            var result=tx.application(principal.tenantId(),target);access.read(request,principal,result);
            return ApprovalView.of(result).withActions(access.actions(request,principal,result));
        });
    }
    public Application operation(HttpServletRequest request, UUID operation) {
        var principal = access.principal(request);
        return store.transact(tx -> {
            var receipt = tx.receipt(principal.tenantId(), principal.actorId(), operation);
            if (receipt == null) throw new ApprovalFailure(404, "OPERATION_NOT_OBSERVED");
            var current = tx.application(principal.tenantId(), receipt.targetId());
            access.read(request, principal, current);
            return receipt.result();
        });
    }
    private static void checkReceipt(ApprovalStore.Receipt receipt, String hash, String action) {
        if (!receipt.commandHash().equals(hash) || !receipt.action().equals(action))
            throw new ApprovalFailure(409, "IDEMPOTENCY_CONFLICT");
    }
    private static String hash(String action, UUID target, Object command) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsBytes(Arrays.asList(action, target, command))));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String policyHash(String action,UUID target,Object command,String mode){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(Arrays.asList(action,target,command,mode))));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static long version(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) throw new ApprovalFailure(400, "INVALID_VERSION");
        try { return Long.parseLong(value); }
        catch (NumberFormatException failure) { throw new ApprovalFailure(400, "INVALID_VERSION"); }
    }
    private static void canonicalUuid(String value) {
        try { if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException failure) { throw new ApprovalFailure(400, "INVALID_OBJECT_ID"); }
    }
    private static void positiveId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new ApprovalFailure(400, "INVALID_OBJECT_ID");
        try { Long.parseLong(value); } catch (NumberFormatException failure) { throw new ApprovalFailure(400, "INVALID_OBJECT_ID"); }
    }
}
