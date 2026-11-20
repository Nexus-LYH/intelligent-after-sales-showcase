ALTER TABLE ticket_record ADD round_started_at DATETIME(3) NULL,ADD current_reopen_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL;
UPDATE ticket_record SET round_started_at=submitted_at;
ALTER TABLE ticket_record MODIFY round_started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);
CREATE TABLE ticket_round(
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 round_no INT NOT NULL,origin VARCHAR(32) NOT NULL,started_at DATETIME(3) NOT NULL,approved_by BIGINT NULL,
 source_round_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,source_closure_operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 source_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL UNIQUE,approval_operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL UNIQUE,
 priority VARCHAR(2) NOT NULL,rule_version VARCHAR(32) NOT NULL,rule_json JSON NOT NULL,response_ms BIGINT NOT NULL,resolution_ms BIGINT NOT NULL,
 confirmation_limit_ms BIGINT NOT NULL DEFAULT 259200000,supplement_limit_ms BIGINT NOT NULL DEFAULT 259200000,
 frozen_json JSON NULL,ended_at DATETIME(3) NULL,
 UNIQUE(ticket_id,round_no),UNIQUE(id,ticket_id),FOREIGN KEY(ticket_id) REFERENCES ticket_record(id),
 CHECK(round_no>=1 AND priority IN('P1','P2','P3')),CHECK(response_ms>0 AND resolution_ms>0 AND confirmation_limit_ms=259200000 AND supplement_limit_ms=259200000),
 CHECK((frozen_json IS NULL)=(ended_at IS NULL))
) ENGINE=InnoDB;
INSERT INTO ticket_round(id,ticket_id,round_no,origin,started_at,priority,rule_version,rule_json,response_ms,resolution_ms)
 SELECT round_id,id,1,'LEGACY_BACKFILL',submitted_at,priority,policy_version,
 JSON_OBJECT('version',policy_version,'responseMinutes',JSON_OBJECT('P1',30,'P2',120,'P3',480),'resolutionHours',JSON_OBJECT('P1',4,'P2',24,'P3',72)),
 GREATEST(1,TIMESTAMPDIFF(MICROSECOND,submitted_at,response_due_at) DIV 1000),GREATEST(1,TIMESTAMPDIFF(MICROSECOND,submitted_at,resolution_due_at) DIV 1000) FROM ticket_record;
CREATE TABLE ticket_reopen_request(
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_round_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,source_closure_operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,requested_by BIGINT NOT NULL,reason VARCHAR(500) NOT NULL,
 requested_at DATETIME(3) NOT NULL,reopen_deadline DATETIME(3) NOT NULL,
 UNIQUE(id,ticket_id),FOREIGN KEY(source_round_id,ticket_id) REFERENCES ticket_round(id,ticket_id),
 KEY ix_reopen_source(ticket_id,source_round_id,requested_at,id),CHECK(CHAR_LENGTH(reason) BETWEEN 5 AND 500),CHECK(requested_at<=reopen_deadline)
) ENGINE=InnoDB;
CREATE TABLE ticket_reopen_decision(
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,decision VARCHAR(16) NOT NULL,reason VARCHAR(500) NOT NULL,
 decided_by BIGINT NOT NULL,decided_at DATETIME(3) NOT NULL,new_round_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL UNIQUE,priority VARCHAR(2) NULL,
 FOREIGN KEY(request_id,ticket_id) REFERENCES ticket_reopen_request(id,ticket_id),FOREIGN KEY(new_round_id,ticket_id) REFERENCES ticket_round(id,ticket_id),
 CHECK(decision IN('APPROVE','REJECT')),CHECK((decision='APPROVE')=(new_round_id IS NOT NULL)),CHECK((decision='APPROVE')=(priority IS NOT NULL)),CHECK(priority IS NULL OR priority IN('P1','P2','P3'))
) ENGINE=InnoDB;
ALTER TABLE ticket_record ADD CONSTRAINT fk_current_reopen FOREIGN KEY(current_reopen_id,id) REFERENCES ticket_reopen_request(id,ticket_id);

ALTER TABLE ticket_sla_target_history ADD round_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL;
UPDATE ticket_sla_target_history h JOIN ticket_record r ON r.id=h.ticket_id SET h.round_id=r.round_id;
