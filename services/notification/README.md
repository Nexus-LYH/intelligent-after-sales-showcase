# Notification service and durable owner-delivery library (BE-N)

## Current executable slice, 2026-10-22

This section supersedes the historical first-slice limitations below for **implemented code only**.
Real MySQL/TLS/approval-to-notification HTTP and browser integration are still pending BE-0's shared
window evidence. Local MVC tests are not real network, model, SMTP or whole FR-NOTIFY acceptance.

`NotificationApplication` now wires a real Spring Boot HTTP service, trust `EdgeFilter`, current session
and CSRF checks, owner-fact/current-identity recipient resolution and durable actual self-inbox messages.
`api-messages-v1.json` freezes exact public DTOs and original-key recovery. Internal ingestion is
`POST /internal/notification/events` with exact body `{event:<canonical map>}` over the configured
`approval-notification` TLS edge. Only after the inbox and all recipient messages commit does it return
`{eventId:<original UUID>,ack:COMMITTED|COMMITTED_DUPLICATE}`. Event authority/recipient fields are rejected.
Owner P `notification-context` must match tenant/event/approval/type/version; identity supplies current
eligible reviewers and applicant. Even WAITCONFIG/no-reviewer still notifies the eligible applicant.
All reads and original read-operation recovery recheck current owner object and recipient qualification;
historical delivery/read facts cannot confer current authority. Read state, original key and audit share
one local transaction. Per-actor mutable lock rows serialize same-key/different-message races, while
immutable message/outbox facts require no UPDATE privilege. No automatic data/audit deletion is added.

### BE-0 migration, grants and private launch references

1. Build from the services reactor: `-pl notification -am package`. The plain
   `target/notification-0.0.1-SNAPSHOT.jar` remains a normal library for approval; run only
   `target/notification-0.0.1-SNAPSHOT-exec.jar` as the notification process.
2. Apply V1 then V2 from `src/main/resources/db/migration` to the **dedicated notification owner DB**
   using the migration identity. No application auto-migration is supplied. V1 creates generic owner
   tables too, but this consumer runtime does not need any privilege on outbox/delivery/replay/signal.
3. Runtime grants on its own database only: `SELECT,INSERT` for `event_inbox`, `notification_message`,
   `notification_read_operation`, `notification_read_audit`; `SELECT,INSERT` plus column-only
   `UPDATE(created_at)` on `notification_reader_lock`, and column-only `UPDATE(read_at)` on
   `notification_message_read`. These no-op updates acquire locks; no application delete/DDL, message
   update, outbox update or cross-domain database access. `FOR UPDATE` applies only to mutable tables.
4. Supply configuration via the current restricted BE-0 references; do not copy secret values into Git,
   this README, command output or evidence. Required environment properties: `IAS_CHAIN_CONFIG` for
   notification-role current TLS/edge configuration; `SPRING_DATASOURCE_URL`,
   `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`; `SPRING_SQL_INIT_MODE=never`;
   private `SERVER_SSL_*` material and `SERVER_PORT=8446` matching contract22. Main refuses missing
   trust role; missing trusted adapter is unavailable, never an anonymous/mock recipient source.
   The physical unpooled MysqlDataSource is deliberate; every connection uses UTC and uncertain
   rollback/abort connections are never returned to a pool. Do not replace it with Hikari silently.
5. BE-0 owns gateway exact routing, current trust edges, migration/grants and the only shared runtime
   window. No services or shared DB were started by BE-N. Test real 8446 owner ACK, lost-ACK retry,
   current self-list/detail/read, CSRF, scope loss, minimal grants, fresh current config and producer
   restart before promoting this code to real-delivery acceptance. SMTP/scheduler/operator replay,
   broad notification categories and full original PRD acceptance remain separate work.

Local tests include H2 inbox+message rollback, duplicate/lost ACK, self/tenant isolation, audit rollback,
same-key concurrency, current-rights loss, strict MVC envelope/header/body/query guards, unavailable
trust, freshness/exact owner echo, eligible reviewers/applicant and no-socket Boot wiring. H2 tests
retain the DDL session because H2 CHECK constant expressions need it; production SQL is not weakened.

