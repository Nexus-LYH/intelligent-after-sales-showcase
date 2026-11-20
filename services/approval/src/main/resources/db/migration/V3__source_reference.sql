CREATE TABLE approval_source_reference (
 tenant_id BIGINT NOT NULL,
 application_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_id BIGINT NOT NULL,
 ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 round_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 assignment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 company_id BIGINT NOT NULL,
 department_id BIGINT NOT NULL,
 command_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 recorded_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,application_id),
 UNIQUE KEY uq_source_operation(tenant_id,actor_id,operation_id),
 KEY ix_source_ticket(tenant_id,ticket_id,application_id),
 FOREIGN KEY(application_id) REFERENCES parts_application(id)
) ENGINE=InnoDB;
-- Immutable source binding. Runtime requires SELECT/INSERT, never UPDATE/DELETE.
