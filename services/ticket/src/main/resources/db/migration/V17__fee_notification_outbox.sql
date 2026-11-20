CREATE TABLE ticket_fee_notification_outbox (
 event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 tenant_id BIGINT NOT NULL, event_type VARCHAR(64) NOT NULL,
 aggregate_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 aggregate_version BIGINT NOT NULL, fee_group_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 generation BIGINT NOT NULL, ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 round_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 assignment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 company_id BIGINT NOT NULL, department_id BIGINT NOT NULL, applicant_user_id BIGINT NOT NULL,
 envelope LONGTEXT NOT NULL, event_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 delivery_state VARCHAR(16) NOT NULL, attempt_count INT NOT NULL, next_attempt_at DATETIME(3) NOT NULL,
 lease_until DATETIME(3) NULL, last_error VARCHAR(64) NULL, created_at DATETIME(3) NOT NULL, delivered_at DATETIME(3) NULL,
 UNIQUE KEY uq_ticket_fee_event_fact(tenant_id,event_type,aggregate_id,aggregate_version),
 KEY ix_ticket_fee_delivery(delivery_state,next_attempt_at,lease_until),
 CHECK(tenant_id>0 AND aggregate_version>0 AND generation>0 AND company_id>0 AND department_id>0 AND applicant_user_id>0),
 CHECK(event_type IN('approval.fee_completed','ticket.fee_execution_recorded')),
 CHECK(delivery_state IN('PENDING','IN_FLIGHT','DELIVERED') AND attempt_count>=0)
) ENGINE=InnoDB;
