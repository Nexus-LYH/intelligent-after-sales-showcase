CREATE TABLE stock_finance_terminal (
 freeze_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 binding_digest CHAR(64) NOT NULL,ticket_fence_digest CHAR(64) NOT NULL,
 source_state VARCHAR(16) NOT NULL,result_digest CHAR(64) NULL,
 receipt_json LONGTEXT NOT NULL,recorded_at DATETIME(3) NOT NULL,
 CHECK(source_state IN('COMMITTED','ABORTED'))
) ENGINE=InnoDB;
CREATE TABLE stock_finance_terminal_audit (
 freeze_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 source_state VARCHAR(16) NOT NULL,ack_digest CHAR(64) NOT NULL,recorded_at DATETIME(3) NOT NULL
) ENGINE=InnoDB;
