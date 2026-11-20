CREATE TABLE ticket_approval_settlement(
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 tenant_id BIGINT NOT NULL,ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 approval_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 decision_operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 command_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 state VARCHAR(16) NOT NULL,settled_at DATETIME(3) NOT NULL,
 FOREIGN KEY(operation_id) REFERENCES ticket_approval_reference(operation_id),
 CHECK(state='REJECTED' OR state='WITHDRAWN' OR state='APPROVED'),
 KEY ix_approval_settlement_ticket(tenant_id,ticket_id,state)
) ENGINE=InnoDB;
