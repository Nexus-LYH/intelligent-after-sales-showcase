CREATE TABLE finance_receivable_execution_fact (
 tenant_id BIGINT NOT NULL, bill_id CHAR(36) NOT NULL,
 fault_group_id CHAR(36) NOT NULL, completion_id CHAR(36) NOT NULL,
 operation_id CHAR(36) NOT NULL, authority_digest CHAR(64) NOT NULL,
 execution_receipts_digest CHAR(64) NOT NULL, recorded_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(tenant_id,bill_id), UNIQUE(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES finance_operation(tenant_id,operation_id),
 CHECK(CHAR_LENGTH(authority_digest)=64 AND CHAR_LENGTH(execution_receipts_digest)=64)
);

CREATE TABLE finance_money_terminal_fact (
 tenant_id BIGINT NOT NULL, operation_id CHAR(36) NOT NULL,
 fault_group_id CHAR(36) NOT NULL, customer_company_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL, account_version BIGINT NOT NULL,
 action_name VARCHAR(32) NOT NULL, business_id CHAR(36) NOT NULL,
 bill_id CHAR(36) NOT NULL, completion_id CHAR(36) NOT NULL,
 original_payment_id CHAR(36) NULL, amount_cents BIGINT NOT NULL,
 authority_digest CHAR(64) NOT NULL, execution_receipts_digest CHAR(64) NOT NULL,
 correction_evidence_id CHAR(36) NULL,
 private_file_id CHAR(36) NULL, evidence_digest CHAR(64) NULL,
 terminal_event_type VARCHAR(50) NOT NULL, terminal_event_version VARCHAR(16) NOT NULL,
 terminal_payload_json TEXT NOT NULL, recorded_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(tenant_id,operation_id),
 UNIQUE(tenant_id,business_id),
 UNIQUE(tenant_id,fault_group_id,account_version),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES finance_operation(tenant_id,operation_id),
 CHECK(tenant_id>0 AND customer_company_id>0 AND actor_id>0 AND account_version>0),
 CHECK(amount_cents>=0), CHECK(CHAR_LENGTH(authority_digest)=64),
 CHECK((private_file_id IS NULL AND evidence_digest IS NULL) OR
       (private_file_id IS NOT NULL AND CHAR_LENGTH(evidence_digest)=64)),
 CHECK(terminal_event_version='1')
);
