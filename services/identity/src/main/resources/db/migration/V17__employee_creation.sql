CREATE TABLE identity_employee_create_operation (
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL,actor_user_id BIGINT NOT NULL,target_user_id BIGINT NOT NULL,
 account_normalized VARCHAR(100) NOT NULL,display_name VARCHAR(50) NOT NULL,
 occurred_at DATETIME(3) NOT NULL,trace_id VARCHAR(100) NOT NULL,
 FOREIGN KEY(tenant_id) REFERENCES tenant(id),FOREIGN KEY(actor_user_id) REFERENCES identity_user(id),FOREIGN KEY(target_user_id) REFERENCES identity_user(id)
) ENGINE=InnoDB;
