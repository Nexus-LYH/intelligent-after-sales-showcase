ALTER TABLE asset_membership ADD UNIQUE KEY uk_membership_owner_id(tenant_id,company_id,id);
CREATE TABLE asset_membership_role (
 tenant_id BIGINT NOT NULL,company_id BIGINT NOT NULL,membership_id BIGINT NOT NULL,
 role_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 updated_at DATETIME(3) NOT NULL,
 PRIMARY KEY(membership_id),
 FOREIGN KEY(tenant_id,company_id,membership_id) REFERENCES asset_membership(tenant_id,company_id,id),
 CHECK(role_code IN('ADMIN','MEMBER'))
) ENGINE=InnoDB;
CREATE TABLE asset_membership_role_operation (
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL,company_id BIGINT NOT NULL,membership_id BIGINT NOT NULL,actor_user_id BIGINT NOT NULL,
 command_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 result_json JSON NOT NULL,recorded_at DATETIME(3) NOT NULL,
 KEY ix_membership_role_operation(tenant_id,company_id,actor_user_id,recorded_at)
) ENGINE=InnoDB;
CREATE TABLE asset_membership_role_audit (
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL,company_id BIGINT NOT NULL,membership_id BIGINT NOT NULL,actor_user_id BIGINT NOT NULL,
 previous_role VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 resulting_role VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 occurred_at DATETIME(3) NOT NULL,
 FOREIGN KEY(operation_id) REFERENCES asset_membership_role_operation(operation_id),
 CHECK(previous_role IN('ADMIN','MEMBER') AND resulting_role IN('ADMIN','MEMBER'))
) ENGINE=InnoDB;
-- No backfill to ADMIN. Missing role record always means MEMBER.
