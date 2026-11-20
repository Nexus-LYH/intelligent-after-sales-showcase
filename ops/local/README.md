# Private local eight-role runner

`Start-LocalChainService.ps1 -Service <role>` consumes operator-provided external references:
`IAS_RUNTIME_ENV_SCRIPT`, `IAS_CHAIN_DIRECTORY`, `IAS_MYSQL_CREDENTIALS_CONFIG`
(existing asset/ticket credentials), and `IAS_DOMAIN_RUNTIME_CONFIG` (new domain principals).
Provide references through the local process environment; never commit their files or secret values.

The runner preserves TLS for each internal service and selects each new domain's own schema/principal
after loading the existing environment. It rejects a mismatched datasource instead of inheriting the
identity connection. Approval starts its durable worker only with the owner datasource; migrate all
published SQL and reconcile legacy source/event bindings before startup. Notification uses the executable
`-exec` classifier; its ordinary JAR remains a dependency for approval.

Finance uses port 8447 and the finance principal from `IAS_DOMAIN_RUNTIME_CONFIG`.
The launcher explicitly sets owner delivery switches for approval, ticket fee/customer notifications,
finance notifications, and stock PART fee notifications. Each switch is enabled only in its owner
process; enabling delivery does not bypass business authorization or prove end-to-end receipt.

This is a local isolated-environment entrypoint, not Linux/Kubernetes deployment evidence. The project
operator must coordinate service windows and verify process ownership before stopping a process.

Finance and administrator prerequisites are explicit: asset V3 membership role tables and immutable
operation/audit SELECT/INSERT, role-only UPDATE columns, identity current-customer-eligibility endpoint,
and an actually published approval fee-rule version. Publishing a rule does not grant quote authority.
Ticket V12 is an append-only quote-fence candidate; production prepare fails closed until the finance
source-intent adapter exists. Its source-commit receipt is not a global approval/stock freeze ACK.
Do not enable finance quote writes based on expected version zero, empty execution lists, or a portable
CI result. Coordinate current service windows before any migration or JAR replacement.
