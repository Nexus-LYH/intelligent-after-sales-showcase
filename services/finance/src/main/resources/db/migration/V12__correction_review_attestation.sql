-- Reviewer-entered facts are separate from the applicant's proposal. Historical events stay unchanged.
CREATE TABLE finance_correction_attestation (
 tenant_id BIGINT NOT NULL, review_id CHAR(36) NOT NULL, operation_id CHAR(36) NOT NULL,
 reviewer_id BIGINT NOT NULL, verified_cents BIGINT NOT NULL,
 evidence_file_id CHAR(36) NOT NULL, evidence_digest CHAR(64) NOT NULL,
 reviewer_note VARCHAR(500) NOT NULL, created_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY (tenant_id,review_id), UNIQUE (tenant_id,operation_id),
 FOREIGN KEY (tenant_id,review_id) REFERENCES finance_money_review(tenant_id,review_id),
 FOREIGN KEY (tenant_id,operation_id) REFERENCES finance_money_review_event(tenant_id,operation_id),
 CHECK (verified_cents>=0 AND reviewer_id>0)
);
