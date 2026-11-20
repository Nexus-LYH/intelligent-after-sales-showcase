-- Notification owner only. Apply V1 in notification owner DB first for event_inbox.
CREATE TABLE notification_reader_lock (
 tenant_id BIGINT NOT NULL, actor_user_id BIGINT NOT NULL, created_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,actor_user_id)
) ENGINE=InnoDB;
CREATE TABLE notification_message (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL, recipient_user_id BIGINT NOT NULL,
 event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 recipient_kind VARCHAR(16) NOT NULL, event_type VARCHAR(80) NOT NULL,
 owner_event_json LONGTEXT NOT NULL, approval_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 title VARCHAR(100) NOT NULL, body VARCHAR(500) NOT NULL,
 occurred_at DATETIME(3) NOT NULL, created_at DATETIME(3) NOT NULL,
 UNIQUE(tenant_id,recipient_user_id,event_id), UNIQUE(id,tenant_id,recipient_user_id),
 KEY ix_my_messages(tenant_id,recipient_user_id,created_at,id),
 CHECK(recipient_kind IN('APPLICANT','REVIEWER'))
) ENGINE=InnoDB;
CREATE TABLE notification_message_read (
 tenant_id BIGINT NOT NULL, recipient_user_id BIGINT NOT NULL,
 message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 first_operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 read_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,recipient_user_id,message_id),
 FOREIGN KEY(message_id,tenant_id,recipient_user_id) REFERENCES notification_message(id,tenant_id,recipient_user_id)
) ENGINE=InnoDB;
CREATE TABLE notification_read_operation (
 tenant_id BIGINT NOT NULL, actor_user_id BIGINT NOT NULL,
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, read_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,actor_user_id,operation_id),
 FOREIGN KEY(message_id,tenant_id,actor_user_id) REFERENCES notification_message(id,tenant_id,recipient_user_id)
) ENGINE=InnoDB;
CREATE TABLE notification_read_audit (
 tenant_id BIGINT NOT NULL, actor_user_id BIGINT NOT NULL,
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, occurred_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,actor_user_id,operation_id),
 FOREIGN KEY(tenant_id,actor_user_id,operation_id) REFERENCES notification_read_operation(tenant_id,actor_user_id,operation_id)
) ENGINE=InnoDB;
