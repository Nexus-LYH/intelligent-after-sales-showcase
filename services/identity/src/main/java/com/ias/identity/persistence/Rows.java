package com.ias.identity.persistence;

import java.time.LocalDateTime;

/** Persistence-only objects; never serialized as API responses or logged. */
public final class Rows {
    private Rows() { }
    public static class Tenant {
        public Long id;
        public String codeNormalized, name;
        public int status;
        public long securityEpoch;
    }
    public static class User {
        public Long id, tenantId, departmentId;
        public String realm, loginNamespaceId, accountNormalized, identityType, displayName, passwordVerifier;
        public int status, mustChangePassword, bindingState;
        public long securityVersion;
        public LocalDateTime updatedAt;
    }
    public static class Context {
        public Long id, userId, tenantId, issuedUserSecurityVersion, issuedTenantSecurityEpoch;
        public byte[] tokenHash;
        public String kind, accessState, preauthState, deliveryState, csrfToken, loginAttemptId, revokeReasonCode;
        public LocalDateTime issuedAt, expiresAt, consumedAt, deliveryConfirmDeadline, confirmedAt, revokedAt, updatedAt;
        public long rowVersion;
    }
    public static class Attempt {
        public String attemptId, expectedRealm, expectedTenantCodeNormalized, expectedAccountNormalized, status, failureCode;
        public Long preauthContextId;
        public LocalDateTime createdAt, completedAt, retainUntil;
        public long rowVersion;
    }
    public static class Operation {
        public Long operationId,actorUserId,targetUserId;
        public String actorNamespaceId,idempotencyKey,reason,traceId;
        public byte[] requestDigest;
        public int digestSchemaVersion;
        public long expectedSecurityVersion,newSecurityVersion;
        public LocalDateTime revokedAt;
    }
}
