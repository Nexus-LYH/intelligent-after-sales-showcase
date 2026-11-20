CREATE TABLE asset_company(
 id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT NOT NULL,code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,name VARCHAR(100) NOT NULL,
 contact_name VARCHAR(50),phone VARCHAR(30),address VARCHAR(200),status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',disabled_reason VARCHAR(200),row_version BIGINT NOT NULL DEFAULT 1,
 created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_company_code(tenant_id,code),UNIQUE KEY uk_company_tenant_id(tenant_id,id),
 CHECK(tenant_id>0 AND row_version>=1 AND status IN ('ENABLED','DISABLED') AND CHAR_LENGTH(name) BETWEEN 1 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE asset_model(
 id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT NOT NULL,code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,name VARCHAR(100) NOT NULL,
 category VARCHAR(50),description VARCHAR(1000),status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',row_version BIGINT NOT NULL DEFAULT 1,created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_model_code(tenant_id,code),UNIQUE KEY uk_model_tenant_id(tenant_id,id),CHECK(tenant_id>0 AND row_version>=1 AND status IN ('ENABLED','DISABLED') AND CHAR_LENGTH(name) BETWEEN 1 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE asset_device(
 id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT NOT NULL,code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,serial_number VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,name VARCHAR(100) NOT NULL,
 company_id BIGINT NOT NULL,model_id BIGINT NOT NULL,location VARCHAR(200),purchase_date DATE,warranty_start DATE,warranty_end DATE,status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',disabled_reason VARCHAR(200),
 row_version BIGINT NOT NULL DEFAULT 1,ownership_version BIGINT NOT NULL DEFAULT 1,created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_device_code(tenant_id,code),UNIQUE KEY uk_device_serial(tenant_id,serial_number),KEY ix_device_company(tenant_id,company_id,id),
 FOREIGN KEY(tenant_id,company_id) REFERENCES asset_company(tenant_id,id) ON DELETE RESTRICT,
 FOREIGN KEY(tenant_id,model_id) REFERENCES asset_model(tenant_id,id) ON DELETE RESTRICT,
 CHECK(tenant_id>0 AND row_version>=1 AND ownership_version>=1 AND status IN ('ENABLED','DISABLED') AND CHAR_LENGTH(name) BETWEEN 1 AND 100 AND CHAR_LENGTH(serial_number) BETWEEN 1 AND 100),
 CHECK((warranty_start IS NULL AND warranty_end IS NULL) OR (warranty_start IS NOT NULL AND warranty_end IS NOT NULL AND warranty_start<=warranty_end)),
 CHECK(purchase_date IS NULL OR warranty_end IS NULL OR purchase_date<=warranty_end)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE asset_operation(
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,tenant_id BIGINT NOT NULL,actor_user_id BIGINT NOT NULL,kind VARCHAR(48) NOT NULL,target_id BIGINT,
 request_digest BINARY(32) NOT NULL,input_json JSON NOT NULL,result_json JSON,state VARCHAR(40) NOT NULL,user_id BIGINT,created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,
 KEY ix_operation_actor(tenant_id,actor_user_id,created_at),CHECK(tenant_id>0 AND actor_user_id>0 AND state IN ('COMPLETED','REGISTERED','PENDING','MEMBERSHIP_COMMITTED','BLOCKED','AWAITING_INITIAL_PASSWORD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE asset_membership(
 id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT NOT NULL,company_id BIGINT NOT NULL,user_id BIGINT NOT NULL,operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 state VARCHAR(24) NOT NULL,row_version BIGINT NOT NULL DEFAULT 1,created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_membership(tenant_id,company_id,user_id),UNIQUE KEY uk_membership_operation(operation_id),KEY ix_member_user(tenant_id,user_id,state),
 FOREIGN KEY(tenant_id,company_id) REFERENCES asset_company(tenant_id,id) ON DELETE RESTRICT,FOREIGN KEY(operation_id) REFERENCES asset_operation(operation_id) ON DELETE RESTRICT,
 CHECK(tenant_id>0 AND user_id>0 AND row_version>=1 AND state IN ('PROVISIONING','ACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE asset_audit(
 id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT NOT NULL,actor_user_id BIGINT NOT NULL,action VARCHAR(48) NOT NULL,target_id BIGINT,operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 occurred_at DATETIME(3) NOT NULL,trace_id VARCHAR(64) NOT NULL,FOREIGN KEY(operation_id) REFERENCES asset_operation(operation_id) ON DELETE RESTRICT,
 KEY ix_asset_audit(tenant_id,occurred_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
