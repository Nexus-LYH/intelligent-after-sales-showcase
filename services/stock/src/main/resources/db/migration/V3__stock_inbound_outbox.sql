-- Owner facts only. Delivery state belongs in a separate mutable delivery table.
-- No backfilled history or fabricated delivery claims; no consumer starts in this migration.
CREATE TABLE stock_outbox (
 tenant_id BIGINT NOT NULL,
 event_id CHAR(36) NOT NULL,
 operation_id CHAR(36) NOT NULL,
 event_type VARCHAR(40) NOT NULL,
 recorded_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(tenant_id,event_id),
 UNIQUE(tenant_id,operation_id,event_type),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES stock_operation(tenant_id,operation_id)
);
