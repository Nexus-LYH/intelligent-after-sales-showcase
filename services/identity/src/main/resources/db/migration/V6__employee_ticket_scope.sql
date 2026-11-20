ALTER TABLE identity_user_capability DROP CHECK ck_capability_code;
ALTER TABLE identity_user_capability ADD CONSTRAINT ck_capability_code CHECK(capability_code IN ('identity.session.revoke','identity.platform.session.revoke','asset.read','asset.manage','identity.customer.provision','ticket.read','ticket.accept','ticket.reject','ticket.assign','ticket.engineer.read','ticket.assignment.accept','ticket.record'));
CREATE TABLE identity_department(
 id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT NOT NULL,code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,name VARCHAR(100) NOT NULL,
 enabled TINYINT NOT NULL DEFAULT 1,row_version BIGINT NOT NULL DEFAULT 1,created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,
 UNIQUE(tenant_id,code),UNIQUE(tenant_id,id),FOREIGN KEY(tenant_id) REFERENCES tenant(id),CHECK(enabled IN(0,1) AND row_version>0)
) ENGINE=InnoDB;
CREATE TABLE identity_employee_configuration(
 user_id BIGINT PRIMARY KEY,tenant_id BIGINT NOT NULL,created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,
 FOREIGN KEY(user_id) REFERENCES identity_user(id),FOREIGN KEY(tenant_id) REFERENCES tenant(id)
) ENGINE=InnoDB;
CREATE TABLE identity_employee_company_scope(
 tenant_id BIGINT NOT NULL,user_id BIGINT NOT NULL,company_id BIGINT NOT NULL,enabled TINYINT NOT NULL DEFAULT 0,
 PRIMARY KEY(user_id,company_id),FOREIGN KEY(user_id) REFERENCES identity_employee_configuration(user_id),FOREIGN KEY(tenant_id) REFERENCES tenant(id),CHECK(company_id>0 AND enabled IN(0,1))
) ENGINE=InnoDB;
CREATE TABLE identity_employee_department_scope(
 tenant_id BIGINT NOT NULL,user_id BIGINT NOT NULL,department_id BIGINT NOT NULL,enabled TINYINT NOT NULL DEFAULT 0,
 PRIMARY KEY(user_id,department_id),FOREIGN KEY(user_id) REFERENCES identity_employee_configuration(user_id),FOREIGN KEY(tenant_id,department_id) REFERENCES identity_department(tenant_id,id),CHECK(enabled IN(0,1))
) ENGINE=InnoDB;
CREATE TABLE identity_configuration_operation(
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,result_json JSON NOT NULL,created_at DATETIME(3) NOT NULL
) ENGINE=InnoDB;

ALTER TABLE identity_user ADD UNIQUE KEY uk_user_tenant_id(tenant_id,id),ADD CONSTRAINT fk_user_current_department FOREIGN KEY(tenant_id,department_id) REFERENCES identity_department(tenant_id,id);
