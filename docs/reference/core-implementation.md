# Local core implementation

[Documentation](../README.md) / [Reference](README.md)
Status: bounded local reference implementation; not a production payment system or a certified profile.
The synthetic execution model follows [ADR 0002](../adr/0002-simulator-execution.md). This guide describes the shared local core; the separately selectable [native composition](../concepts/durable-composition.md) adds independently implemented AP2 signature verification and an embedded ACP checkout.
There are no live/sandbox provider connectors or external ACP merchant HTTP adapters.

## Composition and methods

`PaymentService` is the application facade and depends on the `PaymentStore` application port.
`PaymentServices.local(DataSource)` composes it with `JdbcPaymentStore` and `JdbcSimulatorAdapter`.
Tests can inject a trusted `PaymentAdapter` through the infrastructure composition helper; HTTP clients cannot select one.
`DatabaseMigrator.migrate(DataSource)` must run before the service is used.

The typed commands implement create, quote, approve, execute, complete-action and revoke.
Read methods return graph-safe intent status, trusted action metadata and trusted checkout review data.
The added trusted checkout method requires `approval:write`, an allowed grant and its subject, with intent state QUOTED/APPROVED.
It returns authoritative checkout revision/digest/money without the synthetic credential reference.
An immutable intent has at most one approved attempt and one financial operation; a material checkout change after approval is refused.
Results carry HTTP status, a safe body and replay indication; transport wiring belongs outside the application/domain packages.

Runner methods are private application integration surfaces, not public HTTP endpoints:

| Method | Effect |
| --- | --- |
| `seedRun(session,clock,budget)` | Idempotently creates isolated local run, EUR/USD budgets and fixture grants owned by the seed principal. |
| `advanceClock(session,clock)` | Advances virtual time monotonically under the same run lock used by payment mutations. |
| `runNext(session)` | Runs one durable preparation/dispatch/reconciliation step; scheduled pre-admission expiry is also inspected. |
| `reconcile(session,intent,sealIfAbsent)` | Queries the same operation; optional atomic no-effect seal only when acceptance is absent. |

All runner methods require `runner:write`; graph/UI sessions must not receive that role.
The fixture catalogs are `merchant-demo`, `cart-1250-eur` / `cart-1250-usd`, and `fixture-credential-1`.
Grants are `fixture-grant-1` (EUR) and `fixture-grant-usd` (USD), valid for one virtual hour from initial seeding.
They are `local-fake` policy fixtures, not valid AP2 mandates. Synthetic challenge expiry is ten virtual minutes.
Quote expiry is fifteen virtual minutes; all intervals are half-open at expiry.

## Persistence and ordering

Migrations are checksum-verified and applied transactionally under a PostgreSQL advisory migration lock:

- V001 creates normalized runs, grants/budgets, intents/snapshots, attempts/reservations, operations/actions, commands/outbox/inbox, evidence/audit and independent journal tables.
- V002 retains authenticated conflicting fact variants as safe JSON/digests rather than overwriting the original evidence.
- V003 distinguishes accepted payment evidence from quarantined facts; status cannot advertise a rejected fact as successful outcome evidence.

The adapter journal has no cascading reference to core aggregates; adapter calls use separate connections and commits.
No core transaction is held while calling adapter preparation, execution, query, seal or event verification.
Core mutations use READ COMMITTED with the run row locked first; this deliberately serializes all mutations in one run.
Status uses REPEATABLE READ and changes no state. Tenant and run scope every lookup, reference and command identity.
The global run lock is stronger than the detailed per-aggregate ordering and avoids same-run write races in this local profile.
This is a throughput compromise; it is not the managed-platform concurrency design.

Approval reserves the whole exact amount atomically with creation of the immutable attempt/owner.
Admission rechecks authority, capabilities, reservation and bound unexpired action, then records admission before physical send.
Revocation before that commit prevents admission; a previously admitted operation may complete afterward.
ADMITTED/PENDING/UNKNOWN exposure stays HELD until authoritative success or definitive no-effect evidence.
NOT_FOUND alone never releases it. Atomic journal sealing fences a future stale send of the same operation.
Frozen budgets block new admissions and preserve disputed held exposure; later no-effect evidence cannot silently release it.
Authentic success may consume known exposure without removing the freeze or hiding contradictory evidence.

