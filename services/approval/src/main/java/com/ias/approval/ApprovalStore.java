package com.ias.approval;

import java.util.UUID;
import java.util.function.Function;
import static com.ias.approval.PartsApproval.*;

public interface ApprovalStore {
    record Receipt(String commandHash, String action, UUID targetId, Application result) {}
    interface Transaction {
        /** Immutable committed receipt lookup, without operation/application locks. */
        default Receipt peekReceipt(String tenantId,String actorId,UUID operationId){return receipt(tenantId,actorId,operationId);}
        /** New writes only; same Connection, before receipt/application locking. */
        default void guardMutation(String tenant,String actor,UUID operation,String digest,String action,String ticket,String mode,UUID target){throw new ApprovalFailure(503,"FINANCE_GENERATION_UNAVAILABLE");}
        Receipt receipt(String tenantId, String actorId, UUID operationId);
        void reserve(String tenantId, String actorId, UUID operationId, String hash, String action);
        Application application(String tenantId, UUID applicationId);
        void insert(Application application);
        default void bindExecutionPolicy(Application application,String chargeMode){if(chargeMode!=null)throw new ApprovalFailure(503,"EXECUTION_POLICY_UNAVAILABLE");}
        default void bindSource(Application application,ApprovalAccess.SourceReference reference,String digest){throw new ApprovalFailure(503,"SOURCE_BINDING_UNAVAILABLE");}
        void update(Application previous, Application result);
        void complete(String tenantId, String actorId, UUID operationId, String hash, String action,
                      Application result, String eventType);
    }
    <T> T transact(Function<Transaction, T> work);
}
