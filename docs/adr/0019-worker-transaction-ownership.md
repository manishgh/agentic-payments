# ADR 0019: Worker transaction ownership and stale-result fencing

## Status

Accepted for the bounded synthetic reference profiles. Timed leases and provider-journal admission integration remain separate work.

## Context

The command transaction boundary separates approval and execution policy from SQL. Worker preparation, dispatch admission and reconciliation orchestration still live in `JdbcPaymentStore`. Its outbox counters fence preparation completion, but an old absent/unavailable lookup can overwrite a newer PENDING projection with UNKNOWN. A counter cannot revoke an already admitted financial sender; authentic late evidence must remain admissible.

The existing worker has no timed lease. Repeated claims can repeat effect-free preparation and read-only reconciliation. Dispatch is different: one transaction records admission, permanently completes DISPATCH and publishes RECONCILE before returning the original operation for its one send. A crash after that commit sacrifices automatic send recovery; recovery consults the original identity instead of sending again.

## Decision

Add application-owned `RunPaymentWork` and the versioned responsibility contract `PaymentWorkerTransactions` (`payment-worker-transactions-1`). Keep public service/store methods, composition constructors, worker receipts, the legacy `PaymentAdapter` and all persisted representations compatible. Do not connect `provider-execution-1`, add timed leases, migrations, dependencies or external providers.

`RunPaymentWork` owns runner authorization, the existing scheduled-worker capability check, selection/control decisions, current authority/action checks, provider orchestration and uncertainty policy. The transaction adapter owns run locking, replay-owner checks, virtual time, SQL mapping, narrow atomic writes and retries. Existing expiry sweep, native transaction integration, verified-fact ledger ingestion/settlement and receipt calculation remain explicit infrastructure phases; this is not complete transaction-layer extraction.

### Typed phases

```text
RunPaymentWork(transactions, adapter, verifier, progression)
  runNext(Session) -> boolean
  runNextRecorded(Session) -> WorkerReceipt
  reconcile(Session, IntentId, sealIfAbsent) -> void

PaymentWorkerTransactions.run(Session, Work<T>) -> T
Work<T>.run(Transaction) -> T
Transaction.now() -> Instant
Transaction.activity() -> Activity
Transaction.expireEligible() -> void
Transaction.next() -> Optional<WorkItem>
Transaction.load(OperationId) -> Snapshot
Transaction.findAdmitted(IntentId) -> Snapshot
Transaction.claim(WorkItem) -> Claim
Transaction.reconciliationClaim(Snapshot) -> Claim
Transaction.isCurrent(Claim) -> boolean
Transaction.nativeGate(Snapshot) -> boolean
Transaction.terminateBeforeAdmission(Snapshot, Termination) -> void
Transaction.completeObsolete(WorkItem) -> void
Transaction.admit(Claim, Snapshot) -> void
Transaction.completePreparation(Claim, Snapshot, ActionType) -> void
Transaction.markUnknown(Claim, Snapshot) -> void
Transaction.applyVerifiedFact(ChargeOperation, Fact) -> void
Transaction.receipt(Activity, Selection) -> WorkerReceipt

PaymentFactVerifier.verify(ChargeOperation, Fact) -> Fact
SyntheticPendingProgression.progress(ChargeOperation, Fact) -> Fact
```

`WorkKind` is PREPARE, DISPATCH or RECONCILE; `Termination` is CANCELLED, EXPIRED or FAILED. `WorkItem` and `Claim` bind operation, kind and counter. `Activity` holds the four existing audit/work/evidence/conflict counters; `Selection` holds the selected operation, kind and counter, with an explicit idle representation. `Snapshot` contains the immutable charge operation, current intent profile/state, attempt grant version/subject, checkout digest, current grant state/version/subject/merchant/validity, reservation state, frozen-budget flag, admission marker and optional action state/subject/checkout digest/expiry. It carries no SQL maps, connections, native proof values, credentials or serialized artifacts. Diagnostic string forms are redacted.

Callbacks are synchronous and may be retried only according to the existing SQL class-40 rules. They contain no prepare, execute, lookup, seal, event-verification or simulator-progression calls. A committed return is required before any provider call. Scope operations reject retained and cross-thread use; command, approval and worker views on the same owner share one nesting guard. Direct implementations, foreign-backend adapters and forwarding wrappers implement the same responsibility and preserve these guarantees.

Writes bind to the exact facts loaded in the current callback and their original scoped operation. Native gates accept only the current loaded snapshot and successful verification is retained inside the scope, never accepted as a caller-constructed proof. Admission requires the current claimed DISPATCH, READY and HELD, and successful native gating when required; its admission sequence, timestamp, grant version, ADMITTED state/audit, DISPATCH completion and RECONCILE publication commit together. Preparation completion requires a current unfinished PREPARE, fresh PREPARING snapshot and successful native gating when required. An earlier detached snapshot is never reused for completion policy.

### Selection and provider ordering