The adapter journal models immediate success, trusted synthetic challenge, response loss after acceptance, delayed success and definitive no-effect.
A delayed accepted charge progresses to synthetic success on a subsequent explicit reconciliation worker step.
There are no wall-clock settlement promises, captures, refunds, route switches, split tenders or compensation operations.
Outbox work identities and fences survive service reconstruction; stale preparation cannot resurrect a revoked attempt.
Dispatch and journal commits are intentionally separate; recovery consults durable identity/evidence across that gap.

## Checks and evidence

Use the repository's toolchain/database scripts and documented Maven command with the ignored local Maven cache.
Database tests require `PAYMENTS_DB_URL`, `PAYMENTS_DB_USER` and `PAYMENTS_DB_PASSWORD`; they fail if configuration is absent.
Credentials belong only in ignored local configuration or environment variables, never commands printed into reports.
Example when the environment is already configured:

```powershell
& .tools/apache-maven-3.9.16/bin/mvn.cmd '-Dmaven.repo.local=.tools/m2' test
```

`PaymentCoreIntegrationTest` exercises real PostgreSQL with isolated runs, including:

- exact 402-byte checkout golden digest, durable reconstruction, replay/conflict and concurrent budget admission;
- response-loss uncertainty/reconciliation, trusted action subject/replay/denial/expiry and changed checkout binding;
- revocation before admission and barrier-controlled revocation after admission but before the adapter send;
- stale preparation after revocation, NOT_FOUND hold, no-effect sealing/stale send and acceptance-before-seal;
- unknown capabilities, removed capabilities before admission, split-tender rejection and faulty missing challenge;
- same-tenant cross-run/cross-tenant refusal, retained authenticated conflict variants and stable accepted outcome evidence;
- frozen unknown exposure retaining its hold after contradictory no-effect evidence, expiry and revocation.

`OperationBindingTest` freezes the separate 666-byte synthetic dispatch encoding and verifies that scenario changes alter its digest.
Run the conformance suite to obtain test results and source identity for the current revision.
Tests use explicit latches for reviewed ordering gaps and virtual time for expiry, rather than timing sleeps.

`DurableBoundaryIntegrationTest` adds executable evidence beyond service-object reconstruction:

- A fresh JVM executes the already-persisted operation and abruptly calls `Runtime.halt(73)` immediately after the independent journal commits success, before a fact reaches core.
- Parent-side database witnesses then prove ADMITTED/HELD, one journal success and zero core evidence. A second fresh JVM reconnects and consumes once; a third replays recovery without another charge or consumption.
- Test credentials travel only through inherited environment variables. Child arguments contain mode and nonsecret resource IDs; output is limited to safe outcome markers.
- A test-only DataSource proxy holds the actual PostgreSQL run-row lock while a competing command attempts it. Both deterministic commit orders are tested for clock advance versus quote approval, grant admission and trusted action completion at exact expiry.
- Trusted checkout refuses wrong subject, merchant scope, tenant, run, grant scope and role; denial creates no attempt or budget exposure.
- Authenticated wrong-binding and lower-sequence contradictory final facts preserve safe conflict evidence and freeze the budget; a distinct stale pending event cannot regress success or replace its accepted proof.

These eleven boundary tests passed against local PostgreSQL. They do not add production fault hooks or a public event injection endpoint.

## Limits and remaining verification

`JdbcPaymentStore` currently combines transactional use-case coordination and SQL repository work behind the application port.
Pure canonical encoding and fixed-profile rules are in the domain package; extracting richer domain transitions is a maintainability follow-up.
The local scope requires the complete exact simulator capability offer; broader negotiated/partial provider coverage is not implemented.
Run seeding and runner authentication are local fixture mechanisms, not production identity, enrollment or authorization services.
The journal-backed verifier establishes local adapter trust only; it is not cryptographic provider webhook verification.
The integration tests cover selected deliberate conflicts through a trusted injected test adapter unavailable to HTTP callers.
One actual abrupt-JVM commit-gap/recovery path is tested; this does not cover every possible process-kill point or every provider state.
Cryptographic, broad secret-canary and complete deterministic-replay evidence remain outside these boundary tests.
In particular, a seed/config/schedule evidence runner and normalized whole-run replay digest still require further CT-011 work.
Relevant core fault tests are evidence for their named cases, not a claim that all CT-001 through CT-012 pass.
Project certification, real provider compatibility, managed availability and live operation remain unclaimed and separately gated.
