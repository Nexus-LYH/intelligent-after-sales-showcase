-- Producer intent is durable even if the approval commit/ACK is unknown.
-- No automatic release: external cancellation coordination is a later owner protocol.
CREATE TABLE ticket_approval_reference (
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL, actor_user_id BIGINT NOT NULL,
 ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 round_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 assignment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 command_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 recorded_at DATETIME(3) NOT NULL,
 FOREIGN KEY(round_id,ticket_id) REFERENCES ticket_round(id,ticket_id),
 FOREIGN KEY(assignment_id) REFERENCES ticket_assignment(id),
 KEY ix_approval_reference_ticket(ticket_id,operation_id)
) ENGINE=InnoDB;
