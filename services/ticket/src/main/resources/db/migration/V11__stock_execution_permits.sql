CREATE TABLE ticket_stock_execution (
 operation_id CHAR(36) NOT NULL PRIMARY KEY,
 tenant_id BIGINT NOT NULL, actor_user_id BIGINT NOT NULL,
 ticket_id CHAR(36) NOT NULL, round_id CHAR(36) NOT NULL,
 assignment_id CHAR(36) NOT NULL, approval_id CHAR(36) NOT NULL,
 warehouse_id BIGINT NOT NULL, phase VARCHAR(16) NOT NULL,
 command_digest CHAR(64) NOT NULL, recorded_at DATETIME(3) NOT NULL,
 KEY stock_execution_ticket(ticket_id),
 CHECK(phase IN ('RESERVE','ISSUE','USE','RETURN','RELEASE'))
);
CREATE TABLE ticket_stock_execution_result (
 operation_id CHAR(36) NOT NULL PRIMARY KEY,
 result_digest CHAR(64) NOT NULL, completed_at DATETIME(3) NOT NULL,
 FOREIGN KEY(operation_id) REFERENCES ticket_stock_execution(operation_id)
);
