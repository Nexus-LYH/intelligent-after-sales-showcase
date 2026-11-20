-- Notification-owner migration. Local capture is a test/dev mailbox, never an SMTP sender.
ALTER TABLE notification_message ADD COLUMN object_type VARCHAR(32) NULL;
ALTER TABLE notification_message ADD COLUMN object_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL;
ALTER TABLE notification_message ADD COLUMN scope_generation BIGINT NULL;

CREATE TABLE fee_notification_cursor (
 tenant_id BIGINT NOT NULL, subject_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 generation BIGINT NOT NULL, aggregate_version DECIMAL(20,0) NOT NULL,
 event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, updated_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,subject_id), CHECK(generation>=0), CHECK(aggregate_version>=0)
) ENGINE=InnoDB;

CREATE TABLE fee_notification_suppression (
 event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL, subject_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 reason VARCHAR(32) NOT NULL, generation BIGINT NOT NULL, aggregate_version DECIMAL(20,0) NOT NULL,
 recorded_at DATETIME(3) NOT NULL,
 CHECK(reason='STALE_GENERATION' OR reason='OUT_OF_ORDER_VERSION')
) ENGINE=InnoDB;

CREATE TABLE notification_email_capture (
 message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL, recipient_user_id BIGINT NOT NULL,
 event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 recipient_address VARCHAR(254) NOT NULL, subject VARCHAR(100) NOT NULL, body VARCHAR(500) NOT NULL,
 captured_at DATETIME(3) NOT NULL,
 UNIQUE(tenant_id,recipient_user_id,event_id),
 FOREIGN KEY(message_id,tenant_id,recipient_user_id) REFERENCES notification_message(id,tenant_id,recipient_user_id)
) ENGINE=InnoDB;
