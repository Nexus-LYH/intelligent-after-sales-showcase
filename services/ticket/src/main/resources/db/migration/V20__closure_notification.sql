ALTER TABLE ticket_customer_notification_outbox DROP CHECK ticket_customer_notification_outbox_event;
ALTER TABLE ticket_customer_notification_outbox ADD CONSTRAINT ticket_customer_notification_outbox_event
 CHECK(event_type IN('ticket.accepted','ticket.resolution_submitted','ticket.closed'));
