CREATE TABLE ticket_finance_coordination (
 freeze_id CHAR(36) NOT NULL PRIMARY KEY,
 state VARCHAR(20) NOT NULL,source_state VARCHAR(16) NULL,result_digest CHAR(64) NULL,
 attempts INT NOT NULL DEFAULT 0,next_attempt_at DATETIME(3) NOT NULL,
 lease_id CHAR(36) NULL,lease_until DATETIME(3) NULL,last_error_code VARCHAR(80) NULL,
 row_version BIGINT NOT NULL DEFAULT 0,updated_at DATETIME(3) NOT NULL,
 FOREIGN KEY(freeze_id) REFERENCES ticket_finance_quote_fence(freeze_id),
 CHECK(state IN('FENCING','FROZEN','RECONCILING','RELEASED')),
 CHECK(source_state IS NULL OR source_state IN('IN_PROGRESS','COMMITTED','ABORTED'))
) ENGINE=InnoDB;
INSERT INTO ticket_finance_coordination(freeze_id,state,source_state,next_attempt_at,updated_at)
 SELECT freeze_id,'FENCING',NULL,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM ticket_finance_quote_fence;
CREATE TABLE ticket_finance_owner_ack (
 freeze_id CHAR(36) NOT NULL,owner_name VARCHAR(16) NOT NULL,phase VARCHAR(16) NOT NULL,
 ack_digest CHAR(64) NOT NULL,payload_json LONGTEXT NOT NULL,recorded_at DATETIME(3) NOT NULL,
 PRIMARY KEY(freeze_id,owner_name,phase),
 FOREIGN KEY(freeze_id) REFERENCES ticket_finance_quote_fence(freeze_id),
 CHECK(owner_name IN('approval','stock')),CHECK(phase IN('FENCED','RELEASED'))
) ENGINE=InnoDB;
CREATE TABLE ticket_finance_coordination_audit (
 freeze_id CHAR(36) NOT NULL,event_name VARCHAR(32) NOT NULL,
 source_state VARCHAR(16) NULL,result_digest CHAR(64) NULL,recorded_at DATETIME(3) NOT NULL,
 PRIMARY KEY(freeze_id,event_name),FOREIGN KEY(freeze_id) REFERENCES ticket_finance_quote_fence(freeze_id)
) ENGINE=InnoDB;
