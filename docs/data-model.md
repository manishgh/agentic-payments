# Simulator transaction and persistence design

Status: reviewed logical design; normalized PostgreSQL migrations and the bounded runtime now exist. Physical storage combines the inbox/evidence facts with a separate conflict table; the run lock serializes mutation eligibility. The preview uses claim-fence counters rather than timed leases. Full seeded schedule replay remains incomplete. See the current status and code review for implementation evidence and limits.
Profiles: `local-fake-single-tender-0.1` and `local-ap2-acp-single-tender-0.1`; contracts: [internal API](api-contracts.md); scope: [ADR 0001](adr/0001-simulator-preview.md) and [durable admission](adr/0006-durable-ap2-admission.md).
PostgreSQL is the recommended authoritative transaction store; graph checkpoints are separate, nonauthoritative orchestration storage.

## Storage ownership and values

Migration `V007` adds the explicit intent profile, native checkout bindings, public trust enrollments, challenges, accepted authority and consumed replay identities. Existing intents backfill to the legacy profile without replacing their attempts, operations or reservations. Exact signed artifacts live behind a separate immutable artifact-store port; references are validated again at admission. Staged artifacts may outlive a rolled-back financial transaction without granting authority. Replay consumption, grant binding, reservation and accepted command result share the authoritative transaction. See [durable composition](durable-composition.md) for replacement requirements and migration evidence.

Only Java core repositories write payment tables. Workers call application services; Python/UI never write transaction tables directly.
Every scenario row carries `tenant_id,run_id`; every reference uses `(tenant_id,run_id,id)` foreign keys. UUIDs alone are not authorization.
Amounts use `BIGINT` minor units, positive and bounded at the API; currencies are EUR/USD with exponent 2 in the fixture catalog.
No FX, fees, partial funding, mutable allocation, recurring payment, real credential or real provider result exists in v0.1.
Timestamps use `TIMESTAMPTZ`; decisions compare the injected run clock. System timestamps are diagnostic only.
All scenario rows carry `run_id`; fixture sessions and deterministic operation identity cannot collide across runs.
Normal API sessions are bound to one run; lookups, uniqueness scopes, inbox/outbox and command identities include authenticated tenant+run.
Merchants, grants and budgets are run-isolated fixtures; no shared cross-run budget exists. Reset requires stopped workers; no shared reset endpoint.

## Logical schema

| Table | Important fields | Constraints / ownership |
| --- | --- | --- |
| `simulation_runs` | tenant, run ID, scenario/version, seed, virtual clock, schedule/config digests, lifecycle | Immutable configuration; clock moves forward through runner commands only. |
| `merchants` | tenant, run, merchant ID, fixture catalog/version | Server-owned fixture identity, not body-supplied payee details. |
| `authority_grants` | tenant, run, grant ID, subject, merchant, budget ID, scope digest, valid interval, state, epoch/version | `local-fake` only; ACTIVE/REVOKED; mutable revocation, immutable scope. |
| `budgets` | tenant, run, budget ID, subject, currency, limit, held, consumed, frozen, version | One currency; normal `held >= 0`, `consumed >= 0`, `held + consumed <= limit`. |
| `payment_intents` | tenant, intent ID, run, merchant, phase, checkout ID, version, created/deadline | One approved checkout; phase transitions validated in application service. |
| `checkout_snapshots` | tenant, run, intent ID, revision, canonical bytes/digest, money, capability revision, tender descriptor, expiry | Unique `(tenant,run,intent,revision)`; immutable after insert; synthetic data only. |
| `payment_attempts` | tenant, run, attempt ID, intent ID, checkout revision, grant ID/epoch, owner, profile, amount/currency, state/version | Unique `(tenant,run,intent)`; owner and bindings immutable; no second attempt per intent. |
| `reservations` | tenant, run, reservation ID, budget ID, attempt ID, amount/currency, state | Unique `(tenant,run,attempt)`; HELD/CONSUMED/RELEASED; amount never changes. |
| `financial_operations` | tenant, run, operation ID, attempt ID, kind, adapter key, request digest, state, admission sequence/time, owner | Unique `(tenant,run,attempt,kind)`; immutable identity/key/request/owner. |
| `customer_actions` | tenant, run, action ID, attempt/operation ID, subject, checkout digest, expiry, state/version | OPEN/VERIFIED/DENIED/EXPIRED/CANCELLED; one current challenge per `(tenant,run,operation)`. |
| `commands` | tenant/run/principal/method/route/resource/key, validated request digest, accepted response/status, command ID | Unique scoped idempotency identity; response committed with mutation. |
| `outbox` | tenant, run, work ID, operation ID, kind, due clock, lease/fence, attempts, acknowledgement | Unique `(tenant,run,operation,kind)`; lease changes worker eligibility, never financial identity. |
| `event_inbox` | tenant, run, adapter/account, event ID, operation ID, fact sequence, safe fact/digest, verification result | Unique `(tenant,run,adapter,account,event)`; authenticated fixture-port facts only. |
| `operation_evidence` | tenant, run, evidence ID, operation ID, source, sequence, normalized outcome, artifact digest | Append-only safe facts; do not overwrite conflicting evidence. |
| `disputed_exposure` | tenant, run, dispute ID, budget/operation ID, possible amount/currency, evidence references, state | Exceptional invariant-breach tracking; budget frozen; never adds spendable capacity. |
| `audit_events` | tenant, run, event ID, aggregate/version, actor reference, command/event ID, safe change, clock | Append-only state history; no secret or full request dumps. |
| `simulator_operation_journal` | tenant, run, adapter key, immutable request digest, accepted fact sequence, outcome, sealed flag | Unique `(tenant,run,adapter key)`; adapter-owned persistence; survives core crash outside core transactions. |

