# ADR 0002: Durable execution for the local simulator

Status: accepted for the bounded reference profile described below.
Date: 2026-11-06. Scope: `0.1.0-draft`, `local-fake-single-tender-0.1`, `simulator-api-0.1-draft`.

## Context and bounded decision

[ADR 0001](0001-simulator-preview.md) accepted only create/quote/approve/reserve/read.
The existing [API contract](../api-contracts.md) and [data model](../data-model.md) already propose later dispatch, challenge, journal and reconciliation behavior.
Extend implementation scope to those synthetic operations after the integration owner accepts this clarification and the independent review recommendation.
The mandatory bootstrap gate still applies. Code acceptance additionally requires PostgreSQL, failure-recovery and independent code-review evidence.

Use a modular Java core with immutable domain values, application use cases and explicit persistence/adapter/identity ports.
Use PostgreSQL for both core truth and an adapter-owned journal, with separate connections and commits; no distributed transaction joins them.
One local synthetic full charge is the entire economic model. Its success is not authorization, capture, settlement or real checkout fulfillment.
No live or sandbox network connector, AP2 signature verifier, ACP wire adapter, split voucher, refund or compensation is included.
Future adapters and optional profiles can implement additional capability-specific ports; they cannot inherit simulator sealing guarantees.
Unsupported capabilities reject before reservation. Keep the existing provider/method/credential/action axes distinct.

## Port contracts and ownership

These describe minimum semantics; implementation may choose equivalent Java names without changing the reviewed HTTP contract.
Domain records and port interfaces must not import JDBC, HTTP, provider libraries or orchestration code.

| Port/use case | Input and result | Required behavior |
| --- | --- | --- |
| `prepare` | Immutable operation request -> READY, ACTION_REQUIRED or definitive pre-admission validation refusal | Pure fixture interpretation; no journal acceptance, charge, network call or financial effect. Repeating it cannot allocate another current action. |
| `dispatch` | Persisted admitted operation request -> normalized fact, or unknown transport outcome | Uses exactly the saved operation UUID/key/digest; never routes elsewhere or creates an identity. Called outside core transaction. |
| `query` | Tenant/run/account/key/digest -> NOT_FOUND or current normalized fact | Read-only absence is inconclusive. Neither timeout nor NOT_FOUND authorizes release. |
| `sealIfAbsent` | Same identity and immutable binding -> durable NO_EFFECT or existing accepted/final fact | Atomically installs a permanent no-effect tombstone only if acceptance has not occurred. Never cancels accepted pending work. |
| `verifyEvent` | Adapter-internal event handle plus trusted adapter context -> verified normalized fact or rejection | Resolve and compare durable journal event data; a caller-supplied `verified=true` is never authentication. |
| `applyFact` | Verified safe fact -> transactional evidence/state/accounting result | Only application core mutates payment truth; final evidence consumes or releases exactly once. |
| `completeAction` | Authenticated trusted subject, action ID, expected version, approve/deny | Checks binding/clock/one-use state; returns continuation or pre-admission failure, never payment success. |
| `advanceClock` / `runNext` | Explicit runner-only test schedule | Not exposed to graph/UI roles; commits monotonic virtual time and recorded ordered steps. |

The immutable operation envelope includes tenant/run, simulator account, merchant, intent, attempt, operation UUID, stable adapter key,
kind `SYNTHETIC_CHARGE`, owner `simulator-core`, exact money, checkout digest, capability/catalog revision and local authority binding.
Construct its request digest from a versioned, fixed-order encoding of typed values. Freeze those bytes and a golden fixture in implementation tests.
The operation UUID and adapter key may be the same UUID in this profile; either way persist their unique mapping before any adapter call.
Synthetic credential references are adapter inputs resolved inside the trusted core; graph/status/evidence must omit them.
The journal compares the complete stored binding/digest on every duplicate, query and seal; a key conflict cannot change its original request.

## Core transaction boundaries

Continue READ COMMITTED plus run-first row locking and tenant/run-qualified references from the data model.
After the run row, lock grants, budgets, intents, attempts, operations, actions and reservations in that order, sorting IDs within each class.
All core mutations, lease claims, runner clock advances and fact applications serialize per run. Unlocked discovery reads must be revalidated.
Status reads use a single coherent read transaction; reading status never expires, releases or advances an operation.
Half-open validity intervals use `validFrom <= now < expiresAt`; equality with expiry is ineligible.

