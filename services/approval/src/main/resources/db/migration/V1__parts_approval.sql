-- MySQL 8.4 candidate. Run only against the separately approved approval schema, never identity/ticket.
-- Runtime account: SELECT/INSERT/UPDATE application+operation; SELECT/INSERT audit+outbox.
-- No runtime DDL/DELETE or UPDATE on audit/outbox; no credentials or account creation here.
CREATE TABLE parts_application (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  tenant_id BIGINT NOT NULL,
  applicant_id BIGINT NOT NULL,
  root_application_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  revision_number INT NOT NULL,
  previous_application_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  row_version BIGINT NOT NULL,
  snapshot JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_parts_previous (tenant_id, previous_application_id),
  UNIQUE KEY uq_parts_revision (tenant_id, root_application_id, revision_number),
  KEY ix_parts_applicant (tenant_id, applicant_id, created_at, id),
  CONSTRAINT ck_parts_version CHECK (row_version >= 0 AND revision_number >= 1),
  CONSTRAINT ck_parts_state CHECK (state IN ('WAITING_CONFIGURATION','PENDING','APPROVED','REJECTED','WITHDRAWN')),
  CONSTRAINT ck_parts_tenant CHECK (tenant_id > 0 AND applicant_id > 0)
) ENGINE=InnoDB;

CREATE TABLE approval_operation (
  tenant_id BIGINT NOT NULL,
  actor_id BIGINT NOT NULL,
  operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  command_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  target_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  receipt JSON NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (tenant_id, actor_id, operation_id),
  CONSTRAINT ck_approval_action CHECK (action IN ('SUBMIT','RESUBMIT','DECIDE','WITHDRAW')),
  CONSTRAINT ck_approval_receipt CHECK ((target_id IS NULL) = (receipt IS NULL))
) ENGINE=InnoDB;

CREATE TABLE approval_audit (
  audit_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  tenant_id BIGINT NOT NULL,
  actor_id BIGINT NOT NULL,
  operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  target_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  resulting_version BIGINT NOT NULL,
  command_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  occurred_at DATETIME(3) NOT NULL,
  PRIMARY KEY (audit_id),
  UNIQUE KEY uq_approval_audit_operation (tenant_id, actor_id, operation_id)
) ENGINE=InnoDB;

CREATE TABLE approval_outbox (
  event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  tenant_id BIGINT NOT NULL,
  aggregate_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  aggregate_version BIGINT NOT NULL,
  event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  envelope JSON NOT NULL,
  occurred_at DATETIME(3) NOT NULL,
  PRIMARY KEY (event_id),
  UNIQUE KEY uq_approval_event_fact (tenant_id, aggregate_id, aggregate_version, event_type)
) ENGINE=InnoDB;
-- Immutable outbox only in this slice. BE-N delivery/lease/retry/dead-letter state must use a separate table.