Scheduled work preserves activity snapshot, expiry sweep, priority (PREPARE, DISPATCH, RECONCILE), enqueue order and terminal-work handling. A terminal selected item is completed without incrementing its counter. Other selected work increments its counter under the run lock. Native PREPARE gating retains its position after increment and before preparation. DISPATCH retains READY, frozen-budget, grant eligibility, native gate, trusted-action binding/expiry and HELD checks in that order before admission.

After commit PREPARE calls effect-free preparation and re-enters a fresh transaction for current-claim/state and authority/native checks. The existing challenge mismatch fails before creating action or dispatch work. Action creation/readiness, PREPARE completion and any DISPATCH publication commit together.

DISPATCH calls execute once, only after its committed admission. RECONCILE calls lookup only. The exact simulator's scheduled PENDING result retains its explicit local progression; manual reconciliation does not progress it. All found/returned facts pass the injected verifier before the unchanged atomic ledger ingestion. The concrete simulator check remains infrastructure composition, not application policy. Non-simulator legacy adapters retain lookup-and-equality verification and no synthetic progression. Direct, adapted and decorated collaborators can supply the same responsibilities without application changes.

`RunPaymentWork` publicly accepts a complete transaction owner and the three provider responsibilities. `PaymentServices.local` additionally exposes verifier/progression injection alongside the existing adapter and composition ports; the full `JdbcPaymentStore` constructor exposes the same injection for the external profile. Existing constructors select their historical behavior. Verification must return the exact delivered fact; returning a different verified fact never substitutes evidence silently. These are trusted host registrations, not request-selected implementations.

Preparation unavailability still propagates and cannot mark financial uncertainty. Execute and reconciliation retain their existing `AdapterUnavailableException` handling; unrelated runtime failures are not relabeled. Manual reconciliation retains runner authorization and its existing lack of a capability check. Optional sealing is still only after NOT_FOUND and remains the provider's atomic acceptance-versus-seal responsibility.

### Stale results and recovery

An uncertainty observation is not financial evidence. A scheduled or manual RECONCILE uncertainty write requires its original unfinished counter still to be current. Manual reconciliation now increments the existing RECONCILE counter under the same lock before lookup, deliberately superseding an older observation. It never revives completed work or changes operation identity. On completed work it retains a noncurrent observation token so authentic terminal/conflicting facts may still be checked and retained while uncertainty cannot mutate state.

DISPATCH uncertainty requires the original admitted operation and its matching completed DISPATCH counter. A delayed admitted sender remains entitled to its original call even if reconciliation has advanced: a core counter is not a no-future-send fence. Both uncertainty paths can only change ADMITTED to UNKNOWN; existing UNKNOWN and PENDING are preserved, as are all terminal states. This deliberate monotonicity correction prevents absence or transport loss from erasing known acceptance. The original RECONCILE work remains available for unresolved exposure.

Authentic late facts are ingested regardless of stale counters. Existing identity/money binding, inbox deduplication, sequence/finality rules, conflicting-evidence retention, budget freeze and reservation settlement remain authoritative. Do not discard a late success or conflict because another worker claimed first. Never derive no effect from timeout, process death, a claim counter, lookup absence or lost commit acknowledgement. Only the configured provider's genuine atomic seal can exclude a stale admitted send.

The worker receipt retains selected operation/kind/counter, committed audit range and progress based on the original four counters. Counter-only work does not imply progress; expiry may progress an idle call; `runNext` remains selected-operation-or-progress. Receipt activity may include concurrent committed work exactly as before; it is not a per-invocation audit ownership claim.

## Verification

Independent application tests exercise direct, differently shaped adapted and ordered delegating transaction implementations; provider effects observe completed transaction callbacks. Cover selection/receipt behavior, preparation/action and authority decisions, failure precedence, no send on rejected admission, stale completion/uncertainty, and admissibility of late facts. Separate transaction-scope tests cover after-return/thread/nesting misuse and attempts to use forged or prior-scope values.

Real PostgreSQL tests use deterministic barriers and independent connections to prove same-state PREPARE supersession, stale missing/unavailable observations after newer PENDING/terminal facts, atomic admission rollback, lost admission acknowledgement without send, and command-to-worker/worker-to-command nesting rejection. Preserve existing revoke/admission, seal/stale-send, conflict/freeze, UNKNOWN recovery and abrupt-JVM journal/core commit-gap evidence. Run the documented complete conformance suite, documentation check and diff check. No test result implies production operation, general provider compatibility or certification.

## Consequences and alternatives

The application gains independently executable worker control policy and replaceable typed transaction/provider collaborators without weakening the financial owner. JDBC still integrates several named transaction phases. Moving the entire worker behind an opaque new method would not extract policy; splitting admission, outbox and state into separate commits would break ownership.

Timed leases, renewal, wall-clock takeover and provider-journal integration require separate designs. They introduce schema/clock/liveness choices and must never authorize replacement financial submission. The counter model and its limitations are explicit for this bounded slice.