1. Execute checks current authorization/APPROVED/version and records one operation plus PREPARE work, PREPARING and accepted command response atomically.
2. Prepare runs outside a core transaction. Its result is applied only after reacquiring locks and rechecking current state/bindings/authority.
3. If required, create one OPEN action bound to tenant/run/subject/attempt/operation/digest. Otherwise READY and DISPATCH work commit together.
4. Completion checks trusted session/subject and action version; approve sets VERIFIED/READY and DISPATCH work atomically. Denial/action expiry fails before admission and releases once. Authority loss takes precedence and produces CANCELLED/EXPIRED instead.
5. Admission checks READY, current lease fence, unfrozen budget, HELD full reservation, immutable binding/capabilities, grant epoch/validity and a still-valid VERIFIED action when required.
6. Commit ADMITTED and a monotonically increasing run admission sequence before dispatch. This commit orders admission against revocation and virtual-clock expiry.
7. Dispatch happens without core locks. A failure or crash after admission is UNKNOWN/HELD until authoritative same-operation evidence resolves it.
8. Revoke locks run/grant and all affected rows in canonical order, revokes once and releases/cancels only pre-admission attempts/actions. Already-admitted attempts remain exposed even if physical send has not occurred.

Action denial/expiry cancels pending dispatch work. A stale prepare result cannot resurrect a cancelled/expired/failed attempt.
A verified action that expires before admission cannot authorize admission; terminate as pre-admission FAILED and release once, with safe expiry reason.
Clock advance alone may leave an expired pre-admission state visible until its scheduled expiry handler executes; every command/admission checks current clock regardless.
Action expiry does not invalidate an earlier committed admission. Post-admission authority/action expiry cannot release exposure.
Terminal command replays return the originally accepted response only after current access authorization; clients read authoritative status afterward.

Lease ownership fences work-result and outbox acknowledgement updates, not verified financial evidence.
A stale worker cannot overwrite state or acknowledge a newer lease, but an authentic late journal fact must remain usable by reconciliation.
Use stable unique work identities per operation/kind; retry schedules update that work, never manufacture another charge.
Recovery scans durable PREPARING/READY work and ADMITTED/PENDING/UNKNOWN operations; work must survive process reconstruction.

## Independent durable journal

The adapter owns separate journal and event tables, preferably in a dedicated schema. Application core has no generic writer API for them.
Sharing a database is a development convenience and shared failure domain, not proof of production isolation.
Open a separate JDBC connection/transaction for every journal mutation. Never pass a core transaction connection into adapter code.
Journal transactions do not lock core run/grant/payment/outbox rows or rely on core rollback; core code holds no transaction while invoking the adapter.
Avoid cascading deletes from live core aggregates into journal evidence. Only explicit stopped-run teardown may remove both stores.

Journal identity is unique `(tenant,run,simulator-account,adapter-key)` and contains immutable request digest and complete correlation binding.
Serialize concurrent insertion with a database unique constraint, then lock/read the winning row; do not implement an unlocked check-then-insert.
Within that single journal transaction, either acceptance wins or a permanent NO_EFFECT seal wins. Duplicate send/seal returns the existing fact.
Digest mismatch fails closed without changing the original journal entry. Commit journal state and its append-only event together.

Minimal outcomes are ACCEPTED_PENDING, SUCCEEDED and SEALED_NO_EFFECT. A scenario may immediately succeed or seal a deterministic refusal.
For this bounded implementation, delayed accepted work later succeeds; adding a post-acceptance decline requires an explicitly final no-effect fact, never inference from timeout.
Each journal transition has a monotonic fact sequence and stable event UUID. Retrying delivery reuses that event identity and content.
Final states are immutable. SEALED_NO_EFFECT blocks every delayed duplicate send; SUCCEEDED cannot become pending or no-effect.
`query` returns current durable facts even when the core previously lost every response. `sealIfAbsent` returns pending/success if acceptance won.
No time-based journal/key expiration is allowed during the run. Restart reconstructs journal state from PostgreSQL, not process memory.
The fault runner may delay or lose delivery after journal commit; it cannot erase acceptance to simulate a network failure.

## Event authentication, conflicts and accounting

No public webhook route is introduced. The runner passes an event handle through a private adapter boundary bound to tenant/run/account.
Verification loads the immutable journal event, compares its identity/content/binding and rejects spoofed or cross-scope inputs before core inbox insertion.
Graph/UI sessions cannot invoke this port. This is local process/persistence trust, not cryptographic provider-event authentication.
Intent/attempt/operation, owner, money and request digest must match before any normal financial transition.

Core fact application locks run and affected aggregates, deduplicates event identity, appends safe evidence, advances state and changes budget/reservation in one commit.
Duplicate event ID with different content is an invariant violation, not harmless replay. Equal sequence with different fact content also conflicts.
Lower-sequence pending facts are retained/ignored without regressing final state. Contradictory final facts are not discarded merely because their sequence is lower.
Authentic contradictory facts or authenticated facts with wrong immutable binding preserve safe evidence, freeze the affected budget and flag reconciliation.
Unauthenticated spoofed facts are rejected; an attacker must not freeze a budget by submitting invented evidence.
Negative adapter-invariant fixtures must be introduced through a privileged test verifier, unavailable to HTTP callers and disabled in normal execution.

