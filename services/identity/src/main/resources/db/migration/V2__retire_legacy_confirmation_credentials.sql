-- Run after stopping the old local application; this is not a rolling deployment protocol.
-- Legacy CONFIRMED rows cannot prove their credential was handed out after a timely commit.
-- Retire existing AUTH sessions once; preserve all history, PREAUTH, users and security versions.
START TRANSACTION;
SET @identity_v2_time = UTC_TIMESTAMP(3);
INSERT INTO identity_security_audit
 (occurred_at,action,actor_type,target_type,target_id,tenant_id,result,reason_code,login_attempt_id,trace_id)
SELECT @identity_v2_time,'CONFIRMATION_PROTOCOL_UPGRADE','SYSTEM','SESSION',id,tenant_id,'SUCCESS',
 'CONFIRMATION_PROTOCOL_UPGRADE',login_attempt_id,'migration-identity-v2'
FROM identity_session_context WHERE kind='AUTH' AND revoked_at IS NULL;
UPDATE identity_session_context
SET revoked_at=@identity_v2_time,revoke_reason_code='CONFIRMATION_PROTOCOL_UPGRADE',
    row_version=row_version+1,updated_at=@identity_v2_time
WHERE kind='AUTH' AND revoked_at IS NULL;
COMMIT;
