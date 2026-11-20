CREATE TABLE identity_employee_scope_operation (
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL,actor_user_id BIGINT NOT NULL,target_user_id BIGINT NOT NULL,
 request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 before_json JSON NOT NULL,after_json JSON NOT NULL,
 expected_security_version BIGINT NOT NULL,new_security_version BIGINT NOT NULL,
 reason VARCHAR(200) NOT NULL,occurred_at DATETIME(3) NOT NULL,trace_id VARCHAR(100) NOT NULL,
 FOREIGN KEY(tenant_id) REFERENCES tenant(id),FOREIGN KEY(actor_user_id) REFERENCES identity_user(id),FOREIGN KEY(target_user_id) REFERENCES identity_user(id)
) ENGINE=InnoDB;
