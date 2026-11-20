CREATE TABLE finance_quote_source (
 tenant_id BIGINT NOT NULL,
 operation_id CHAR(36) NOT NULL,
 fault_group_id CHAR(36) NOT NULL,
 actor_id BIGINT NOT NULL,
 customer_company_id BIGINT NOT NULL,
 application_id CHAR(36) NOT NULL,
 expected_group_version BIGINT NOT NULL,
 command_digest CHAR(64) NOT NULL,
 command_json TEXT NOT NULL,
 details_json TEXT NOT NULL,
 source_state VARCHAR(16) NOT NULL,
 result_digest CHAR(64) NULL,
 registered_at TIMESTAMP(3) NOT NULL,
 updated_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(tenant_id,operation_id),
 UNIQUE(tenant_id,application_id),
 FOREIGN KEY(tenant_id,fault_group_id) REFERENCES finance_fee_group(tenant_id,fault_group_id),
 CHECK(expected_group_version>=0),
 CHECK(CASE WHEN source_state='IN_PROGRESS' THEN 1 WHEN source_state='COMMITTED' THEN 1 WHEN source_state='ABORTED' THEN 1 ELSE 0 END=1),
 CHECK((source_state='COMMITTED' AND result_digest IS NOT NULL) OR (source_state<>'COMMITTED' AND result_digest IS NULL))
);
CREATE TABLE finance_quote_source_event (
 tenant_id BIGINT NOT NULL,
 operation_id CHAR(36) NOT NULL,
 source_state VARCHAR(16) NOT NULL,
 reason VARCHAR(500) NOT NULL,
 fact_json TEXT NOT NULL,
 recorded_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(tenant_id,operation_id,source_state),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES finance_quote_source(tenant_id,operation_id)
);
CREATE TABLE finance_quote_source_outbox (
 tenant_id BIGINT NOT NULL,
 operation_id CHAR(36) NOT NULL,
 event_type VARCHAR(48) NOT NULL,
 payload_json TEXT NOT NULL,
 recorded_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES finance_quote_source(tenant_id,operation_id)
);
