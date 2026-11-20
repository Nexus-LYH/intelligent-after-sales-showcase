CREATE TABLE finance_money_account (
 tenant_id BIGINT NOT NULL,
 fault_group_id CHAR(36) NOT NULL,
 row_version BIGINT NOT NULL,
 receivable_cents BIGINT NOT NULL,
 collected_cents BIGINT NOT NULL,
 refunded_cents BIGINT NOT NULL,
 settled_cents BIGINT NOT NULL,
 overpayment_cents BIGINT NOT NULL,
 state_json TEXT NOT NULL,
 updated_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(tenant_id,fault_group_id),
 FOREIGN KEY(tenant_id,fault_group_id) REFERENCES finance_fee_group(tenant_id,fault_group_id),
 CHECK(row_version>0 AND receivable_cents>=0 AND collected_cents>=0 AND refunded_cents>=0 AND settled_cents>=0 AND overpayment_cents>=0),
 CHECK(collected_cents-refunded_cents=settled_cents+overpayment_cents),
 CHECK(settled_cents<=receivable_cents)
);
CREATE TABLE finance_money_entry (
 tenant_id BIGINT NOT NULL,
 operation_id CHAR(36) NOT NULL,
 fault_group_id CHAR(36) NOT NULL,
 account_version BIGINT NOT NULL,
 action_name VARCHAR(32) NOT NULL,
 command_json TEXT NOT NULL,
 receipt_json TEXT NOT NULL,
 recorded_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(tenant_id,operation_id),
 UNIQUE(tenant_id,fault_group_id,account_version),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES finance_operation(tenant_id,operation_id)
);
CREATE TABLE finance_money_reference (
 tenant_id BIGINT NOT NULL,
 reference_kind VARCHAR(16) NOT NULL,
 external_reference VARCHAR(100) NOT NULL,
 operation_id CHAR(36) NOT NULL,
 fault_group_id CHAR(36) NOT NULL,
 PRIMARY KEY(tenant_id,reference_kind,external_reference),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES finance_operation(tenant_id,operation_id)
);
