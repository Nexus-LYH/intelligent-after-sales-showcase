CREATE TABLE stock_finance_generation (
 tenant_id BIGINT NOT NULL,ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 generation BIGINT NOT NULL DEFAULT 0,active_freeze_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 row_version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(tenant_id,ticket_id),CHECK(generation>=0 AND row_version>=0)
) ENGINE=InnoDB;
CREATE TABLE stock_finance_fence (
 freeze_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL,company_id BIGINT NOT NULL,
 ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 round_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 group_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 expected_group_version BIGINT NOT NULL,quote_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 command_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 binding_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 ticket_fence_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 generation BIGINT NOT NULL,recorded_at DATETIME(3) NOT NULL,
 UNIQUE(tenant_id,ticket_id,generation),UNIQUE(tenant_id,ticket_id,source_operation_id),
 CHECK(expected_group_version>=0 AND generation>0)
) ENGINE=InnoDB;
CREATE TABLE stock_finance_ack (
 freeze_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 coverage_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 ack_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 receipt_json LONGTEXT NOT NULL,recorded_at DATETIME(3) NOT NULL,
 FOREIGN KEY(freeze_id) REFERENCES stock_finance_fence(freeze_id)
) ENGINE=InnoDB;
CREATE TABLE stock_finance_barrier_audit (
 event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 freeze_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 phase VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,recorded_at DATETIME(3) NOT NULL,
 FOREIGN KEY(freeze_id) REFERENCES stock_finance_fence(freeze_id),CHECK(phase IN('FENCED','OWNER_ACKED'))
) ENGINE=InnoDB;
CREATE TABLE stock_finance_execution_fact (
 tenant_id BIGINT NOT NULL,operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,generation BIGINT NOT NULL,
 order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,approval_version BIGINT NOT NULL,
 phase VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,charge_mode VARCHAR(24) NOT NULL,
 command_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,recorded_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,operation_id),CHECK(generation>=0 AND approval_version>=0)
) ENGINE=InnoDB;
