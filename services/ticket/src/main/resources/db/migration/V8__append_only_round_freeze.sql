CREATE TABLE ticket_round_freeze(
 round_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 snapshot_json JSON NOT NULL,ended_at DATETIME(3) NOT NULL,
 FOREIGN KEY(round_id,ticket_id) REFERENCES ticket_round(id,ticket_id)
) ENGINE=InnoDB;
INSERT INTO ticket_round_freeze(round_id,ticket_id,snapshot_json,ended_at)
 SELECT id,ticket_id,frozen_json,ended_at FROM ticket_round WHERE frozen_json IS NOT NULL;
ALTER TABLE ticket_round DROP CHECK ticket_round_chk_3;
ALTER TABLE ticket_round DROP COLUMN frozen_json,DROP COLUMN ended_at;
