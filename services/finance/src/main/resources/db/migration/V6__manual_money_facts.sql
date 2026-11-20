-- Explicit staff verification of offline funds, NOT a bank/provider confirmation.
CREATE TABLE finance_manual_money_fact (
 tenant_id BIGINT NOT NULL, operation_id CHAR(36) NOT NULL,
 fault_group_id CHAR(36) NOT NULL, actor_id BIGINT NOT NULL,
 business_id CHAR(36) NOT NULL, movement VARCHAR(16) NOT NULL,
 original_payment_id CHAR(36) NULL, amount_cents BIGINT NOT NULL,
 external_reference VARCHAR(100) NOT NULL, occurred_on DATE NOT NULL,
 method VARCHAR(40) NOT NULL, private_file_id CHAR(36) NOT NULL,
 evidence_digest CHAR(64) NOT NULL, command_digest CHAR(64) NOT NULL,
 fact_json TEXT NOT NULL, recorded_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(tenant_id,operation_id), UNIQUE(tenant_id,business_id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES finance_operation(tenant_id,operation_id),
 CHECK(amount_cents>0), CHECK(CASE WHEN movement='RECEIVED' THEN 1 WHEN movement='REFUNDED' THEN 1 WHEN movement='REBOOKED' THEN 1 ELSE 0 END=1)
);
