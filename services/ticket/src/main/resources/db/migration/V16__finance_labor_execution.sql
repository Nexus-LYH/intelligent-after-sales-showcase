-- Candidate only: root integrates explicit migration and append-only runtime grants.
CREATE TABLE ticket_fee_labor_execution (
 receipt_id CHAR(36) NOT NULL PRIMARY KEY,
 tenant_id BIGINT NOT NULL, company_id BIGINT NOT NULL, actor_user_id BIGINT NOT NULL,
 ticket_id CHAR(36) NOT NULL, round_id CHAR(36) NOT NULL, assignment_id CHAR(36) NOT NULL,
 group_id CHAR(36) NOT NULL, completion_id CHAR(36) NOT NULL, quote_id CHAR(36) NOT NULL,
 quote_version BIGINT NOT NULL, generation BIGINT NOT NULL, line_id CHAR(36) NOT NULL,
 processing_record_id CHAR(36) NOT NULL, kind VARCHAR(8) NOT NULL, amount_cents BIGINT NOT NULL,
 coverage_digest CHAR(64) NOT NULL, command_digest CHAR(64) NOT NULL,
 operation_id CHAR(36) NOT NULL, recorded_at DATETIME(3) NOT NULL,
 UNIQUE(tenant_id,group_id,line_id), UNIQUE(tenant_id,operation_id),
 CHECK(tenant_id>0 AND company_id>0 AND actor_user_id>0 AND quote_version>0 AND generation>0 AND amount_cents>0),
 CHECK(kind IN('LABOR','OTHER'))
) ENGINE=InnoDB;
CREATE TABLE ticket_fee_labor_operation (
 tenant_id BIGINT NOT NULL, operation_id CHAR(36) NOT NULL, actor_user_id BIGINT NOT NULL,
 ticket_id CHAR(36) NOT NULL, command_digest CHAR(64) NOT NULL, receipt_json LONGTEXT NOT NULL,
 recorded_at DATETIME(3) NOT NULL, PRIMARY KEY(tenant_id,operation_id)
) ENGINE=InnoDB;
CREATE TABLE ticket_fee_labor_audit (
 tenant_id BIGINT NOT NULL, operation_id CHAR(36) NOT NULL, actor_user_id BIGINT NOT NULL,
 ticket_id CHAR(36) NOT NULL, receipt_id CHAR(36) NOT NULL, action VARCHAR(40) NOT NULL,
 command_digest CHAR(64) NOT NULL, recorded_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,operation_id), UNIQUE(receipt_id), CHECK(action='FEE_LABOR_EXECUTED')
) ENGINE=InnoDB;
