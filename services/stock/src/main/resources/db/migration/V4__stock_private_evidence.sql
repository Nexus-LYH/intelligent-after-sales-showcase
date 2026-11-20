-- Immutable private owner storage; never a web/static path or a ticket attachment.
CREATE TABLE stock_evidence (
 tenant_id BIGINT NOT NULL, file_id CHAR(36) NOT NULL, actor_id BIGINT NOT NULL,
 warehouse_id BIGINT NOT NULL, content_hash CHAR(64) NOT NULL,
 content_type VARCHAR(40) NOT NULL, byte_size BIGINT NOT NULL,
 content_bytes LONGBLOB NOT NULL, recorded_at TIMESTAMP(3) NOT NULL,
 PRIMARY KEY(tenant_id,file_id),
 FOREIGN KEY(tenant_id,warehouse_id) REFERENCES stock_warehouse(tenant_id,id),
 CHECK(byte_size>0 AND byte_size<=20971520)
);