## Historical delivery-library first slice

Ownership: this module only. Parent reactor, producer business writes, gateway/internal routes,
trust configuration and runtime launchers remain BE-0/domain-owner single-writer files.
This is a JDBC delivery library and local two-transaction transport slice, **not** a deployed
notification HTTP service, recipient resolver, email sender or complete FR-NOTIFY acceptance.

## Implemented

- `DomainEvent`: candidate `events-v1.yaml` reference-only envelope, immutable canonical encoding/hash,
  36 producer/type mappings, consumer subscriptions, strict duplicate JSON keys and millisecond UTC.
  Envelope tenant/producer are data: a verified internal identity adapter must supply producer identity.
- `JdbcDelivery.append`: uses the producer's already-open local business transaction; it never commits
  the caller connection. Install `event_outbox/event_delivery/event_replay_audit` in that same owner DB.
  No notification process reads another service's business tables. Each owner operates its own outbox.
- `claim`: commit a durable per-consumer lease before sending, `FOR UPDATE SKIP LOCKED`, stable event ID,
  expired-lease recovery, lease token checks on completion/failure. Maximum 3 in the tests; recommendation
  for normal runtime is 8 attempts, 30-second lease, transport timeout shorter than the lease. Backoff
  is 1/2/4/.../256 seconds, bounded; exhausted leases and invalid durable envelopes enter `DEAD`.
- `consume`: durable `(consumerId,eventId)` inbox plus local effect in one transaction, ACK only after
  commit. Committed duplicate returns a duplicate ACK; same ID with different tenant/body is rejected.
  A consumer effect may write only its own DB and must use the passed transaction, not independently
  commit or perform irreversible external I/O. `notification_signal` is an append-only event signal,
  **not** an authenticated user notification or authorization result. It does not overwrite a current
  object from a stale event; domain readers resolve current owner facts/version before new execution.
- `replay`: only `DEAD`, current tenant/operator permission callback, same UUID/parameters recovery,
  separate replay audit in the state-reset transaction. Replaying does not change the event ID/body/time.
- Physical connection ownership: constructor permits exactly unpooled MySQL `MysqlDataSource` and test
  H2 `JdbcDataSource`; arbitrary pools/wrappers are rejected. Each new connection sets its session to UTC,
  and JDBC timestamps explicitly use a UTC Calendar. Never restore auto-commit. A failed rollback aborts
  the owned physical connection. If abort also fails, quarantine it without calling `close`; it is not
  reusable or returned to a pool. Service-level readiness/alert and manual disposal are still integration work.

The first transport is deliberately local JDBC -> JDBC: `pumpOne((id,event) ->
consumer.consume(id, verifiedProducer, event, JdbcDelivery::signal))`. Producer and consumer objects
can use different owner data sources. A successful consume followed by a lost ACK is covered by
actual JDBC tests: the next delivery applies no second effect. No unauthenticated HTTP route is exposed.

## Reproduce without shared ports

Use the existing Java 21 and project Maven configuration, without dot-sourcing the environment script
(that script activates MySQL and port 8081, which this slice must not do):

```powershell
$env:JAVA_HOME='C:\ias-local\Develop\jdk21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
& 'C:\ias-local\Develop\Maven\apache-maven-3.9.16-bin\apache-maven-3.9.16\bin\mvn.cmd' `
  -gs 'C:\ias-local\Codex\Work\Intelligent After-Sales\环境工具\maven-global-empty.xml' `
  -s 'C:\ias-local\Codex\Work\Intelligent After-Sales\环境工具\maven-settings.xml' -f pom.xml test
```

Run from this module; the actual build used the absolute project configuration paths shown above.
Reports are `target/surefire-reports`.

2026-10-21 final local run: **23 tests, 0 failures/errors/skips**: 7 envelope, 12 actual H2 JDBC
transaction/concurrency/delivery tests, 4 injected connection-failure tests. H2 migration test removes
only MySQL charset/collation/engine/index syntax, retaining tables, uniqueness and checks. H2 2.4.240
constant-set CHECK expressions retain the DDL session: fixture now keeps that connection until teardown;
business operations still get fresh connections. Two earlier H2-session failures (15 tests/6 errors each)
are preserved under `target/evidence-first-run` and `target/evidence-second-run`. One later test-compile
error was an ambiguous generic JUnit assertion; fixed by an explicit Integer local. None is a 3307 result.

