CREATE TABLE ai_budget (
 tenant_id BIGINT NOT NULL, budget_id VARCHAR(100) NOT NULL, period_key VARCHAR(100) NOT NULL,
 currency CHAR(3) NOT NULL, limit_amount DECIMAL(38,12) NOT NULL,
 held_amount DECIMAL(38,12) NOT NULL DEFAULT 0, spent_amount DECIMAL(38,12) NOT NULL DEFAULT 0,
 row_version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(tenant_id,budget_id,period_key),
 CHECK(limit_amount>=0 AND held_amount>=0 AND spent_amount>=0 AND row_version>=0)
) ENGINE=InnoDB;
CREATE TABLE ai_reservation (
 tenant_id BIGINT NOT NULL, operation_id CHAR(36) NOT NULL, actor_id BIGINT NOT NULL,
 budget_id VARCHAR(100) NOT NULL, period_key VARCHAR(100) NOT NULL,
 command_hash CHAR(64) NOT NULL, state VARCHAR(16) NOT NULL,
 model_id VARCHAR(100) NOT NULL, config_version VARCHAR(100) NOT NULL, price_version VARCHAR(100) NOT NULL,
 currency CHAR(3) NOT NULL, input_price DECIMAL(38,12) NOT NULL, output_price DECIMAL(38,12) NOT NULL,
 max_input BIGINT NOT NULL, max_output BIGINT NOT NULL, max_attempts INT NOT NULL,
 maximum_cost DECIMAL(38,12) NOT NULL, actual_cost DECIMAL(38,12) NULL,
 created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,budget_id,period_key) REFERENCES ai_budget(tenant_id,budget_id,period_key),
 CHECK(state IN('RESERVED','UNKNOWN','SETTLED','RELEASED')),
 CHECK(maximum_cost>=0 AND max_input>0 AND max_output>0 AND max_attempts BETWEEN 1 AND 10),
 CHECK((state='SETTLED')=(actual_cost IS NOT NULL)), CHECK(actual_cost IS NULL OR actual_cost>=0)
) ENGINE=InnoDB;
CREATE TABLE ai_budget_audit (
 tenant_id BIGINT NOT NULL, command_id CHAR(36) NOT NULL, reservation_id CHAR(36) NOT NULL,
 actor_id BIGINT NOT NULL, action_name VARCHAR(24) NOT NULL, command_hash CHAR(64) NOT NULL,
 evidence_ref VARCHAR(200) NOT NULL, occurred_at DATETIME(3) NOT NULL,
 PRIMARY KEY(tenant_id,command_id),
 FOREIGN KEY(tenant_id,reservation_id) REFERENCES ai_reservation(tenant_id,operation_id)
) ENGINE=InnoDB;
