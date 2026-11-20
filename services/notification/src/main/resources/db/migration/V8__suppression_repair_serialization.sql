CREATE TABLE notification_suppression_repair_lock (
 tenant_id BIGINT NOT NULL,
 event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,event_id)
) ENGINE=InnoDB;
