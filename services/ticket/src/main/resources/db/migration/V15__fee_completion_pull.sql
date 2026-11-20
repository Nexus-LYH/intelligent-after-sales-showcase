CREATE TABLE ticket_fee_group_pull (
 tenant_id BIGINT NOT NULL,company_id BIGINT NOT NULL,group_id CHAR(36) NOT NULL,
 attempts BIGINT NOT NULL DEFAULT 0,next_attempt_at DATETIME(3) NOT NULL,
 lease_id CHAR(36) NULL,lease_until DATETIME(3) NULL,last_error_code VARCHAR(80) NULL,
 PRIMARY KEY(tenant_id,group_id),CHECK(tenant_id>0 AND company_id>0 AND attempts>=0)
);
CREATE TABLE ticket_fee_completion_ack (
 tenant_id BIGINT NOT NULL,company_id BIGINT NOT NULL,group_id CHAR(36) NOT NULL,
 completion_id CHAR(36) NOT NULL,quote_id CHAR(36) NOT NULL,quote_version BIGINT NOT NULL,
 ack_digest CHAR(64) NOT NULL,payload_json LONGTEXT NOT NULL,recorded_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,group_id,completion_id),CHECK(quote_version>0)
);
