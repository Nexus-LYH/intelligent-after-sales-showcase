-- Execution and explicit refusal serialize on this durable operation row.
-- Missing/PENDING is never proof that a delayed execution cannot commit.
CREATE TABLE stock_part_execution_gate (
 tenant_id BIGINT NOT NULL, operation_id CHAR(36) NOT NULL,
 command_digest CHAR(64) NOT NULL, state VARCHAR(16) NOT NULL,
 recorded_at DATETIME(3) NOT NULL,
 PRIMARY KEY (tenant_id,operation_id),
 CHECK (CASE WHEN state='PENDING' THEN 1 WHEN state='COMMITTED' THEN 1 WHEN state='REJECTED' THEN 1 ELSE 0 END=1)
);
