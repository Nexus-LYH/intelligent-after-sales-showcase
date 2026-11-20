CREATE TABLE fee_authorization_notification_cursor (
 tenant_id BIGINT NOT NULL,
 subject_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 generation BIGINT NOT NULL,
 PRIMARY KEY(tenant_id,subject_id), CHECK(generation>=0)
) ENGINE=InnoDB;
CREATE TABLE fee_event_notification_cursor (
 tenant_id BIGINT NOT NULL,
 producer VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 aggregate_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 generation BIGINT NOT NULL,
 aggregate_version DECIMAL(20,0) NOT NULL,
 PRIMARY KEY(tenant_id,producer,aggregate_id),
 CHECK(generation>=0), CHECK(aggregate_version>=0)
) ENGINE=InnoDB;
