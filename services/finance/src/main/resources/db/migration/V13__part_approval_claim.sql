-- A physical material approval belongs to one fault group for its lifetime.
-- Quote revisions may reuse the exact binding in that group; no deletion releases ownership.
CREATE TABLE finance_part_approval_claim (
 tenant_id BIGINT NOT NULL, approval_id CHAR(36) NOT NULL,
 fault_group_id CHAR(36) NOT NULL, customer_company_id BIGINT NOT NULL,
 ticket_id CHAR(36) NOT NULL, round_id CHAR(36) NOT NULL,
 binding_digest CHAR(64) NOT NULL, first_operation_id CHAR(36) NOT NULL,
 recorded_at DATETIME(3) NOT NULL,
 PRIMARY KEY (tenant_id,approval_id),
 INDEX ix_part_claim_group (tenant_id,fault_group_id)
);
