ALTER TABLE ticket_record DROP CHECK ck_ticket_workflow_state;
ALTER TABLE ticket_record ADD CONSTRAINT ck_ticket_workflow_state CHECK(status IN('PENDING_ACCEPTANCE','WAITING_ASSIGNMENT','REJECTED','ASSIGNED','IN_PROGRESS','PENDING_CUSTOMER_CONFIRMATION','RESOLVED','CLOSED')),
 ADD current_solution_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD completion_scope VARCHAR(40) NOT NULL DEFAULT 'ORDINARY_REPAIR_V1',
 ADD related_business_json JSON NULL;
CREATE TABLE ticket_solution(
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,round_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 assignment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,attempt_no INT NOT NULL,
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,engineer_user_id BIGINT NOT NULL,
 cause TEXT NOT NULL,resolution_method TEXT NOT NULL,customer_message TEXT NOT NULL,completed_at DATETIME(3) NOT NULL,submitted_at DATETIME(3) NOT NULL,
 confirmed_work_hours DECIMAL(12,2) NOT NULL,evidence_file_ids JSON NOT NULL,
 UNIQUE KEY uq_solution_attempt(ticket_id,round_id,attempt_no),UNIQUE KEY uq_solution_owner(id,ticket_id,round_id),
 FOREIGN KEY(ticket_id) REFERENCES ticket_record(id),FOREIGN KEY(assignment_id) REFERENCES ticket_assignment(id),
 CHECK(attempt_no>0),CHECK(confirmed_work_hours>=0),CHECK(completed_at<=submitted_at)
) ENGINE=InnoDB;
CREATE TABLE ticket_solution_decision(
 solution_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,round_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,actor_user_id BIGINT NOT NULL,
 decision VARCHAR(10) NOT NULL,reason VARCHAR(500) NULL,decided_at DATETIME(3) NOT NULL,
 effective_resolution_ms BIGINT NOT NULL,excluded_confirmation_ms BIGINT NOT NULL,
 FOREIGN KEY(solution_id,ticket_id,round_id) REFERENCES ticket_solution(id,ticket_id,round_id),
 CHECK(decision IN('CONFIRM','RETURN')),CHECK((decision='CONFIRM' AND reason IS NULL) OR (decision='RETURN' AND CHAR_LENGTH(reason)>=5)),
 CHECK(effective_resolution_ms>=0),CHECK(excluded_confirmation_ms>=0 AND excluded_confirmation_ms<=259200000)
) ENGINE=InnoDB;
CREATE TABLE ticket_closure(
 ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,round_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 solution_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 closed_by BIGINT NOT NULL,closed_at DATETIME(3) NOT NULL,close_message VARCHAR(500) NOT NULL,check_json JSON NOT NULL,
 PRIMARY KEY(ticket_id,round_id),FOREIGN KEY(solution_id,ticket_id,round_id) REFERENCES ticket_solution(id,ticket_id,round_id)
) ENGINE=InnoDB;
ALTER TABLE ticket_record ADD CONSTRAINT fk_ticket_current_solution FOREIGN KEY(current_solution_id,id,round_id) REFERENCES ticket_solution(id,ticket_id,round_id);
