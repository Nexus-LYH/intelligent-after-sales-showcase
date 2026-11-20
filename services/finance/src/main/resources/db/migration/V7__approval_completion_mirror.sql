CREATE TABLE finance_approval_completion_mirror (
 tenant_id BIGINT NOT NULL, completion_id CHAR(36) NOT NULL,
 customer_company_id BIGINT NOT NULL, fault_group_id CHAR(36) NOT NULL,
 application_id CHAR(36) NOT NULL, quote_id CHAR(36) NOT NULL,
 quote_version BIGINT NOT NULL, actor_id BIGINT NOT NULL,
 recording_operation_id CHAR(36) NOT NULL, fact_digest CHAR(64) NOT NULL,
 fact_json TEXT NOT NULL, receipt_json TEXT NOT NULL, recorded_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(tenant_id,completion_id), UNIQUE(tenant_id,recording_operation_id),
 UNIQUE(tenant_id,quote_id),
 FOREIGN KEY(tenant_id,recording_operation_id) REFERENCES finance_operation(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,quote_id) REFERENCES finance_quote_version(tenant_id,application_id),
 CHECK(quote_version>0 AND actor_id>0 AND customer_company_id>0)
);
