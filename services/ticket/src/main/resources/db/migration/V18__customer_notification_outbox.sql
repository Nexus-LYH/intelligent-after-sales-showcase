CREATE TABLE ticket_customer_notification_outbox (
 event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 tenant_id BIGINT NOT NULL, event_type VARCHAR(64) NOT NULL,
 aggregate_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 aggregate_version BIGINT NOT NULL,
 ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 round_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 company_id BIGINT NOT NULL, recipient_user_id BIGINT NOT NULL,
 state_code VARCHAR(64) NOT NULL,
 envelope LONGTEXT NOT NULL, event_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 delivery_state VARCHAR(16) NOT NULL, attempt_count INT NOT NULL, next_attempt_at DATETIME(3) NOT NULL,
 lease_until DATETIME(3) NULL, last_error VARCHAR(64) NULL, created_at DATETIME(3) NOT NULL, delivered_at DATETIME(3) NULL,
 UNIQUE KEY uq_ticket_customer_event_fact(tenant_id,ticket_id,event_type,aggregate_version),
 KEY ix_ticket_customer_delivery(delivery_state,next_attempt_at,lease_until),
 CHECK(tenant_id>0 AND aggregate_version>0 AND company_id>0 AND recipient_user_id>0),
 CHECK(event_type IN('ticket.accepted')),
 CHECK(delivery_state IN('PENDING','IN_FLIGHT','DELIVERED') AND attempt_count>=0)
) ENGINE=InnoDB;
