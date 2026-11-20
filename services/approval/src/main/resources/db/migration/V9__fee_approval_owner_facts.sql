CREATE TABLE fee_approval_binding (
 tenant_id BIGINT NOT NULL, application_id CHAR(36) NOT NULL, coverage_digest CHAR(64) NOT NULL,
 quote_context LONGTEXT NOT NULL, ticket_context LONGTEXT NOT NULL, lines_json LONGTEXT NOT NULL,
 recorded_at DATETIME(3) NOT NULL, PRIMARY KEY(tenant_id,application_id)
);
CREATE TABLE fee_approval_decision (
 tenant_id BIGINT NOT NULL, application_id CHAR(36) NOT NULL, node VARCHAR(16) NOT NULL,
 receipt_id CHAR(36) NOT NULL, actor_id BIGINT NOT NULL, decision VARCHAR(16) NOT NULL,
 resulting_version BIGINT NOT NULL, recorded_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,application_id,node), UNIQUE(tenant_id,receipt_id)
);
CREATE TABLE fee_approval_completion (
 tenant_id BIGINT NOT NULL, application_id CHAR(36) NOT NULL, completion_id CHAR(36) NOT NULL,
 company_id BIGINT NOT NULL, group_id CHAR(36) NOT NULL,
 payload LONGTEXT NOT NULL, recorded_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,application_id), UNIQUE(tenant_id,completion_id), INDEX ix_fee_completion_group(tenant_id,company_id,group_id)
);
-- Append-only logical terminal overlay: original application/operation snapshots are immutable history.
CREATE TABLE fee_approval_termination (
 tenant_id BIGINT NOT NULL, application_id CHAR(36) NOT NULL, freeze_id CHAR(36) NOT NULL,
 generation BIGINT NOT NULL, resulting_version BIGINT NOT NULL, receipt_id CHAR(36) NOT NULL,
 payload LONGTEXT NOT NULL, recorded_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,application_id), UNIQUE(tenant_id,freeze_id,application_id), UNIQUE(tenant_id,receipt_id)
);