SUCCEEDED moves the full reservation HELD -> CONSUMED and adjusts both budget counters once.
SEALED_NO_EFFECT permits HELD -> RELEASED and FAILED after admission. Pre-admission failure/cancellation/expiry also releases once without journal evidence.
ADMITTED/PENDING/UNKNOWN always retain HELD. A retry deadline, session expiry, browser result, graph failure or NOT_FOUND cannot release it.
Authentic late success can resolve ADMITTED/PENDING/UNKNOWN after revocation. Never turn previously confirmed success into failure.
If contradictory evidence reveals possible exposure after a prior release, record disputed exposure and freeze; do not silently replenish or rewrite accounting.
All state/evidence changes increment appropriate versions and append safe audit events. No full payload/session/credential dumps are allowed.

## Required race and restart traces

| Interleaving | Required result |
| --- | --- |
| Revoke commits, then admission attempts | No admission/send; release pre-admission reservation once. |
| Admission commits, then revoke, then delayed send | Same admitted operation may succeed; no second operation; exposure held until evidence. |
| Admission commits, core crashes before send | Recovery queries same key; NOT_FOUND holds; atomic seal may settle no-effect. |
| Seal commits before stale send | Stale send returns the saved no-effect fact; zero synthetic charge. |
| Acceptance commits before seal | Seal returns accepted/pending/success; never releases on an invented no-effect result. |
| Journal succeeds, response lost, core restarts | UNKNOWN holds; journal query/event proves success; consume once. |
| Core commits success, acknowledgement lost | Replayed event/worker makes no second accounting change. |
| Lease expires while old worker is sending | New owner reconciles same key; stale acknowledgement rejected; authentic late fact still usable. |
| Clock reaches grant/action expiry before eligibility transaction | Transaction refuses admission even if expiry handler has not yet run. |
| Eligibility transaction commits before clock advance | Earlier committed admission remains valid; later completion is reconciled. |

Use explicit test barriers and virtual schedule steps to choose both lock/commit orders, not sleeps or probabilistic thread races.
Financial assertions inspect journal charge count, unique operation identity, reservation/budget sums and durable admission order.
Process-reconstruction tests must create new service/adapter/repository instances against the same database.
At least one test closes and reopens the underlying process or demonstrates the same persistence boundary without any in-memory state transfer.

## Evidence and acceptance

| CT case | Required bounded implementation evidence |
| --- | --- |
| CT-001/002 | Exact profile/version/capability combinations; invalid combinations reject before reservation/dispatch. |
| CT-003 | Concurrent command replay/conflict; one attempt/operation/key; stale lease; all commit-gap restarts. |
| CT-004 | Lost acceptance response, failed lookup, bare NOT_FOUND hold, seal/send races, no reroute/new identity. |
| CT-005 | Synthetic trusted subject/version/binding/expiry/one-use positive and negative actions; no graph completion. |
| CT-006 | Authenticated event resolution; spoof/cross-run refusal; duplicate/reordered/contradictory evidence; atomic accounting. |
| CT-007 | Every immutable binding mutation and fake-versus-real profile substitution rejects. Local-fake only. |
| CT-008 | Not applicable for optional voucher/split behavior; explicit split/underfunding rejection tests still required. |
| CT-009 | Both revoke/admission orders, late success, and clock races at quote/grant/action expiry. |
| CT-010 | Synthetic canaries in credential/action/session paths absent from HTTP errors/status, graph/checkpoint, logs and reports. |
| CT-011 | Repeat pinned seed/config/initial clock/ordered schedule with normalized IDs and equal state/journal/invariant digest; run isolation. |
| CT-012 | Machine-readable evidence preserves failures, missing evidence, optional exclusions and exact profile; no external certification assertion. |

The implementation must supply exact runnable test commands, actual output and reviewed source identity. This ADR reports no executed application tests.
Documentation checks are necessary but cannot establish transaction/recovery behavior. A test absent from evidence remains incomplete.
Python/TypeScript clients require their own bounded integration tests and may only call the authorized internal API; they are not payment truth.
Real sandbox connectors remain isolated behind separate opt-in profiles and G2-P review with pinned upstream contracts and provider-specific evidence.

## Alternatives and consequences

An in-memory journal cannot prove recovery across process loss; a journal update inside the core transaction cannot model acceptance/core-commit gaps.
A public fake webhook with caller-asserted verification would remove the event trust boundary; a private journal-backed verifier is sufficient locally.
Global per-run serialization limits throughput but gives an explicit deterministic ordering. Real managed-service concurrency needs a separate review.
Atomic sealing is stronger than many providers offer; a future adapter without it must leave absence unresolved and held.
One full-charge operation keeps the slice reviewable while port/capability separation preserves future extensibility. Split tender remains separate design work.
