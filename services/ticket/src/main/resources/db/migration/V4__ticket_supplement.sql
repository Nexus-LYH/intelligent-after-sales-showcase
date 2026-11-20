ALTER TABLE ticket_record DROP CHECK ck_ticket_workflow_state;
ALTER TABLE ticket_record ADD CONSTRAINT ck_ticket_workflow_state CHECK(status IN('PENDING_ACCEPTANCE','WAITING_ASSIGNMENT','REJECTED','ASSIGNED','IN_PROGRESS','WAITING_INFORMATION','PENDING_CUSTOMER_CONFIRMATION','RESOLVED','CLOSED')),
 ADD first_response_priority VARCHAR(2) NULL,ADD CHECK(first_response_priority IS NULL OR first_response_priority IN ('P1','P2','P3')),
 ADD current_supplement_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL;
CREATE TABLE ticket_supplement_request(
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,round_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 assignment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,resume_state VARCHAR(32) NOT NULL,
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 question VARCHAR(500) NOT NULL,requested_by BIGINT NOT NULL,requested_at DATETIME(3) NOT NULL,
 UNIQUE KEY uq_supplement_owner(id,ticket_id,round_id),FOREIGN KEY(ticket_id) REFERENCES ticket_record(id),
 FOREIGN KEY(assignment_id) REFERENCES ticket_assignment(id),CHECK(CHAR_LENGTH(question)>=5),
 CHECK((resume_state='PENDING_ACCEPTANCE' AND assignment_id IS NULL) OR (resume_state='IN_PROGRESS' AND assignment_id IS NOT NULL))
) ENGINE=InnoDB;
CREATE TABLE ticket_supplement_reply(
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,round_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 body TEXT NOT NULL,replied_by BIGINT NOT NULL,replied_at DATETIME(3) NOT NULL,
 UNIQUE KEY uq_reply_owner(id,request_id,ticket_id),
 FOREIGN KEY(request_id,ticket_id,round_id) REFERENCES ticket_supplement_request(id,ticket_id,round_id),CHECK(CHAR_LENGTH(body) BETWEEN 1 AND 2000)
) ENGINE=InnoDB;
ALTER TABLE ticket_record ADD CONSTRAINT fk_ticket_current_supplement FOREIGN KEY(current_supplement_id,id,round_id) REFERENCES ticket_supplement_request(id,ticket_id,round_id);
ALTER TABLE ticket_file MODIFY submission_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,MODIFY device_id BIGINT NULL,MODIFY ownership_version BIGINT NULL,
 ADD supplement_ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD supplement_round_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD supplement_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD reply_operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD CONSTRAINT fk_file_supplement FOREIGN KEY(supplement_request_id,supplement_ticket_id,supplement_round_id) REFERENCES ticket_supplement_request(id,ticket_id,round_id),
 ADD CONSTRAINT ck_file_context CHECK((supplement_request_id IS NULL AND supplement_ticket_id IS NULL AND supplement_round_id IS NULL AND reply_operation_id IS NULL AND device_id IS NOT NULL AND ownership_version IS NOT NULL) OR (supplement_request_id IS NOT NULL AND supplement_ticket_id IS NOT NULL AND supplement_round_id IS NOT NULL AND reply_operation_id IS NOT NULL AND device_id IS NULL AND ownership_version IS NULL));
CREATE TABLE ticket_supplement_file_reference(
 file_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 reply_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 FOREIGN KEY(file_id) REFERENCES ticket_file(id),FOREIGN KEY(reply_id,request_id,ticket_id) REFERENCES ticket_supplement_reply(id,request_id,ticket_id)
) ENGINE=InnoDB;
ALTER TABLE ticket_solution_decision ADD excluded_supplement_ms BIGINT NOT NULL DEFAULT 0,ADD CHECK(excluded_supplement_ms BETWEEN 0 AND 259200000);

UPDATE ticket_record SET first_response_priority=priority WHERE first_response_at IS NOT NULL;
