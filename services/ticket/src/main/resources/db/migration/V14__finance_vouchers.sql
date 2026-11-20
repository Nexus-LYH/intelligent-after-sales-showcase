CREATE TABLE ticket_finance_voucher (
 upload_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 file_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 tenant_id BIGINT NOT NULL, actor_user_id BIGINT NOT NULL, company_id BIGINT NOT NULL,
 ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 group_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 business_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 purpose VARCHAR(32) NOT NULL, name VARCHAR(200) NOT NULL, media_type VARCHAR(64) NOT NULL,
 size_bytes BIGINT NOT NULL, sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 command_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 state VARCHAR(16) NOT NULL, created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
 CONSTRAINT ck_finance_voucher_state CHECK(state IN ('UPLOADING','READY')),
 CONSTRAINT ck_finance_voucher_purpose CHECK(purpose IN ('FINANCE_COLLECTION','FINANCE_REFUND')),
 CONSTRAINT ck_finance_voucher_size CHECK(size_bytes BETWEEN 1 AND 20971520),
 INDEX ix_finance_voucher_business(tenant_id,company_id,ticket_id,group_id,business_id)
) ENGINE=InnoDB;
CREATE TABLE ticket_finance_voucher_audit (
 upload_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 action VARCHAR(16) NOT NULL, tenant_id BIGINT NOT NULL, actor_user_id BIGINT NOT NULL,
 file_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 command_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 occurred_at DATETIME(3) NOT NULL,
 PRIMARY KEY(upload_id,action), CONSTRAINT ck_finance_voucher_audit CHECK(action IN ('REGISTERED','READY'))
) ENGINE=InnoDB;