`payment_intents.phase` is a transactionally updated projection of the sole attempt once approved; it cannot be independently advanced.
Before approval phase is DRAFT/QUOTED/EXPIRED. Attempt states are APPROVED/PREPARING/WAITING_ACTION/READY/ADMITTED/PENDING/UNKNOWN/SUCCEEDED/FAILED/CANCELLED/EXPIRED.
The complete allowed-transition table lives in [API contracts](api-contracts.md); repositories never expose a generic set-state operation.
SQL checks constrain enums and immutable shapes; application transitions require expected version and evidence predicates.

## Key invariants

1. Exactly one attempt and one execution owner per intent; one synthetic charge operation per attempt (EXT-003/CT-003).
2. Approved checkout digest, total, currency, merchant, subject, tender, grant and owner never change (EXT-007/CT-007).
3. Reservation amount equals full total; no partial release/consume; unknown exposure remains HELD (EXT-004).
4. Normal budget totals equal the sums of HELD and CONSUMED reservations; admissions cannot exceed the limit.
5. A charge is dispatched only after its immutable operation and durable admission record commit.
6. Revoked/expired authority cannot create a new admission; committed earlier admissions may still resolve after revocation (EXT-009).
7. Only verified authoritative final evidence consumes/releases an admitted reservation; absence of evidence is not failure.
8. Graph replay, lease expiry and process restart never create a replacement financial operation or route.
9. Event identity/sequence deduplicate transport and reject stale facts; a browser return cannot create payment evidence (EXT-005/006).
10. No credential, OTP, private key or raw sensitive payload exists in checkpoints/logs/evidence (EXT-010/CT-010).

## Transaction isolation and locking

Use PostgreSQL READ COMMITTED plus explicit row locks and unique constraints; do not rely on a preliminary unlocked read.
Every mutating transaction first locks its `simulation_runs` row, verifies ACTIVE and snapshots its current virtual time.
Runner clock advance locks the same row; it cannot advance between a command's expiry check and commit. Clock advance and admission have a durable total order.
Global lock order: run -> authority grants (ID order) -> budgets -> intents -> attempts -> operations -> actions -> reservations.
This deliberately serializes mutations within a run for deterministic simulation; different runs remain concurrent and isolated.
Read-only status uses one REPEATABLE READ transaction for coherent run-clock/resource snapshots; it does not change expiry state.
Commands for one intent may first read IDs without locks, then lock in that order and reread/revalidate all mutable state.
No code may lock an intent and then wait for its grant/budget. Retry transaction deadlocks/serialization conflicts with the same command identity.
Workers claim outbox leases in a short separate transaction; release those row locks before entering domain lock order.
Grant scope is immutable, so reading its budget ID before taking the grant lock cannot redirect the subsequent lock set.
All decisions and their command response/audit/outbox mutations commit together; never make an adapter call while database locks are held.

## Approval transaction

