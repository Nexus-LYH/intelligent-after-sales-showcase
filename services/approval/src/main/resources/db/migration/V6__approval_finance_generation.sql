-- Candidate local owner barrier only; no production source adapter or global freeze ACK.
CREATE TABLE approval_finance_generation (
 tenant_id BIGINT NOT NULL, ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 generation BIGINT NOT NULL, state VARCHAR(16) NOT NULL,
 PRIMARY KEY(tenant_id,ticket_id), CHECK(generation>=0), CHECK(state='OPEN' OR state='FENCED')
);
CREATE TABLE approval_finance_write_intent (
 tenant_id BIGINT NOT NULL, actor_id BIGINT NOT NULL, operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, generation BIGINT NOT NULL,
 action VARCHAR(16) NOT NULL, charge_mode VARCHAR(16) NOT NULL, command_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 recorded_at DATETIME(3) NOT NULL, PRIMARY KEY(tenant_id,actor_id,operation_id),
 INDEX ix_approval_finance_intent_scope(tenant_id,ticket_id)
);
CREATE TABLE approval_finance_fence (
 tenant_id BIGINT NOT NULL, freeze_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, generation BIGINT NOT NULL,
 binding_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 binding_json JSON NOT NULL, initial_snapshot JSON NOT NULL, recorded_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,freeze_id), UNIQUE(tenant_id,ticket_id,generation)
);
CREATE TABLE approval_finance_owner_ack (
 tenant_id BIGINT NOT NULL, freeze_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 ack_json JSON NOT NULL, recorded_at DATETIME(3) NOT NULL, PRIMARY KEY(tenant_id,freeze_id)
);
CREATE TABLE approval_finance_fence_audit (
 audit_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 tenant_id BIGINT NOT NULL, freeze_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 event_type VARCHAR(32) NOT NULL, binding_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 recorded_at DATETIME(3) NOT NULL, UNIQUE(tenant_id,freeze_id,event_type)
);
