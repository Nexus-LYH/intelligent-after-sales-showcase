CREATE TABLE notification_fee_recipient_repair (
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 envelope_sha CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 requested_recipients LONGTEXT NOT NULL,
 reason VARCHAR(500) NOT NULL,
 restored_messages INT NOT NULL,
 repaired_at DATETIME(3) NOT NULL,
 KEY ix_fee_recipient_repair_event(tenant_id,event_id),
 CHECK(restored_messages>=0)
) ENGINE=InnoDB;
