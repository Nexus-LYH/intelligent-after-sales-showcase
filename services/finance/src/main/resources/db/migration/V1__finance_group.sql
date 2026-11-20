CREATE TABLE finance_fee_group (
 tenant_id BIGINT NOT NULL, fault_group_id CHAR(36) NOT NULL, customer_company_id BIGINT NOT NULL,
 row_version BIGINT NOT NULL, q_cents BIGINT NOT NULL, c_cents BIGINT NOT NULL,
 h_cents BIGINT NOT NULL, b_cents BIGINT NOT NULL, ever_required_finance BOOLEAN NOT NULL,
 first_trigger_application_id CHAR(36), first_trigger_rule_version VARCHAR(100), first_trigger_group_version BIGINT,
 state_json TEXT NOT NULL, updated_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(tenant_id,fault_group_id), CHECK(tenant_id>0 AND customer_company_id>0),
 CHECK(row_version>=0 AND q_cents>=0 AND c_cents>=0 AND h_cents>=0),
 CHECK(b_cents>=q_cents AND b_cents>=c_cents AND b_cents>=h_cents),
 CHECK((ever_required_finance=FALSE AND first_trigger_application_id IS NULL AND first_trigger_rule_version IS NULL AND first_trigger_group_version IS NULL)
 OR (ever_required_finance=TRUE AND first_trigger_application_id IS NOT NULL AND first_trigger_rule_version IS NOT NULL AND first_trigger_group_version IS NOT NULL))
);
CREATE TABLE finance_operation (
 tenant_id BIGINT NOT NULL, operation_id CHAR(36) NOT NULL, fault_group_id CHAR(36) NOT NULL,
 actor_id BIGINT NOT NULL, action_name VARCHAR(32) NOT NULL, request_hash CHAR(64) NOT NULL,
 receipt_json TEXT NOT NULL, recorded_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,fault_group_id) REFERENCES finance_fee_group(tenant_id,fault_group_id)
);
CREATE TABLE finance_quote_version (
 tenant_id BIGINT NOT NULL, application_id CHAR(36) NOT NULL, fault_group_id CHAR(36) NOT NULL,
 group_version BIGINT NOT NULL, operation_id CHAR(36) NOT NULL, quote_json TEXT NOT NULL,
 basis_json TEXT NOT NULL, recorded_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(tenant_id,application_id), UNIQUE(tenant_id,fault_group_id,group_version), UNIQUE(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES finance_operation(tenant_id,operation_id)
);
CREATE TABLE finance_cost_fact (
 tenant_id BIGINT NOT NULL, fact_id CHAR(36) NOT NULL, fault_group_id CHAR(36) NOT NULL,
 operation_id CHAR(36) NOT NULL, delta_cents BIGINT NOT NULL, correction_of CHAR(36),
 evidence_reference VARCHAR(500) NOT NULL, recorded_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(tenant_id,fact_id), UNIQUE(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES finance_operation(tenant_id,operation_id),
 CHECK(delta_cents<>0), CHECK((delta_cents>0 AND correction_of IS NULL) OR (delta_cents<0 AND correction_of IS NOT NULL))
);
CREATE TABLE finance_approval_fact (
 tenant_id BIGINT NOT NULL, application_id CHAR(36) NOT NULL, completion_event_id CHAR(36) NOT NULL,
 fault_group_id CHAR(36) NOT NULL, operation_id CHAR(36) NOT NULL, fact_json TEXT NOT NULL, recorded_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(tenant_id,application_id), UNIQUE(tenant_id,completion_event_id), UNIQUE(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES finance_operation(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,application_id) REFERENCES finance_quote_version(tenant_id,application_id)
);
CREATE TABLE finance_audit (
 tenant_id BIGINT NOT NULL, operation_id CHAR(36) NOT NULL, actor_id BIGINT NOT NULL,
 action_name VARCHAR(32) NOT NULL, fault_group_id CHAR(36) NOT NULL, group_version BIGINT NOT NULL,
 recorded_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(tenant_id,operation_id), FOREIGN KEY(tenant_id,operation_id) REFERENCES finance_operation(tenant_id,operation_id)
);
CREATE TABLE finance_outbox (
 tenant_id BIGINT NOT NULL, event_id CHAR(36) NOT NULL, operation_id CHAR(36) NOT NULL,
 event_type VARCHAR(50) NOT NULL, payload_json TEXT NOT NULL, recorded_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(tenant_id,event_id), UNIQUE(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES finance_operation(tenant_id,operation_id)
);
