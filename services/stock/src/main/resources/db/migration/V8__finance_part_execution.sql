CREATE TABLE stock_finance_part_execution (
 tenant_id BIGINT NOT NULL,
 execution_receipt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_user_id BIGINT NOT NULL,company_id BIGINT NOT NULL,
 group_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 completion_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 quote_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 quote_version BIGINT NOT NULL,generation BIGINT NOT NULL,
 line_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 round_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 use_operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 warehouse_id BIGINT NOT NULL,part_id BIGINT NOT NULL,quantity BIGINT NOT NULL,amount_cents BIGINT NOT NULL,
 coverage_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,recorded_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,execution_receipt_id),
 UNIQUE(tenant_id,group_id,generation,line_id),
 UNIQUE(tenant_id,use_operation_id,part_id),
 CHECK(quote_version>0 AND generation>0 AND warehouse_id>0 AND part_id>0 AND quantity>0 AND amount_cents>0)
) ENGINE=InnoDB;

CREATE TABLE stock_finance_part_operation (
 tenant_id BIGINT NOT NULL,operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_user_id BIGINT NOT NULL,request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 result_json LONGTEXT NOT NULL,recorded_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,operation_id)
) ENGINE=InnoDB;

CREATE TABLE stock_finance_part_audit (
 tenant_id BIGINT NOT NULL,operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 actor_user_id BIGINT NOT NULL,action_name VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 recorded_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES stock_finance_part_operation(tenant_id,operation_id),
 CHECK(action_name='FINANCE_PART_EXECUTED')
) ENGINE=InnoDB;
