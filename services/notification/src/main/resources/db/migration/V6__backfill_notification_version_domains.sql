INSERT INTO fee_authorization_notification_cursor(tenant_id,subject_id,generation)
SELECT tenant_id,object_id,MAX(scope_generation)
FROM notification_message
WHERE object_type='FEE' AND object_id IS NOT NULL
 AND JSON_UNQUOTE(JSON_EXTRACT(owner_event_json,'$.producer')) IN ('approval','ticket','stock')
GROUP BY tenant_id,object_id;
INSERT INTO fee_event_notification_cursor(tenant_id,producer,aggregate_id,generation,aggregate_version)
SELECT tenant_id,
 JSON_UNQUOTE(JSON_EXTRACT(owner_event_json,'$.producer')),
 JSON_UNQUOTE(JSON_EXTRACT(owner_event_json,'$.aggregateId')),
 MAX(CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(owner_event_json,'$.producer'))='finance' THEN 0 ELSE scope_generation END),
 MAX(CAST(JSON_UNQUOTE(JSON_EXTRACT(owner_event_json,'$.aggregateVersion')) AS DECIMAL(20,0)))
FROM notification_message
WHERE object_type='FEE'
GROUP BY tenant_id,JSON_UNQUOTE(JSON_EXTRACT(owner_event_json,'$.producer')),JSON_UNQUOTE(JSON_EXTRACT(owner_event_json,'$.aggregateId'));
