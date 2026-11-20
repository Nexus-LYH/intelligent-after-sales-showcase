ALTER TABLE identity_user_capability DROP CHECK ck_capability_code;
ALTER TABLE identity_user_capability ADD CONSTRAINT ck_capability_code CHECK(capability_code IN('identity.session.revoke','identity.platform.session.revoke','asset.read','asset.manage','identity.customer.provision','ticket.read','ticket.accept','ticket.reject','ticket.assign','ticket.engineer.read','ticket.assignment.accept','ticket.record','ticket.solution.submit','ticket.close','ticket.supplement.request','ticket.cancel.review','ticket.transfer.request','ticket.reopen.review','ticket.respond','approval.parts.read','approval.parts.submit','approval.parts.review','stock.read','stock.manage'));
CREATE TABLE identity_approval_department_scope(
 tenant_id BIGINT NOT NULL,user_id BIGINT NOT NULL,department_id BIGINT NOT NULL,enabled TINYINT NOT NULL,
 PRIMARY KEY(user_id,department_id),FOREIGN KEY(tenant_id,user_id) REFERENCES identity_employee_configuration(tenant_id,user_id),FOREIGN KEY(tenant_id,department_id) REFERENCES identity_department(tenant_id,id),
 CHECK(enabled IN(0,1)),KEY ix_approval_scope(tenant_id,user_id,enabled,department_id)
) ENGINE=InnoDB;
CREATE TABLE identity_warehouse_scope(
 tenant_id BIGINT NOT NULL,user_id BIGINT NOT NULL,warehouse_id BIGINT NOT NULL,enabled TINYINT NOT NULL,
 PRIMARY KEY(user_id,warehouse_id),FOREIGN KEY(tenant_id,user_id) REFERENCES identity_employee_configuration(tenant_id,user_id),
 CHECK(enabled IN(0,1) AND warehouse_id>0),KEY ix_warehouse_scope(tenant_id,user_id,enabled,warehouse_id)
) ENGINE=InnoDB;
