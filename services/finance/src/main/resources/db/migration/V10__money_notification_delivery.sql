CREATE TABLE finance_money_notification_delivery (
 tenant_id BIGINT NOT NULL, event_id CHAR(36) NOT NULL,
 state VARCHAR(16) NOT NULL, attempts INT NOT NULL,
 next_attempt_at TIMESTAMP(3) NOT NULL, lease_id CHAR(36) NULL,
 lease_until TIMESTAMP(3) NULL, delivered_at TIMESTAMP(3) NULL,
 last_error_code VARCHAR(64) NULL, updated_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(tenant_id,event_id),
 FOREIGN KEY(tenant_id,event_id) REFERENCES finance_outbox(tenant_id,event_id),
 CHECK(attempts>=0)
);
CREATE INDEX idx_finance_money_delivery_due ON finance_money_notification_delivery(state,next_attempt_at,lease_until);
