CREATE TABLE stock_execution_order (
 tenant_id BIGINT NOT NULL, order_id CHAR(36) NOT NULL, warehouse_id BIGINT NOT NULL,
 approval_version BIGINT NOT NULL, approval_json LONGTEXT NOT NULL,
 state VARCHAR(24) NOT NULL, expires_at TIMESTAMP(3) NULL, row_version BIGINT NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, updated_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(tenant_id,order_id), CHECK(row_version>=0)
);
CREATE TABLE stock_execution_line (
 tenant_id BIGINT NOT NULL, order_id CHAR(36) NOT NULL, part_id BIGINT NOT NULL,
 quantity BIGINT NOT NULL, issued BIGINT NOT NULL, used BIGINT NOT NULL, returned BIGINT NOT NULL,
 PRIMARY KEY(tenant_id,order_id,part_id),
 FOREIGN KEY(tenant_id,order_id) REFERENCES stock_execution_order(tenant_id,order_id),
 CHECK(quantity>0 AND issued>=0 AND issued<=quantity AND used>=0 AND returned>=0 AND used<=issued-returned)
);
CREATE TABLE stock_execution_operation (
 tenant_id BIGINT NOT NULL, operation_id CHAR(36) NOT NULL, actor_id BIGINT NOT NULL,
 order_id CHAR(36) NOT NULL, action_name VARCHAR(24) NOT NULL, request_hash CHAR(64) NOT NULL,
 result_json LONGTEXT NOT NULL, recorded_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(tenant_id,operation_id)
);
CREATE TABLE stock_execution_movement (
 tenant_id BIGINT NOT NULL, operation_id CHAR(36) NOT NULL, part_id BIGINT NOT NULL,
 on_hand_delta BIGINT NOT NULL, reserved_delta BIGINT NOT NULL, used_delta BIGINT NOT NULL,
 PRIMARY KEY(tenant_id,operation_id,part_id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES stock_execution_operation(tenant_id,operation_id)
);
CREATE TABLE stock_execution_audit (
 tenant_id BIGINT NOT NULL, operation_id CHAR(36) NOT NULL, actor_id BIGINT NOT NULL,
 action_name VARCHAR(32) NOT NULL, recorded_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES stock_execution_operation(tenant_id,operation_id)
);
CREATE TABLE stock_execution_outbox (
 tenant_id BIGINT NOT NULL, operation_id CHAR(36) NOT NULL, event_id CHAR(36) NOT NULL,
 event_type VARCHAR(48) NOT NULL, payload_json LONGTEXT NOT NULL, recorded_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(tenant_id,operation_id), UNIQUE(event_id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES stock_execution_operation(tenant_id,operation_id)
);
CREATE TABLE stock_execution_delivery (
 tenant_id BIGINT NOT NULL, operation_id CHAR(36) NOT NULL, result_digest CHAR(64) NOT NULL,
 acknowledged_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES stock_execution_operation(tenant_id,operation_id)
);
