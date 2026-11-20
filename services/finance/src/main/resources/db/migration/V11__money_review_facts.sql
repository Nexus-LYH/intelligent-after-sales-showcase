-- Independent, append-only review facts. Execution remains gated until the owner adapter is connected.
CREATE TABLE finance_money_review (
 tenant_id BIGINT NOT NULL, review_id CHAR(36) NOT NULL, fault_group_id CHAR(36) NOT NULL,
 customer_company_id BIGINT NOT NULL, kind VARCHAR(16) NOT NULL, applicant_id BIGINT NOT NULL,
 account_version BIGINT NOT NULL, group_version BIGINT NOT NULL, subject_id CHAR(36) NOT NULL,
 bill_id CHAR(36) NOT NULL, line_id CHAR(36), proposed_cents BIGINT NOT NULL,
 coverage_digest CHAR(64), evidence_file_id CHAR(36), evidence_digest CHAR(64),
 reason VARCHAR(500) NOT NULL, created_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY (tenant_id,review_id), CHECK (CASE WHEN kind='REDUCTION' THEN 1 WHEN kind='CORRECTION' THEN 1 ELSE 0 END=1),
 CHECK (proposed_cents>=0)
);
CREATE TABLE finance_money_review_event (
 tenant_id BIGINT NOT NULL, review_id CHAR(36) NOT NULL, operation_id CHAR(36) NOT NULL,
 event_kind VARCHAR(24) NOT NULL, actor_id BIGINT NOT NULL, request_digest CHAR(64) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY (tenant_id,operation_id), UNIQUE (tenant_id,review_id,event_kind),
 FOREIGN KEY (tenant_id,review_id) REFERENCES finance_money_review(tenant_id,review_id),
 FOREIGN KEY (tenant_id,operation_id) REFERENCES finance_operation(tenant_id,operation_id),
 CHECK (CASE WHEN event_kind='SUBMITTED' THEN 1 WHEN event_kind='CUSTOMER_CONFIRMED' THEN 1 WHEN event_kind='FINANCE_APPROVED' THEN 1 WHEN event_kind='CORRECTION_VERIFIED' THEN 1 ELSE 0 END=1)
);
