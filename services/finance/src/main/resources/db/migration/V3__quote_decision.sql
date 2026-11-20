CREATE TABLE finance_quote_decision (
 tenant_id BIGINT NOT NULL,
 application_id CHAR(36) NOT NULL,
 fault_group_id CHAR(36) NOT NULL,
 operation_id CHAR(36) NOT NULL,
 actor_id BIGINT NOT NULL,
 action_name VARCHAR(32) NOT NULL,
 reason VARCHAR(500) NOT NULL,
 quote_group_version BIGINT NOT NULL,
 decided_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(tenant_id,application_id),
 UNIQUE(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,application_id) REFERENCES finance_quote_version(tenant_id,application_id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES finance_operation(tenant_id,operation_id),
 CHECK(CASE WHEN action_name='CUSTOMER_QUOTE_REJECTED' THEN 1 WHEN action_name='QUOTE_WITHDRAWN' THEN 1 ELSE 0 END=1)
);
