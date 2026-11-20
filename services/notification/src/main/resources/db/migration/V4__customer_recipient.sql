-- Customer-visible messages need a first-class CUSTOMER recipient kind.
ALTER TABLE notification_message DROP CHECK notification_message_chk_1;
ALTER TABLE notification_message ADD CONSTRAINT notification_message_recipient_kind
 CHECK(recipient_kind IN('APPLICANT','REVIEWER','ENGINEER','FINANCE','CUSTOMER_ADMIN','CUSTOMER'));
