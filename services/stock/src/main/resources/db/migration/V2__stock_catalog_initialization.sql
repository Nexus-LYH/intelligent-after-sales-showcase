-- Controlled initial catalogue creation; no stock quantities, user scopes or inbound facts are fabricated.
CREATE TABLE stock_catalog_initialization (
 tenant_id BIGINT PRIMARY KEY, operation_id CHAR(36) NOT NULL,
 request_hash CHAR(64) NOT NULL, operator_reference VARCHAR(100) NOT NULL,
 receipt_json TEXT NOT NULL, recorded_at TIMESTAMP(3) NOT NULL,
 UNIQUE(operation_id)
);
