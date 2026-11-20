CREATE TABLE asset_repair_binding(
 binding_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 tenant_id BIGINT NOT NULL,actor_user_id BIGINT NOT NULL,device_id BIGINT NOT NULL,
 request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 state VARCHAR(16) NOT NULL, snapshot_json JSON NULL,accepted_at DATETIME(3) NULL,
 created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,
 KEY ix_binding_device(tenant_id,device_id,state),
 CHECK(state IN ('RESERVED','CONFIRMED','RELEASED'))
) ENGINE=InnoDB;
CREATE TABLE asset_repair_audit(id BIGINT AUTO_INCREMENT PRIMARY KEY,operation_id CHAR(36) NOT NULL,tenant_id BIGINT NOT NULL,actor_user_id BIGINT NOT NULL,action VARCHAR(64) NOT NULL,occurred_at DATETIME(3) NOT NULL) ENGINE=InnoDB;
