-- W candidate only: no row here is a P/S owner ACK or a global FROZEN proof.
CREATE TABLE ticket_finance_quote_fence (
 freeze_id CHAR(36) NOT NULL PRIMARY KEY,
 tenant_id BIGINT NOT NULL, actor_user_id BIGINT NOT NULL, company_id BIGINT NOT NULL,
 fault_relation_id CHAR(36) NOT NULL, ticket_id CHAR(36) NOT NULL,
 round_id CHAR(36) NOT NULL, group_id CHAR(36) NOT NULL,
 expected_group_version BIGINT NOT NULL, quote_id CHAR(36) NOT NULL,
 operation_id CHAR(36) NOT NULL, command_digest CHAR(64) NOT NULL,
 generation BIGINT NOT NULL, required_owners VARCHAR(100) NOT NULL,
 recorded_at DATETIME(3) NOT NULL,
 UNIQUE(tenant_id,ticket_id,operation_id),
 UNIQUE(tenant_id,ticket_id,generation),
 CHECK(expected_group_version>=0 AND generation>0)
);
CREATE TABLE ticket_finance_quote_source_result (
 freeze_id CHAR(36) NOT NULL PRIMARY KEY,
 result_digest CHAR(64) NOT NULL,
 recorded_at DATETIME(3) NOT NULL,
 FOREIGN KEY(freeze_id) REFERENCES ticket_finance_quote_fence(freeze_id)
);