## BE-0/domain integration and remaining verification

1. Add this independent module to the reactor only under BE-0 ownership after reviewing the fixed slice.
   Apply the supplied MySQL migration with a migration identity to explicitly approved dedicated owner DBs;
   business identities need only the relevant table privileges, no DDL/DELETE, immutable outbox INSERT/SELECT.
   Notification DB owns inbox/signal only. Replay permission must be a live tenant-scoped capability check.
2. Producer business handlers create a stable event ID and call `append` on their **same physical transaction**
   after authorizing the business operation. Existing original-operation recovery happens before a new append.
   Do not append on an unrelated notification database and claim business/outbox atomicity.
   Current approval persistence has its own `approval_outbox`, not `event_outbox/event_delivery`. These are not
   interchangeable yet. BE-P/BE-0 must choose same-connection `append` integration or a producer-local durable
   adapter; cross-database relay is not a substitute for approval/business atomicity. This module did not modify
   approval files or silently claim that integration had passed.
3. BE-0/BE-N freeze verified producer identity, allowed internal destination, HTTPS/mTLS/signature/replay defense,
   delivery ACK/result format and configuration reference. Never copy trustedProducer from browser JSON/header.
   Network transport has **not** been implemented or exercised. Do not switch on a raw HTTP route to save time.
4. Wire bounded scheduler/health and dead-letter metrics/alert/operator UI; process up to a bounded batch and
   reschedule idle polling (no busy loop). DEAD is durable visible state, not proof of alert delivery. No automatic
   `job.failed` generation avoids recursion. User-recipient resolution, current download/action permissions,
   read status, SMTP delivery/dedup/unknown-result handling and reporting still need their real slices.
5. Coordinate the 3307 window before actual MySQL tests; do not reuse current B11 schemas/fixtures or stop the
   instance. Verify real MySQL SQL/locks/minimal grants, two owner DBs, restart after send or commit before ACK,
   concurrent workers, current replay scope and failure recovery. H2 and proxy faults are not MySQL or physical
   process-kill evidence. There is no claim of exactly-once transport, zero data loss or independent acceptance.

Resource gaps are only the shared task 14.13; this module neither requests credentials nor reads private
configuration or old rotation backups. No shared service/port/database was started, stopped or occupied.

### Producer integration increment, 2026-10-21

`appendOnOwnerTransaction` is now a static embedded adapter so an owner's existing physical business
transaction can append the canonical event and consumer delivery rows without creating a notification
connection or requiring a pooled datasource to pass the worker's physical-source restriction. It requires
an open transaction and the owner's UTC session; it never commits, authorizes an actor or sends transport.
Approval now calls this adapter in its store, preserving the legacy immutable outbox with the same event ID.
Thus the initial approval-table compatibility gap above is closed for **new writes in the tested H2 slice**;
legacy backfill, real MySQL migration/grants, owner-side worker scheduling and verified network ACK remain
prerequisites. Approval's new two-owner tests establish local JDBC handoff and lost-ACK duplicate suppression,
not real notification delivery, SMTP or independent acceptance of the whole feature.


### Owner worker lifecycle increment (2026-10-21)

DeliveryWorker now supplies bounded single-flight polling, immediate startup polling, fixed-delay subsequent ticks, aggregate health counters without exception text, and idempotent shutdown. JdbcDelivery remains the durable lease/retry/dead-letter owner; this worker never replaces original UUIDs or bypasses consumer inbox transactions. Tests cover bounded work, overlapping invocation, infrastructure failure recovery, lifecycle, and a persisted abandoned lease drained by a fresh worker with unchanged envelope and a single consumer effect.

This is a runnable lifecycle adapter, not yet a configured production process: owner startup wiring, physical MySQL datasource, V1-only backfill readiness checks, verified TLS transport/ACK and current recipient/message authorization remain required. No services or shared database were started by these tests. Do not call these local tests real network notification or SMTP acceptance.
