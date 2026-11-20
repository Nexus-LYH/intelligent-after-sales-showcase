CREATE TABLE stock_part_fee_notification_outbox (
 event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 tenant_id BIGINT NOT NULL,event_type VARCHAR(64) NOT NULL,
 aggregate_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,aggregate_version BIGINT NOT NULL,
 company_id BIGINT NOT NULL,fee_group_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,generation BIGINT NOT NULL,
 execution_receipt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,line_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,round_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 warehouse_id BIGINT NOT NULL,part_id BIGINT NOT NULL,quantity BIGINT NOT NULL,amount_cents BIGINT NOT NULL,
 envelope LONGTEXT NOT NULL,event_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 delivery_state VARCHAR(16) NOT NULL,attempt_count INT NOT NULL,next_attempt_at DATETIME(3) NOT NULL,
 lease_id CHAR(36) NULL,lease_until DATETIME(3) NULL,last_error VARCHAR(64) NULL,created_at DATETIME(3) NOT NULL,delivered_at DATETIME(3) NULL,
 UNIQUE(tenant_id,execution_receipt_id),CHECK(event_type='inventory.consumed'),CHECK(aggregate_version>0 AND generation>0 AND warehouse_id>0 AND part_id>0 AND quantity>0 AND amount_cents>0)
) ENGINE=InnoDB;
CREATE INDEX ix_stock_part_fee_delivery ON stock_part_fee_notification_outbox(delivery_state,next_attempt_at,lease_until);
