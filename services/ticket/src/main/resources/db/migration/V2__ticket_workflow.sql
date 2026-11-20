ALTER TABLE ticket_record DROP CHECK ticket_record_chk_1;
ALTER TABLE ticket_record ADD CONSTRAINT ck_ticket_workflow_state CHECK(status IN('PENDING_ACCEPTANCE','WAITING_ASSIGNMENT','REJECTED','ASSIGNED','IN_PROGRESS'));
ALTER TABLE ticket_record
 ADD round_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD initial_priority VARCHAR(2) NULL,ADD priority VARCHAR(2) NULL,ADD priority_rule_version VARCHAR(32) NOT NULL DEFAULT 'IMPACT_V1',
 ADD priority_source VARCHAR(32) NOT NULL DEFAULT 'SUBMISSION',ADD policy_version VARCHAR(32) NOT NULL DEFAULT 'SLA_V1',
 ADD response_due_at DATETIME(3) NULL,ADD resolution_due_at DATETIME(3) NULL,
 ADD first_response_at DATETIME(3) NULL,ADD first_response_actor BIGINT NULL,ADD first_response_record_id CHAR(36) NULL,
 ADD department_id BIGINT NULL,ADD engineer_user_id BIGINT NULL,ADD assignment_id CHAR(36) NULL,
 ADD KEY ix_ticket_company(tenant_id,company_id,status,submitted_at,id),ADD KEY ix_ticket_engineer(tenant_id,engineer_user_id,department_id,status,id);
UPDATE ticket_record SET round_id=id,initial_priority=CASE impact WHEN 'UNUSABLE' THEN 'P1' WHEN 'AFFECTED' THEN 'P2' ELSE 'P3' END,priority=CASE impact WHEN 'UNUSABLE' THEN 'P1' WHEN 'AFFECTED' THEN 'P2' ELSE 'P3' END,priority_source='MIGRATED_IMPACT_V1';
UPDATE ticket_record SET response_due_at=TIMESTAMPADD(MINUTE,CASE priority WHEN 'P1' THEN 30 WHEN 'P2' THEN 120 ELSE 480 END,submitted_at),resolution_due_at=TIMESTAMPADD(HOUR,CASE priority WHEN 'P1' THEN 4 WHEN 'P2' THEN 24 ELSE 72 END,submitted_at);
ALTER TABLE ticket_record MODIFY round_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,MODIFY initial_priority VARCHAR(2) NOT NULL,MODIFY priority VARCHAR(2) NOT NULL,MODIFY response_due_at DATETIME(3) NOT NULL,MODIFY resolution_due_at DATETIME(3) NOT NULL,ADD CHECK(priority IN('P1','P2','P3') AND initial_priority IN('P1','P2','P3'));
CREATE TABLE ticket_assignment(
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,round_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 tenant_id BIGINT NOT NULL,department_id BIGINT NOT NULL,engineer_user_id BIGINT NOT NULL,assigned_by BIGINT NOT NULL,note VARCHAR(500) NULL,created_at DATETIME(3) NOT NULL,
 FOREIGN KEY(ticket_id) REFERENCES ticket_record(id),KEY ix_assignment_ticket(ticket_id,id)
) ENGINE=InnoDB;
CREATE TABLE ticket_action_operation(
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,tenant_id BIGINT NOT NULL,actor_user_id BIGINT NOT NULL,ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 action VARCHAR(40) NOT NULL,request_digest CHAR(64) NOT NULL,result_json JSON NOT NULL,created_at DATETIME(3) NOT NULL,
 FOREIGN KEY(ticket_id) REFERENCES ticket_record(id),KEY ix_action_actor(tenant_id,actor_user_id,ticket_id)
) ENGINE=InnoDB;
CREATE TABLE ticket_processing_record(
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,round_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 assignment_id CHAR(36) NULL,operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,actor_user_id BIGINT NOT NULL,type VARCHAR(40) NOT NULL,
 body TEXT NOT NULL,work_hours DECIMAL(4,2) NOT NULL DEFAULT 0,visibility VARCHAR(24) NOT NULL,qualification_json JSON NOT NULL,row_version BIGINT NOT NULL,occurred_at DATETIME(3) NOT NULL,
 FOREIGN KEY(ticket_id) REFERENCES ticket_record(id),KEY ix_record_visibility(ticket_id,visibility,occurred_at,id),CHECK(visibility IN('INTERNAL','CUSTOMER_VISIBLE')),CHECK(work_hours>=0 AND work_hours<=24)
) ENGINE=InnoDB;
CREATE TABLE ticket_sla_target_history(
 id BIGINT AUTO_INCREMENT PRIMARY KEY,ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,operation_id CHAR(36) NULL,
 old_priority VARCHAR(2) NULL,new_priority VARCHAR(2) NOT NULL,reason VARCHAR(500) NULL,submitted_at DATETIME(3) NOT NULL,response_due_at DATETIME(3) NOT NULL,resolution_due_at DATETIME(3) NOT NULL,
 previous_response_overdue TINYINT NOT NULL DEFAULT 0,policy_version VARCHAR(32) NOT NULL,created_at DATETIME(3) NOT NULL,FOREIGN KEY(ticket_id) REFERENCES ticket_record(id)
) ENGINE=InnoDB;
INSERT INTO ticket_sla_target_history(ticket_id,new_priority,reason,submitted_at,response_due_at,resolution_due_at,policy_version,created_at) SELECT id,priority,'MIGRATED_IMPACT_V1',submitted_at,response_due_at,resolution_due_at,policy_version,UTC_TIMESTAMP(3) FROM ticket_record;
