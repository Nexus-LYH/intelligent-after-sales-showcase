-- Dormant until the trusted owner protocol is implemented. No timeout releases a permit.
CREATE TABLE ticket_fee_part_permit (
 operation_id CHAR(36) NOT NULL PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 actor_user_id BIGINT NOT NULL,
 company_id BIGINT NOT NULL,
 ticket_id CHAR(36) NOT NULL,
 round_id CHAR(36) NOT NULL,
 assignment_id CHAR(36) NOT NULL,
 command_digest CHAR(64) NOT NULL,
 binding_json TEXT NOT NULL,
 recorded_at DATETIME(3) NOT NULL,
 INDEX ix_ticket_fee_part_permit_ticket (tenant_id,ticket_id)
);
CREATE TABLE ticket_fee_part_permit_result (
 operation_id CHAR(36) NOT NULL PRIMARY KEY,
 result_type VARCHAR(32) NOT NULL,
 result_digest CHAR(64) NOT NULL,
 recorded_at DATETIME(3) NOT NULL,
 CONSTRAINT fk_fee_part_result_permit FOREIGN KEY (operation_id) REFERENCES ticket_fee_part_permit(operation_id),
 CONSTRAINT ck_fee_part_result_type CHECK (result_type IN ('COMMITTED','PROVEN_NO_EXECUTION'))
);
