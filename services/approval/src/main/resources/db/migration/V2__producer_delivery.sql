-- Producer-local delivery tables. These belong to approval, not the notification database.
-- V1 approval_outbox remains immutable audit/history; new events use the same stable ID in both stores.
-- Existing legacy V1-only events require controlled original-ID backfill before enabling a production worker.
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
CREATE TABLE event_replay_audit (
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL, event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 consumer_id VARCHAR(32) NOT NULL, actor_id BIGINT NOT NULL, reason VARCHAR(500) NOT NULL,
 replayed_at TIMESTAMP(3) NOT NULL, FOREIGN KEY(event_id) REFERENCES event_outbox(event_id)
) ENGINE=InnoDB;
