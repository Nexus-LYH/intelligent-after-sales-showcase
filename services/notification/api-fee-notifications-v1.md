# Fee notifications v1 candidate

This notification-owned adapter accepts canonical `DomainEvent` envelopes for the already implemented facts below:

- `approval.fee_completed`: existing approval completion identifiers (`ticketId`, `roundId`, `feeGroupId`, `completionId`, `applicationId`, `quoteId`, `quoteVersion`, `generation`, `companyId`).
- `ticket.fee_execution_recorded`: LABOR/OTHER only, with exact payload (`ticketId`, `roundId`, `executionId`, `feeGroupId`, `generation`, `completionId`, `lineId`, `processingRecordId`, `amountCents`, `companyId`, `assignmentId`, `kind`). `kind=PART` is rejected.
- `finance.collection_finalized` / `finance.refund_finalized`: money terminal facts only, adapted from the frozen V8 event types.
- `inventory.consumed`: stock-owned PART only, with exact payload (`ticketId`, `roundId`, `executionId`, `feeGroupId`, `generation`, `lineId`, `warehouseId`, `partId`, `quantity`, `amountCents`, `companyId`). The operation UUID is both event and correlation ID, the execution receipt is the aggregate ID, and generation is the aggregate version.

The event never supplies recipients or authority. Delivery and every read re-resolve the current owner object plus current identity scope. BE-0 must provide exact trusted owner adapters before activation; absence, timeout, 404 or mismatch fails closed. The notification inbox, subject cursor, messages and local email captures commit in one notification transaction. A lower generation or lower aggregate version is committed as a suppression fact and produces no message. Duplicate event UUIDs return the original duplicate ACK.

Email addresses use the local-only `user-{id}@local.invalid` capture sink. No SMTP or external email transport exists in this slice.

Producer outboxes and business transactions are unchanged. They commit independently and use their durable retry/dead-letter/manual-replay delivery state, so notification downtime cannot roll back a producer business commit. The notification service ACKs only after its local transaction commits.

## Frozen BE-F V8 money terminal adapter

The final adapter is bound to the current finance candidate JAR SHA-256 `F0D09742ECF1199BA98B2D35AC91EF1D330F4AFD1C18BFE0B3E9FC79D1A4A33F`, `MoneyTerminalFacts.java` SHA-256 `41F1170CD4FFFD830D84A5B84E4003ED900646F96603DA6F982D01FF086EA762`, V8 SHA-256 `D6D0AEAA365F514ADC458DEC66D205C19E3ABDFBB4A2D382B8362BBCF5AAEC92`, and `api-money-v1.md` SHA-256 `D642562869BD7D15B7E2FF412E30D7F5FDE12B47E4B0D63DE06BA3721A164DAD`. The older `F1346DBB...` candidate is superseded.

`POST /internal/notification/finance-events` accepts exact JSON `{eventType,payload}`. Event type is `FINANCE_COLLECTION_FINALIZED` for `COLLECTION_RECORDED`, `COLLECTION_REVERSED`, or `COLLECTION_REBOOKED`; `FINANCE_REFUND_FINALIZED` accepts only `REFUND_VOUCHER_RECORDED`. Payload has exactly `schemaVersion=1`, `operationId`, `tenantId`, `companyId`, `groupId`, `billId`, `completionId`, `accountVersion`, `action`, `businessId`, nullable `originalPaymentId`, `amountCents`, `authorityDigest`, `executionReceiptsDigest`, and UTC `occurredAt`. All values except nullable `originalPaymentId` are strings. IDs, decimal strings and lowercase 64-hex digests are canonical and exact; extra/missing fields or event/action mismatch are rejected.

`operationId` is the stable inbox event ID. `groupId` is the subject and `accountVersion` is both the aggregate version and ordering generation. Lower account versions are committed to suppression audit without a user message. Equal event UUID and content returns duplicate ACK; changed content with the same operation conflicts. `COLLECTION_RECORDED` covers normal/partial collection and overpayment because the frozen terminal payload intentionally does not contain a derived balance label. Reverse amount must be represented by the producer as zero; rebook and refund preserve the original payment binding.

Every delivery and historical read calls the trusted finance owner context with the complete frozen binding, then resolves current identity recipients. Owner mismatch, revoked scope, 404, timeout or stale facts fail closed. BE-0 must add only the exact `finance-notification` ingress and `notification-finance` owner-context edge when integrating.

## Frozen stock PART owner adapter

Stock ingress is accepted only on `stock-notification`. Notification calls `POST /internal/stock/part-fee-notification-context` on `notification-stock` with the exact immutable fact set frozen by `stock-part-fee-notification-v1`: event, tenant, event type, company, ticket, round, fee group, generation, execution receipt, line, warehouse, part, quantity, amount, and `purpose=DELIVERY`. The response must echo every binding and must contain the matching fact/event versions, a canonical actor, a lowercase SHA-256 coverage digest, and `currentScope=true`, `qualified=true`, `revoked=false`. The current verified stock actor is the `ENGINEER` recipient; every historical projection repeats the stock owner lookup, so revoked or stale USE scope is hidden. A 403/404/409/timeout, stale response, missing field, extra/missing event payload field, or mismatched echo fails closed.

The notification candidate is frozen against the corrected stock protocol SHA-256 `7F105D420B97A32A9ADBB942872130115732AE34C8C1BA8E9D46A81383E462FB`, owner controller SHA-256 `2383A08D943BC81D2B55B13142851D874E8696279098243F9EA4093B82A72C91`, producer source SHA-256 `0A043BB237E3422EE943193F6E773A3F1D630C608686F6271832CC4B8A819980`, and stock JAR SHA-256 `F22F6BBAAD2EDEFDA40A3BFB63A73C1FFD671B8E1D25E313217943032C93BF55`. The producer now supplies `warehouseId`, `partId`, and `quantity`; notification keeps the same exact-field and owner-response validation without relaxation.

## Audited fee-completion recipient repair

`POST /internal/notification/fee-completions/repair` uses the existing approval-notification TLS edge. The exact body contains `operationId`, the original canonical `event`, explicit `recipientUserIds` as decimal strings, and `reason`. The service requires an existing matching notification inbox fact and rechecks every requested recipient against the current fee owner and identity scope.

Missing recipient messages, local email captures and the V9 repair audit commit in one transaction. Existing messages and read facts retain their identifiers and timestamps. Replaying the original operation and parameters returns its original count. Changed parameters conflict. Separate repair operations for the same event serialize and insert each missing recipient once. Current eligibility remains required when replaying a repair.

Apply V9 and grant the notification runtime account SELECT/INSERT on `notification_fee_recipient_repair` before invoking the endpoint. Existing repair-lock privileges remain required. Operator plans should retain the original owner event, its digest, explicit target IDs, reason and operation UUID in shared evidence. A duplicate event-delivery acknowledgement alone does not populate previously omitted recipients.
