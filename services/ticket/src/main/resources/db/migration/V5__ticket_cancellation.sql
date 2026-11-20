ALTER TABLE ticket_record DROP CHECK ck_ticket_workflow_state;
ALTER TABLE ticket_record ADD CONSTRAINT ck_ticket_workflow_state CHECK(status IN('PENDING_ACCEPTANCE','WAITING_ASSIGNMENT','REJECTED','ASSIGNED','IN_PROGRESS','WAITING_INFORMATION','PENDING_CUSTOMER_CONFIRMATION','RESOLVED','CLOSED','CANCELLED')),
 ADD current_cancel_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL;
CREATE TABLE ticket_cancel_request(
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 round_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,assignment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,requested_by BIGINT NOT NULL,requested_at DATETIME(3) NOT NULL,
 reason VARCHAR(500) NOT NULL,submitted_status VARCHAR(32) NOT NULL,
 UNIQUE KEY uq_cancel_request_owner(id,ticket_id,round_id),FOREIGN KEY(ticket_id) REFERENCES ticket_record(id),FOREIGN KEY(assignment_id) REFERENCES ticket_assignment(id),
 CHECK(CHAR_LENGTH(reason) BETWEEN 5 AND 500)
) ENGINE=InnoDB;
CREATE TABLE ticket_cancel_decision(
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 round_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 decision VARCHAR(32) NOT NULL,reason VARCHAR(500) NOT NULL,decided_by BIGINT NOT NULL,decided_at DATETIME(3) NOT NULL,
 FOREIGN KEY(request_id,ticket_id,round_id) REFERENCES ticket_cancel_request(id,ticket_id,round_id),
 CHECK(decision IN('APPROVED','REJECTED','NO_LONGER_APPLICABLE')),CHECK(CHAR_LENGTH(reason)>=5)
) ENGINE=InnoDB;
CREATE TABLE ticket_cancellation(
 ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,round_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 source VARCHAR(16) NOT NULL,cancelled_by BIGINT NOT NULL,cancelled_at DATETIME(3) NOT NULL,reason VARCHAR(500) NULL,
 previous_status VARCHAR(32) NOT NULL,assignment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 terminated_supplement_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,terminated_solution_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 check_json JSON NOT NULL,timing_json JSON NOT NULL,PRIMARY KEY(ticket_id,round_id),
 FOREIGN KEY(ticket_id) REFERENCES ticket_record(id),FOREIGN KEY(request_id,ticket_id,round_id) REFERENCES ticket_cancel_request(id,ticket_id,round_id),
 FOREIGN KEY(terminated_supplement_id,ticket_id,round_id) REFERENCES ticket_supplement_request(id,ticket_id,round_id),
 FOREIGN KEY(terminated_solution_id,ticket_id,round_id) REFERENCES ticket_solution(id,ticket_id,round_id),
 FOREIGN KEY(assignment_id) REFERENCES ticket_assignment(id),CHECK((source='DIRECT' AND request_id IS NULL) OR(source='APPROVED' AND request_id IS NOT NULL))
) ENGINE=InnoDB;
ALTER TABLE ticket_record ADD CONSTRAINT fk_ticket_cancel_request FOREIGN KEY(current_cancel_request_id,id,round_id) REFERENCES ticket_cancel_request(id,ticket_id,round_id);
