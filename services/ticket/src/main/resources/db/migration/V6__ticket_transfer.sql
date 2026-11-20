CREATE TABLE ticket_transfer_request(
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 tenant_id BIGINT NOT NULL,round_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,source_assignment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_department_id BIGINT NOT NULL,source_engineer_user_id BIGINT NOT NULL,target_department_id BIGINT NOT NULL,target_engineer_user_id BIGINT NOT NULL,
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,requested_by BIGINT NOT NULL,reason VARCHAR(500) NOT NULL,
 requested_at DATETIME(3) NOT NULL,expires_at DATETIME(3) NOT NULL,
 UNIQUE KEY uq_transfer_owner(id,ticket_id,round_id),KEY ix_transfer_inbox(tenant_id,target_engineer_user_id,expires_at,id),KEY ix_transfer_expiry(expires_at,id),
 FOREIGN KEY(ticket_id) REFERENCES ticket_record(id),FOREIGN KEY(source_assignment_id) REFERENCES ticket_assignment(id),
 CHECK(source_engineer_user_id<>target_engineer_user_id),CHECK(CHAR_LENGTH(reason) BETWEEN 5 AND 500),CHECK(expires_at=TIMESTAMPADD(HOUR,24,requested_at))
) ENGINE=InnoDB;
CREATE TABLE ticket_transfer_end(
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 round_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 state VARCHAR(32) NOT NULL,reason VARCHAR(500) NULL,decided_by BIGINT NULL,decided_at DATETIME(3) NOT NULL,
 assignment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL UNIQUE,
 FOREIGN KEY(request_id,ticket_id,round_id) REFERENCES ticket_transfer_request(id,ticket_id,round_id),FOREIGN KEY(assignment_id) REFERENCES ticket_assignment(id),
 CHECK(state IN('ACCEPTED','REJECTED','WITHDRAWN','EXPIRED','NO_LONGER_APPLICABLE')),
 CHECK((state='ACCEPTED' AND assignment_id IS NOT NULL) OR(state<>'ACCEPTED' AND assignment_id IS NULL))
) ENGINE=InnoDB;
CREATE TABLE ticket_coordination_event(
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 round_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,assignment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 reason VARCHAR(40) NOT NULL,request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,created_at DATETIME(3) NOT NULL,
 FOREIGN KEY(ticket_id) REFERENCES ticket_record(id),FOREIGN KEY(assignment_id) REFERENCES ticket_assignment(id),FOREIGN KEY(request_id) REFERENCES ticket_transfer_request(id),
 KEY ix_coordination_assignment(ticket_id,assignment_id,id),CHECK(reason IN('TARGET_REJECTED','REQUEST_WITHDRAWN','TRANSFER_EXPIRED','SOURCE_INELIGIBLE'))
) ENGINE=InnoDB;
ALTER TABLE ticket_record ADD current_transfer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD CONSTRAINT fk_ticket_transfer FOREIGN KEY(current_transfer_id,id,round_id) REFERENCES ticket_transfer_request(id,ticket_id,round_id);
