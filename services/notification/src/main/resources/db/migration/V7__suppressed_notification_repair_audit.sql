CREATE TABLE notification_suppression_repair (
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 producer VARCHAR(32) NOT NULL,
 envelope_sha CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 reason VARCHAR(500) NOT NULL,
 restored_messages INT NOT NULL,
 repaired_at DATETIME(3) NOT NULL,
 UNIQUE(tenant_id,event_id), CHECK(restored_messages>0)
) ENGINE=InnoDB;
