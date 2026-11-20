-- Immutable trusted source terminal receipt. No TTL/absence release and no historical approval mutation.
CREATE TABLE approval_finance_terminal (
 tenant_id BIGINT NOT NULL, freeze_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, generation BIGINT NOT NULL,
 source_state VARCHAR(16) NOT NULL, result_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 receipt_json JSON NOT NULL, recorded_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,freeze_id), UNIQUE(tenant_id,ticket_id,generation),
 CHECK(source_state='COMMITTED' OR source_state='ABORTED')
);
