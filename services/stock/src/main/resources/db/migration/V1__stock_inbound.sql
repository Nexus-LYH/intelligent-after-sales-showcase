CREATE TABLE stock_warehouse (
 id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, name VARCHAR(100) NOT NULL,
 active BOOLEAN NOT NULL, UNIQUE(tenant_id,id)
);
CREATE TABLE stock_part (
 id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, code_normalized VARCHAR(64) NOT NULL,
 name VARCHAR(100) NOT NULL, model VARCHAR(100) NOT NULL, unit VARCHAR(20) NOT NULL,
 active BOOLEAN NOT NULL, safety_stock BIGINT NOT NULL,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,code_normalized), CHECK(safety_stock>=0)
);
CREATE TABLE stock_balance (
 tenant_id BIGINT NOT NULL, warehouse_id BIGINT NOT NULL, part_id BIGINT NOT NULL,
 on_hand BIGINT NOT NULL, reserved BIGINT NOT NULL, row_version BIGINT NOT NULL,
 PRIMARY KEY(tenant_id,warehouse_id,part_id),
 FOREIGN KEY(tenant_id,warehouse_id) REFERENCES stock_warehouse(tenant_id,id),
 FOREIGN KEY(tenant_id,part_id) REFERENCES stock_part(tenant_id,id),
 CHECK(on_hand>=0 AND reserved>=0 AND reserved<=on_hand AND row_version>=0)
);
CREATE TABLE stock_operation (
 tenant_id BIGINT NOT NULL, operation_id CHAR(36) NOT NULL, actor_id BIGINT NOT NULL,
 request_hash CHAR(64) NOT NULL, warehouse_id BIGINT NOT NULL, part_id BIGINT NOT NULL,
 quantity BIGINT NOT NULL, resulting_on_hand BIGINT NOT NULL, recorded_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(tenant_id,operation_id)
);
CREATE TABLE stock_movement (
 tenant_id BIGINT NOT NULL, operation_id CHAR(36) NOT NULL, warehouse_id BIGINT NOT NULL,
 part_id BIGINT NOT NULL, actor_id BIGINT NOT NULL, quantity BIGINT NOT NULL,
 source_text VARCHAR(500) NOT NULL, batch_note VARCHAR(500) NOT NULL,
 evidence_file_id CHAR(36) NOT NULL, recorded_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(tenant_id,operation_id), CHECK(quantity>0),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES stock_operation(tenant_id,operation_id)
);
CREATE TABLE stock_audit (
 tenant_id BIGINT NOT NULL, operation_id CHAR(36) NOT NULL, actor_id BIGINT NOT NULL,
 action_name VARCHAR(32) NOT NULL, recorded_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES stock_operation(tenant_id,operation_id)
);
