-- Explicit user capabilities only. No bootstrap grants or default administrators.
CREATE TABLE identity_user_capability (
 user_id BIGINT NOT NULL,
 capability_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 enabled TINYINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL,
 updated_at DATETIME(3) NOT NULL,
 row_version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY (user_id,capability_code),
 CONSTRAINT fk_capability_user FOREIGN KEY (user_id) REFERENCES identity_user(id) ON DELETE RESTRICT,
 CONSTRAINT ck_capability_code CHECK (capability_code IN ('identity.session.revoke','identity.platform.session.revoke')),
 CONSTRAINT ck_capability_state CHECK (enabled IN (0,1) AND row_version>=0 AND updated_at>=created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
