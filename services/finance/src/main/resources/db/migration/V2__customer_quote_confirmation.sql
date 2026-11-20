CREATE TABLE finance_customer_confirmation (
 tenant_id BIGINT NOT NULL, application_id CHAR(36) NOT NULL, fault_group_id CHAR(36) NOT NULL,
 operation_id CHAR(36) NOT NULL, actor_id BIGINT NOT NULL, quote_group_version BIGINT NOT NULL,
 confirmed_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(tenant_id,application_id), UNIQUE(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,application_id) REFERENCES finance_quote_version(tenant_id,application_id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES finance_operation(tenant_id,operation_id)
);
-- A local durable request, NOT an approval decision or an already delivered message.
CREATE TABLE finance_approval_request (
 tenant_id BIGINT NOT NULL, application_id CHAR(36) NOT NULL, fault_group_id CHAR(36) NOT NULL,
 operation_id CHAR(36) NOT NULL, basis_json TEXT NOT NULL, recorded_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(tenant_id,application_id), UNIQUE(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,application_id) REFERENCES finance_quote_version(tenant_id,application_id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES finance_operation(tenant_id,operation_id)
);
