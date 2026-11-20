-- Install outbox/delivery/replay in each PRODUCER owner DB. Install inbox/projection in the CONSUMER DB.
-- This file is not executed automatically. Runtime identity has no DDL or delete privilege.
CREATE TABLE event_outbox (
 event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL, producer VARCHAR(32) NOT NULL, event_type VARCHAR(80) NOT NULL,
 envelope_json LONGTEXT NOT NULL, envelope_sha CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at TIMESTAMP(3) NOT NULL
) ENGINE=InnoDB;
CREATE TABLE event_delivery (
 event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 consumer_id VARCHAR(32) NOT NULL, state VARCHAR(16) NOT NULL,
 attempts INT NOT NULL DEFAULT 0, next_attempt_at TIMESTAMP(3) NOT NULL,
 lease_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL, lease_until TIMESTAMP(3) NULL,
 last_error_code VARCHAR(32) NULL, delivered_at TIMESTAMP(3) NULL,
 PRIMARY KEY(event_id,consumer_id), FOREIGN KEY(event_id) REFERENCES event_outbox(event_id),
 KEY ix_delivery_due(state,next_attempt_at,lease_until),
 CHECK(state='PENDING' OR state='CLAIMED' OR state='DELIVERED' OR state='DEAD'), CHECK(attempts>=0)
) ENGINE=InnoDB;
CREATE TABLE event_inbox (
 consumer_id VARCHAR(32) NOT NULL, event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 tenant_id BIGINT NOT NULL, envelope_sha CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 processed_at TIMESTAMP(3) NOT NULL, PRIMARY KEY(consumer_id,event_id)
) ENGINE=InnoDB;
CREATE TABLE event_replay_audit (
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL, event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 consumer_id VARCHAR(32) NOT NULL, actor_id BIGINT NOT NULL, reason VARCHAR(500) NOT NULL,
 replayed_at TIMESTAMP(3) NOT NULL, FOREIGN KEY(event_id) REFERENCES event_outbox(event_id)
) ENGINE=InnoDB;
-- Demonstration signal projection: never a business authorization or a notification recipient list.
CREATE TABLE notification_signal (
 event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL, producer VARCHAR(32) NOT NULL, event_type VARCHAR(80) NOT NULL,
 aggregate_id VARCHAR(100) NOT NULL, aggregate_version DECIMAL(20,0) NOT NULL,
 occurred_at TIMESTAMP(3) NOT NULL
) ENGINE=InnoDB;

