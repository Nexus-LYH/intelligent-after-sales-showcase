ALTER TABLE identity_user_capability DROP CHECK ck_capability_code;
ALTER TABLE identity_user_capability ADD CONSTRAINT ck_capability_code CHECK(capability_code IN ('identity.session.revoke','identity.platform.session.revoke','asset.read','asset.manage','identity.customer.provision'));
CREATE TABLE identity_customer_enrollment(
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 actor_user_id BIGINT NOT NULL,
 tenant_id BIGINT NOT NULL,
 account_normalized VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 display_name VARCHAR(50) NOT NULL,
 user_id BIGINT NOT NULL UNIQUE,
 state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(3) NOT NULL,
 updated_at DATETIME(3) NOT NULL,
 CONSTRAINT fk_enroll_actor FOREIGN KEY(actor_user_id) REFERENCES identity_user(id) ON DELETE RESTRICT,
 CONSTRAINT fk_enroll_user FOREIGN KEY(user_id) REFERENCES identity_user(id) ON DELETE RESTRICT,
 CONSTRAINT fk_enroll_tenant FOREIGN KEY(tenant_id) REFERENCES tenant(id) ON DELETE RESTRICT,
 CONSTRAINT ck_enroll_state CHECK(state IN ('PENDING','BOUND'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
