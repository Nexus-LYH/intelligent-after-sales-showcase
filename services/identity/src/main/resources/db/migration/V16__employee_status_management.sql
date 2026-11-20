ALTER TABLE identity_user_capability DROP CHECK ck_capability_code;
ALTER TABLE identity_user_capability ADD CONSTRAINT ck_capability_code CHECK(capability_code IN('identity.session.revoke','identity.employee.manage','identity.platform.session.revoke','asset.read','asset.manage','identity.customer.provision','ticket.read','ticket.accept','ticket.reject','ticket.assign','ticket.engineer.read','ticket.assignment.accept','ticket.record','ticket.solution.submit','ticket.close','ticket.supplement.request','ticket.cancel.review','ticket.transfer.request','ticket.reopen.review','ticket.respond','approval.parts.read','approval.parts.submit','approval.parts.review','stock.read','stock.manage','finance.quote.read','finance.quote.write','finance.quote.confirm','approval.fees.read','approval.fees.submit','approval.fees.review','approval.fees.finance','finance.money.read','finance.money.assess','finance.money.adjust','finance.money.collect','finance.money.reverse','finance.money.refund'));

CREATE TABLE identity_employee_status_operation (
 operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL,actor_user_id BIGINT NOT NULL,target_user_id BIGINT NOT NULL,
 request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 expected_security_version BIGINT NOT NULL,new_security_version BIGINT NOT NULL,
 old_status TINYINT NOT NULL,new_status TINYINT NOT NULL,reason VARCHAR(200) NOT NULL,
 occurred_at DATETIME(3) NOT NULL,trace_id VARCHAR(100) NOT NULL,
 FOREIGN KEY(tenant_id) REFERENCES tenant(id),FOREIGN KEY(actor_user_id) REFERENCES identity_user(id),FOREIGN KEY(target_user_id) REFERENCES identity_user(id),
 CHECK(old_status IN (0,1) AND new_status IN (0,1))
) ENGINE=InnoDB;
