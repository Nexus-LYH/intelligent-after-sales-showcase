CREATE TABLE approval_execution_policy (
 tenant_id BIGINT NOT NULL, application_id CHAR(36) NOT NULL,
 charge_mode VARCHAR(20) NOT NULL, operation_id CHAR(36) NOT NULL,
 recorded_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,application_id), UNIQUE(tenant_id,operation_id),
 CHECK(charge_mode IN ('FREE','CUSTOMER_PAID'))
);
