CREATE TABLE approval_fee_rule_publication (
 publication_sequence BIGINT AUTO_INCREMENT PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 rule_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 threshold_cents BIGINT NOT NULL,
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 operator_reference VARCHAR(100) NOT NULL,
 published_at DATETIME(3) NOT NULL,
 UNIQUE KEY uq_fee_rule_version(tenant_id,rule_version),
 KEY ix_fee_rule_current(tenant_id,publication_sequence),
 CHECK(tenant_id>0 AND threshold_cents>=0)
) ENGINE=InnoDB;