1. Authenticate/authorize; validate digest and command; lock run -> grant -> budget -> intent in canonical order.
2. Reread current quote, clock deadline, capability snapshot, grant scope/expiry/epoch and existing attempt.
3. Require QUOTED, no existing attempt, active authority, matching subject/merchant/method/credential/checkout and sufficient budget.
4. Generate attempt ID/immutable owner; bind local-fake authority to this execution identity and all checkout fields.
5. Increment budget HELD by full total; insert HELD reservation, APPROVED attempt and command response; advance intent/version.
6. Commit. Any failure rolls back all rows; concurrent approvals/reservations serialize on the same budget.

The authority fixture is a policy grant, not a signed mandate. The approval binding is a distinct synthetic authorization record.
No API caller can supply a budget limit, grant scope, authority epoch or trusted action result as an unverified assertion.

## Execute preparation and customer action

Execute locks authoritative rows, checks APPROVED/binding, inserts the sole immutable charge operation and effect-free PREPARE outbox item.
Its adapter key is a stable opaque UUID mapped uniquely to `(tenant,run,merchant,attempt,SYNTHETIC_CHARGE)`; include immutable request digest.
The key is created once, stored before dispatch and reused for every attempt to deliver that operation; no time-based regeneration.
The PREPARE worker is explicitly nonfinancial: scenario lookup either yields READY or a bound OPEN synthetic action.
Action approval verifies tenant/run, trusted subject/session, operation, checkout digest, expiry and one-use state under domain locks.
Successful approval marks VERIFIED and READY atomically; denial/expiry is pre-admission FAILED and releases the reservation.
Authority loss takes precedence over successful action continuation and produces CANCELLED/EXPIRED with release before admission.

## Dispatch admission and revocation ordering

READY produces a durable DISPATCH work item. A worker takes its lease, then locks run/grant/budget/intent/attempt/operation in order.
It checks lease fence, READY, unchanged bindings/capabilities, current grant epoch/validity and VERIFIED action when required.
The admission transaction writes ADMITTED, a monotonic admission sequence and immutable operation identity before the effectful send.
This commit is the linearization point named **dispatch admission**; the physical adapter call follows immediately outside the transaction.
The worker can crash between commit and send. It is therefore unsafe to claim either acceptance or no effect from ADMITTED alone.
No new admission occurs after a revocation commit; both operations serialize on the grant lock.
Revocation locks run, grant then affected budgets/intents in ID order, marks REVOKED/epoch+1 and cancels all pre-admission attempts.
It atomically releases their reservations/cancels open actions; admitted attempts keep HELD exposure and reconciliation work.
The revoke response distinguishes future blocked admissions from already-admitted attempts; it never promises an external rollback.
Authority expiry uses the same pre-admission rules, with EXPIRED instead of CANCELLED, under the run clock.
An admitted operation may complete after revocation/expiry; there is no second effectful capture stage in this simulator profile.

## Adapter journal, delivery and unknown outcomes

The simulator adapter has its own durable idempotency journal; delivery and core state update are intentionally separate commits.
For a new key, atomically bind request digest and accept or seal a definitive no-effect result; repeated keys replay the same result.
A digest mismatch rejects. Once sealed, a no-effect key can never execute later; delayed duplicate sends return that final no-effect fact.
Acceptance, delayed completion and final outcomes are journaled with monotonic fact sequence; transport loss does not erase them.
On send timeout or restart after admission, record UNKNOWN and enqueue lookup for the same operation/key; keep the full reservation.
Lookup NOT_FOUND alone is inconclusive because a stale admitted worker may still send; never release on absence.
The simulator reconciliation port can atomically seal a not-yet-accepted key as NO_EFFECT, fencing all future sends for that key.
If acceptance won the race, sealing returns the accepted/pending/final fact instead; it cannot cancel an already-accepted charge.
This atomic journal behavior is a stated simulator assumption, not a promised capability of real PSPs (EXT-004/CT-004).
Core crash after adapter acceptance is recovered by lookup/replayed facts, not by allocating a new operation.
Lost worker leases permit another worker to reconcile/replay only the same key. No universal exactly-once delivery is claimed.
No automatic unresolved deadline releases funds; unresolved attempts remain visible and require simulator evidence or operator investigation.

## Event application, late evidence and compensation

