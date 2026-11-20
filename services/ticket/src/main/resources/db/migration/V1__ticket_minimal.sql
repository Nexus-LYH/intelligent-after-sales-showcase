CREATE TABLE ticket_actor_lock(tenant_id BIGINT NOT NULL,user_id BIGINT NOT NULL,PRIMARY KEY(tenant_id,user_id)) ENGINE=InnoDB;
CREATE TABLE ticket_create_operation(
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 binding_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 tenant_id BIGINT NOT NULL,actor_user_id BIGINT NOT NULL,device_id BIGINT NOT NULL,
 request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,input_json JSON NOT NULL,
 state VARCHAR(16) NOT NULL,reason VARCHAR(64) NULL, binding_json JSON NULL,
 lease_id CHAR(36) NULL,lease_until DATETIME(3) NULL,row_version BIGINT NOT NULL DEFAULT 1,
 created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,
 KEY ix_operation_actor(tenant_id,actor_user_id,state),CHECK(state IN ('REGISTERED','RESERVED','COMMITTED','COMPLETED','ABORTED')),CHECK(row_version>0)
) ENGINE=InnoDB;
CREATE TABLE ticket_record(
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,ticket_no VARCHAR(40) NOT NULL UNIQUE,
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 tenant_id BIGINT NOT NULL,reporter_user_id BIGINT NOT NULL,company_id BIGINT NOT NULL,device_id BIGINT NOT NULL,
 title VARCHAR(100) NOT NULL,description TEXT NOT NULL,category_code VARCHAR(32) NOT NULL,impact VARCHAR(20) NOT NULL,
 contact_name VARCHAR(50) NOT NULL,contact_phone VARCHAR(30) NOT NULL,snapshot_json JSON NOT NULL,
 status VARCHAR(32) NOT NULL DEFAULT 'PENDING_ACCEPTANCE',row_version BIGINT NOT NULL DEFAULT 1,
 submitted_at DATETIME(3) NOT NULL,created_at DATETIME(3) NOT NULL,
 FOREIGN KEY(operation_id) REFERENCES ticket_create_operation(operation_id),KEY ix_reporter(tenant_id,reporter_user_id,submitted_at,id),
 CHECK(status='PENDING_ACCEPTANCE'),CHECK(impact IN ('UNUSABLE','AFFECTED','CONSULTATION')),CHECK(row_version>0)
) ENGINE=InnoDB;
CREATE TABLE ticket_audit(id BIGINT AUTO_INCREMENT PRIMARY KEY,operation_id CHAR(36) NULL,tenant_id BIGINT NOT NULL,actor_user_id BIGINT NULL,action VARCHAR(64) NOT NULL,occurred_at DATETIME(3) NOT NULL) ENGINE=InnoDB;
CREATE TABLE ticket_file(
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,upload_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 submission_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,tenant_id BIGINT NOT NULL,user_id BIGINT NOT NULL,
 device_id BIGINT NOT NULL,ownership_version BIGINT NOT NULL,name VARCHAR(200) NOT NULL,sha256 CHAR(64) NOT NULL,
 size_bytes BIGINT NOT NULL DEFAULT 0,media_type VARCHAR(40) NULL,state VARCHAR(16) NOT NULL,
 generation CHAR(36) NULL,lease_until DATETIME(3) NULL,created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,
 KEY ix_file_submission(tenant_id,user_id,submission_id),CHECK(state IN ('UPLOADING','FAILED','READY','DELETING','DELETED')),CHECK(size_bytes>=0 AND size_bytes<=20971520)
) ENGINE=InnoDB;
CREATE TABLE ticket_file_reference(file_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,FOREIGN KEY(file_id) REFERENCES ticket_file(id),FOREIGN KEY(ticket_id) REFERENCES ticket_record(id)) ENGINE=InnoDB;
CREATE TABLE ticket_download_authorization(id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,file_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,tenant_id BIGINT NOT NULL,user_id BIGINT NOT NULL,attempt VARCHAR(64) NOT NULL,expires_at DATETIME(3) NOT NULL,FOREIGN KEY(file_id) REFERENCES ticket_file(id)) ENGINE=InnoDB;
