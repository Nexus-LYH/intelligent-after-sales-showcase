-- Candidate only: explicit migration and scoped grants are required before production integration.
CREATE TABLE fee_approval_application (
 tenant_id BIGINT NOT NULL, id CHAR(36) NOT NULL, ticket_id CHAR(36) NOT NULL,
 group_id CHAR(36) NOT NULL, quote_id CHAR(36) NOT NULL, quote_version BIGINT NOT NULL,
 applicant_id BIGINT NOT NULL, state VARCHAR(32) NOT NULL, row_version BIGINT NOT NULL,
 snapshot LONGTEXT NOT NULL, created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,id), UNIQUE KEY uq_fee_quote(tenant_id,group_id,quote_id),
 CHECK(row_version>=1), CHECK(state IN ('WAITING_CUSTOMER','WAITING_CONFIGURATION','PENDING_SUPERVISOR','WAITING_FINANCE','COMPLETED','REJECTED','WITHDRAWN'))
);
CREATE TABLE fee_approval_operation (
 tenant_id BIGINT NOT NULL, actor_id BIGINT NOT NULL, operation_id CHAR(36) NOT NULL,
 application_id CHAR(36) NOT NULL, command_digest CHAR(64) NOT NULL, command_json LONGTEXT NOT NULL,
 receipt LONGTEXT NOT NULL, created_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,actor_id,operation_id),
 CONSTRAINT fk_fee_op_app FOREIGN KEY(tenant_id,application_id) REFERENCES fee_approval_application(tenant_id,id)
);
CREATE TABLE fee_approval_audit (
 event_id CHAR(36) NOT NULL PRIMARY KEY, tenant_id BIGINT NOT NULL, actor_id BIGINT NOT NULL,
 operation_id CHAR(36) NOT NULL, application_id CHAR(36) NOT NULL, action VARCHAR(24) NOT NULL,
 resulting_version BIGINT NOT NULL, command_digest CHAR(64) NOT NULL, occurred_at DATETIME(3) NOT NULL,
 UNIQUE KEY uq_fee_audit_op(tenant_id,actor_id,operation_id),
 UNIQUE KEY uq_fee_audit_version(tenant_id,application_id,resulting_version)
);
CREATE TABLE fee_approval_outbox (
 event_id CHAR(36) NOT NULL PRIMARY KEY, tenant_id BIGINT NOT NULL, application_id CHAR(36) NOT NULL,
 aggregate_version BIGINT NOT NULL, event_type VARCHAR(64) NOT NULL, envelope LONGTEXT NOT NULL,
 created_at DATETIME(3) NOT NULL, UNIQUE KEY uq_fee_outbox_version(tenant_id,application_id,aggregate_version)
);