The runner delivers facts through an authenticated in-process adapter port bound to run/account/operation, not a public browser callback.
Spoofed or cross-run facts reject before inbox insertion; record only safe verification metadata (CT-006).
In one transaction insert/deduplicate inbox event, lock domain rows, append evidence, apply allowed transition and update reservation/budget.
Success requires full exact amount and matching immutable request; consume HELD -> CONSUMED and move budget held to consumed.
Definitive sealed no-effect permits FAILED and HELD -> RELEASED; pre-admission denial/revocation/expiry also releases exactly once.
Late matching success can resolve ADMITTED/PENDING/UNKNOWN after revoke. Stale lower-sequence pending facts cannot erase final success.
Conflicting final facts or altered money/binding are adapter-invariant violations: preserve all facts, freeze the budget, flag reconciliation.
Never reinterpret a prior confirmed success as failure. No automatic release/refund or new admission is allowed while a budget is frozen.
If contradictory evidence implies exposure beyond stored accounting, retain a separate disputed-exposure record and report the invariant breach.
That exceptional record does not make money spendable; independent review must decide repair, and the scenario fails conformance.
The simulator journal's sealing rule makes success-after-definitive-no-effect impossible for a correct adapter; negative fixtures test rejection.

Refund/cancel/compensate are unsupported operations and return capability refusal before dispatch.
Successful synthetic charges consume authority permanently for the run; refunds do not silently replenish spend permission.
Future compensation must have its own operation identity, ledger entries, limits and evidence; it is not transactional rollback.
Split tender requires a separately reviewed profile with per-leg identities/reservations, partial success and failed/nonreversible compensation.
Until that design exists, more than one tender or less than full-total funding rejects before reservation (CT-008 not claimed).

## Migration, recovery, retention and verification

V008 adds a separate [durable checkout aggregate](durable-external-checkout.md#storage-and-validation-scope): immutable enrolled configurations/current pointers, purchase projections, exact lifecycle commands and protected correlated outcomes, scoped by tenant/run/principal. The mutation barrier persists independently of latest GET results. These rows are commerce history, not financial reservations, AP2 authority or signed artifacts; no external lifecycle status changes the payment ledger.

V009 adds the standalone [provider compatibility journal](durable-provider-journal.md#storage-and-codec): immutable scoped binding/mapping, permanent dispatch ownership, complete evidence, retained conflict receipts and unique original resource ownership. Its SDK run key remains text, independently of mapped legacy UUIDs. It has no foreign key to core payment/admission rows. Any recorded evidence prevents a new initial owner; conflict state commits with its evidence and cannot be cleared by lookup or a new process.

Use numbered SQL migrations owned by core; first migration creates tables/keys/checks/indexes, then fixtures are loaded explicitly.
Index tenant+run-qualified status/read queries, authority/intent references, unique idempotency scopes, due outbox work and inbox event IDs.
Reference keys use `(tenant_id,run_id,id)`; all unique financial/command/event scopes include run. Test cross-tenant and same-tenant cross-run refusal.
Migration tests run against an empty database and rollback where practical; no down migration may discard unresolved operations silently.
Use bounded database transaction retries; return 503 with SAME_COMMAND on exhausted precommit infrastructure failures.
Recovery scans unacknowledged outbox and ADMITTED/PENDING/UNKNOWN operations; uses persisted keys and journal lookup.
Graph checkpoints contain intent/attempt/operation/action IDs and safe summaries only; IDs still require tenant+run authorization.
Synthetic credential references are resolved inside core/adapter, never serialized into graph status or exception/log objects.
Reports retain safe versions/digests, clock/schedule, CT outcomes and normalized evidence; no raw session or payload dumps.
Run-lifetime retention is the preview policy; deletion is explicit isolated-run teardown after worker stop, not a time-based financial cleanup.
Future real evidence retention/encryption/key policy is a G2-P/managed-service decision, not implied by local fixtures.
G2-S tests must cover CT-001..007 and CT-009..012; CT-008 is explicitly unsupported, with rejection tests instead of a claimed pass.
Required fault interleavings: competing budgets, revoke-before/after admission, stale lease, acceptance-response loss, core crash and late/duplicate facts.
Add clock-advance races at quote/authority/action expiry: whichever run-row transaction commits first determines eligibility, never wall-clock timing.
Evidence must include normalized state/reservation/journal digests and demonstrate identical replay from pinned clock/schedule/configuration.
