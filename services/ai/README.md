# AI first slice: cost-bound/admission reference rules

Actual owner: the BE-N execution lane is now also BE-A for this isolated directory. No parent POM,
gateway, frontend, other domain source, shared runtime or secrets have been changed.

Source scope: original `18-AI应用与知识库专项需求.md` section 5, not a new AI feature extension.

Implemented deterministic price/config/model/currency snapshots, conservative maximum token cost
including every permitted billable retry, ceiling rounding, original operation/parameters, held cost,
unknown-result retention, one settlement or verified-no-charge release, and current tenant/actor checks.
`ReferenceLedger` deliberately uses a synchronized in-memory map as an executable specification;
it is **not** a durable billing ledger or production admission guard and must not be connected to a
paid model provider. Its 100-concurrent-request test is an in-process atomicity test, not a SQL/multi-Pod test.

2026-10-21 actual Java 21 independent Maven run: **9 tests passed, 0 failures/errors/skips**.
Reports: `target/surefire-reports/TEST-com.ias.ai.BudgetAdmissionTest.xml`.
The tests verify known finite upper bound before admission, retry budget, timeout/cancel keeping the
whole reservation, one settlement/release, current authorization before original-operation recovery,
tenant/actor/currency isolation, and concurrent acceptance (budget 0.9, reservation 0.3, exactly 3 of 100).
An unexpectedly greater real charge remains recorded as spent and blocks future admission; the
original PRD expressly does not claim an absolute cap on provider invoices.

Next runtime gate: durable budget row locks/unique operation, reservation/settlement/audit same transaction,
period/config identity, current trusted identity adapter, task/attempt persistence and original-result queries,
provider usage reconciliation, price/config metadata, explicit billing-result uncertainty and operational alert.
The final no-charge Boolean is a pure-model input representing a trusted reconciliation fact, not a user UI flag;
the durable adapter must bind and persist actual provider/administrator evidence and permission.

Remaining original AI/knowledge scope is not implemented here: PDF/text parse, versioned embedding/index,
publish/archive authorization, RAG/citations/streaming/task restore, summary/manual draft, read/write tools and
five-minute one-time confirmation, real model transport, actual 40-question evaluation. The placeholder model
identifier in tests is fictional. There was no model request, key/configuration read, budget consumption or
resource purchase. Real endpoints/model/Embedding IDs and existing quota remain the shared task **14.13**,
not a separate user questionnaire. No runtime/service or database window was occupied.

Reproduce with the existing JDK21 and project Maven settings, passing this independent `pom.xml`; do not
source `Enter-IdentityEnv.ps1`, start MySQL or use port 8081 to run these pure tests. BE-0 alone may integrate
the module into the shared reactor/CI after review. First B11 source-baseline push excludes this unfrozen slice.

## Durable budget slice — 2026-10-21

Actual current owner: BE-A / fe_approval_finish, explicitly handed over by the technical lead after the previous reference slice froze. Only `services/ai/**` was edited; frontend ownership was returned to the original FE-0 and was not edited again.

`JdbcBudgetLedger` now persists `ai_budget`, `ai_reservation` and `ai_budget_audit` using a single local JDBC transaction. The budget row is locked before capacity checks and every state change. Unique tenant/reservation UUID binds original actor, budget/period and the immutable model/config/price/token/retry upper-bound snapshot. Replayed numeric prices are canonically normalized so a database round trip cannot create a false conflict. Amounts are exact DECIMAL(38,12), with no silent storage rounding. The same UUID cannot move to another actor or period.

Current authorization is required before original-key recovery, then rechecked after acquiring the budget lock for mutation/replay. `Access` must be implemented by a trusted current identity and policy adapter, deriving tenant/actor, the permitted budget/period and price metadata server-side. `RECONCILE` is distinct from `RESERVE` and must not be granted to a normal model-calling browser user. Settlement/no-charge release requires a nonempty evidence reference; the adapter must verify that reference as a provider/accounting fact, not accept an arbitrary browser/model assertion. Budget provisioning/limit management is not exposed here; tests create only their isolated H2 fixture rows.

UNKNOWN retains the entire maximum. Actual settlement removes the original hold once and records the verified charge, including a real charge above its estimate; overspend then stops new admission rather than hiding a provider invoice. Verified no-charge release is separate. A late charge after a confirmed RELEASED state is rejected into explicit reconciliation rather than silently rewriting history. Each mutation and audit is atomic; a repeated command UUID with changed amount/evidence/target is rejected.

Connections are restricted to exact unpooled MySQL/H2 DataSource classes, initialized to a UTC session. An uncertain commit or rollback aborts the connection without restoring auto-commit or closing a pool wrapper. If abort also fails, a strong reference is quarantined and that ledger instance fails closed until operator reconciliation. This is not a general pooling adapter.

Actual verification at 19:40:56 with existing JDK21/project Maven configuration: **26 tests, zero failures/errors/skips** — original pure rules 9, real H2 JDBC 13, real-connection exception-proxy boundary 4. The 100-request SQL concurrency test admits exactly 3 holds of 0.3 under limit 0.9; same-key concurrent submissions produce one reservation/audit. Tests include process-object recreation/readback, period/tenant/actor isolation, currency/finite bound/storage precision, UNKNOWN retention, one settlement/release, overrun preservation, current revocation, audit rollback and original-key recovery, plus commit/rollback/abort uncertainty. Mockito is used only for the factory/exception proxy fault harness, not as a substitute for the H2 ledger database. Proxy faults are not physical commit-time process kills.

First JDBC run failed with 12 H2 CHECK errors caused by closing the DDL session used by constant-set constraints. Those original reports remain at `target/evidence/jdbc-first-run`. The fixture now keeps its DDL session until AfterEach; no production constraints were loosened. The corrected 22-test run and final 26-test run are separate rounds.

Not executed/accepted: MySQL3307 migration or locking/least-privilege tests, production budget configuration/period policy, trusted runtime authorization/evidence adapter, HTTP API, persistent model task/attempt dispatch lease, real provider calls, real billed-cost reconciliation, remote CI for this increment, independent acceptance or actual 40-question evaluation. An admission receipt alone does not prove that a caller may transmit the same provider request repeatedly; durable task/attempt dispatch fencing is still required. **Do not connect this partial adapter or the reference ledger to a paid provider.** No shared service/port/database was started/stopped or occupied, no secret/configuration value was read, and no model quota was consumed. Resource gaps remain the existing task section14.13.
