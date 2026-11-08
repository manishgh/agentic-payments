# ADR 0013: Durable synthetic provider execution journal

Status: proposed for independent design review, 2026-11-08. Design only until the integration owner records the next implementation gate. Existing aggregate source, accepted specifications and reviews remain frozen.

## Context and bounded decision

[ADR 0012](0012-provider-execution-contract.md) and [provider-execution-1](../../specification/provider-execution-1/README.md) introduced an additive provider-neutral contract, compatibility adapter and synthetic behavioral suite. The compatibility adapter deliberately requires an injected atomic `CompatibilityBindingJournal`; its retained-memory fixtures prove only in-process reconstruction. This decision supplies one real PostgreSQL implementation and independently observed process-crash evidence, using the existing durable synthetic `JdbcSimulatorAdapter` as the execution peer.

Implement `JdbcCompatibilityBindingJournal`, a strict versioned persistence codec and additive V009 tables. Keep the existing SDK responsibility interface unchanged. Do not wire these classes to `JdbcPaymentStore`, financial admission, checkout execution, HTTP endpoints or the current simulator composition. Do not activate a vendor connector, sandbox account or live-money path. PostgreSQL is a replaceable implementation of the existing journal responsibility, not a new mandatory SDK backend. The SDK classifier must retain its existing `java.base` dependency boundary.

The backend preserves the full operation and immutable legacy mapping, grants at most one initial dispatch owner, retains all evidence/conflicting facts and exposes an explicitly conservative projection. Each method commits its own short transaction before returning. No provider call, credential resolution or external callback occurs within a journal transaction. This sacrifices automatic resubmission after an uncertain claim in order to preserve the original single-execution guarantee.

## Existing interface assessment

`bind`, `claim`, `record` and `read` are sufficient for this bounded implementation. No claim expiry, replacement owner, mutable configuration lookup or new SDK method is required. The following behavioral refinements are necessary and will be documented/tested by this implementation without changing the accepted SDK signatures:

1. `record` must check full same-ID content and durably retain conflicting evidence before returning `EVIDENCE_CONFLICT`. It must not throw inside a transaction that then rolls the conflict record back.
2. Any prior evidence, including unknown reconciliation, prevents a later fresh owner. An unknown lookup does not prove a submission occurred, but this interface cannot prove that legacy acceptance/history is absent; conservative blocking deliberately sacrifices liveness.
3. After a persisted evidence conflict, `read`, `claim`, `record` and binding replays must surface the conflict rather than return an old terminal fact as clean truth.
4. Durable storage cannot persist an error while that storage is unavailable. This slice promises retained claim/uncertainty state and durable conflict/failure decisions when PostgreSQL can commit, not an impossible guarantee that every outage exception is written to the failed database.

The old memory fixture's state-only terminal comparison is not evidence for these stronger durable cases. New independent SQL tests, including same-ID nonterminal conflicts, are required. Any discovered need to change a published signature/profile is a separate reviewed decision; do not silently widen this slice.

## Trusted construction, identity and exact version retention

Construct each journal instance with a `DataSource` and immutable trusted configuration: tenant, merchant and run scope; exact execution binding/offer; local mapping-policy revision; scenario-policy reference/version. The journal verifies trusted scope before every operation, including direct `read`/`record` use. Bridge validation alone is insufficient if another trusted component reuses the journal directly. No scope/account/configuration comes from an unauthenticated request body.

Reference composition requires a fresh dedicated synthetic execution scope, exclusive use of its legacy keys by this bridge and durable no-deletion retention of compatibility history. It must not adopt a preexisting legacy operation, reconstruct a lost compatibility history from lookup, or start against an independently restored/empty compatibility store while the peer may retain old acceptance. Restore/recovery must preserve the complete compatible histories or remain blocked for operator review. These are explicit scope/ownership premises, not properties inferred from current lookup absence. The existing simulator's indefinite deduplication is not generalized into a PSP-retention guarantee.

The primary execution namespace is exactly `(tenant_id, merchant_id, run_id, caller_key)`. `run_id` is the SDK's bounded text token, not a UUID. Provider/account/region/configuration/credential fields remain immutable values outside the namespace. There is no foreign key to core payment intents, attempts, reservations or `simulation_runs`. The old simulator journal separately uses the complete mapped legacy scope/run UUID/key. This allows standalone provider-journal evidence with zero core financial rows.

Within an authenticated scope, an `operation_id` may have one caller-key binding. Reusing an operation ID under another caller key is an identity conflict, not a route to a second owner. Different operation IDs/keys may progress independently; the journal does not supply spending authority or decide whether a caller is entitled to admit another payment attempt. Legitimately separate trusted scopes may use the same caller key and operation ID. A complete legacy `(tenant, run UUID, adapter key)` may belong to only one new execution namespace across journal instances, preventing legacy aliases from bypassing full binding.

`bind` compares the entire decoded `ExecutionOperation`, exact fingerprint and complete `ChargeOperation`: old identity/scope/UUIDs/request digest, exact old money and selected scenario. It never compares only hashes. A trusted mapping function supplied by reference composition must independently derive the expected legacy candidate for the exact pinned mapping/scenario policy and reject candidate disagreement before delegation. This function is constructor-bound policy, never a per-request candidate override. It is a pure compatibility responsibility, not a vendor port or provider call.

Pin reference mapping revision `local-fake-uuid-map-1` to the existing compatibility bridge's tagged deterministic UUID derivation and scenario selection. The infrastructure composition can reproduce that pure mapping and test it against the bridge without editing the frozen SDK. Persist the chosen complete legacy mapping and policy revisions. On reconstruction, validate against the original retained policy/configuration; do not recalculate using current defaults and overwrite the old mapping. Missing original policy/configuration/credential version returns a fixed safe unavailable result and leaves claims/evidence intact. No credentials or secret material are stored in these tables.

Observation time and synthetic action expiry supplied to a bridge are immutable reconstruction configuration. Reconstructing with a different observation instant produces distinct evidence IDs under the bridge's current mapping and must not be called an exact replay. Process tests retain the original instants/scenario/configuration and compare every field. Administrative credential rotation does not rewrite existing bindings; an old pinned resolver must remain available for outstanding work or recovery stays explicitly blocked.

## V009 storage model

Proposed migration: `src/main/resources/db/V009__provider_compatibility_journal.sql`. Tables are additive, separate from core truth and the synthetic provider peer:

| Table | Purpose and principal constraints |
| --- | --- |
| `provider_compatibility_bindings` | One row per execution namespace; unique scoped operation ID and globally unique complete legacy scope/key tuple. Stores codec revision, complete operation/legacy payloads, both fingerprints, immutable mapping/scenario policy revisions, permanent dispatch-claimed flag, submission fingerprint and exact absent/present resource association, irreversible dispatch-blocked flag, nullable projection-evidence reference, conflict marker and audit timestamps. |
| `provider_compatibility_evidence` | Append-only accepted facts keyed by namespace/evidence ID, complete versioned payload and content digest, explicit state/source and exact instant text. Same-ID duplicates compare the complete decoded fact. Foreign key to the binding. |
| `provider_compatibility_conflicts` | Append-only protected conflict receipts keyed by receipt UUID, with required requested-namespace fields, optional complete existing-owner namespace foreign key, safe conflict kind/code/certainty, existing/candidate content digests and complete safe conflicting candidate evidence when applicable. Requested namespace deliberately has no binding foreign key: a rejected operation-ID/legacy alias may have no row there. Existing-owner columns are all null or all present. Candidate fact cannot overwrite the original evidence-ID row. Binding conflict receipts retain no raw exception/provider payload. |
| `provider_compatibility_resource_owners` | First known original provider-resource ownership by provider/account/region/resource reference, linked to one execution namespace. Prevents the same original resource from being attached to another operation. No provider resource is inferred from current absence. |

Use bounded text identifiers and `sha256:` checks consistent with the SDK, bounded text payloads (operation at most 64 KiB; legacy at most 8 KiB; evidence at most 128 KiB), explicit nullable-field checks and foreign keys. Submission presence must agree with nullable resource reference/fingerprint. Claims cannot be reset by any method. Choose a restricted SQL update path: immutable identity/payload/policy fields are insert-only, submission association is written once under its locked row, and later updates name only projection/irreversible claim/block/conflict fields. Replays compare complete original values; independent port-level tests must exercise rejected changes and unchanged stored columns. This is not an arbitrary database-administrator tamper guarantee. No cascade deletion or expiration of binding/evidence/claims is provided. Operational retention/purge needs a separate reviewed horizon and must never fall back to provider-key retention.

V009 uses simple declarative SQL supported by the current semicolon-splitting migrator; do not introduce procedural trigger/function bodies or a migration parser rewrite in this slice. Existing migration checksums remain unchanged.

Evidence instants retain exact `Instant` text/nanoseconds. PostgreSQL `timestamptz` audit columns are operational timestamps only and cannot round/replace evidence time. Store full binding information as protected data; logs/status/test output expose only safe markers/counts/digests.

The compatibility profile is effect-free preparation, so its `SubmissionRequest` resource association is explicitly absent. Persist and compare that absence. Do not claim this backend proves future resource-creating preparation admission. `provider_compatibility_resource_owners` protects the separate provider-resource reference in evidence. A future generic preparation journal needs its own reviewed create/uncertainty/provenance contract; adding a non-null prepared resource to this compatibility profile must fail before activity.

## Transaction algorithms and concurrency

Use normal PostgreSQL transactions and row-level locking for one operation; do not take a whole simulation-run lock. Scope validation and bounded codec parsing happen before SQL writes. Row insertion uses uniqueness constraints as the serialization boundary. `INSERT ... ON CONFLICT DO NOTHING`, followed by lookup/lock of the requested namespace, distinguishes exact replay from a conflicting operation-ID/legacy alias that belongs to another namespace. Unique-index contention must be exercised with independent real connections.

When insertion is suppressed and the requested namespace is absent, explicitly look up the immutable conflicting operation-ID/legacy owner and commit a rejection receipt with its optional existing-owner association and the unbound requested namespace. Do not invent a requested binding solely to satisfy a receipt foreign key. Expected uniqueness decisions use `ON CONFLICT DO NOTHING` (or a reviewed savepoint), so PostgreSQL is not left in an aborted transaction before the receipt write. An unexpected SQL error rolls back and returns safe unavailability; do not attempt receipt writes in an aborted transaction or claim they committed.

### Bind

Validate operation/selection against trusted original configuration and validate the proposed legacy candidate against trusted mapping policy. Insert the complete immutable row if absent. Otherwise lock it and compare every field/policy revision. Same namespace changed binding, money, purpose, selection, legacy identity/scenario or policy is `IDENTITY_CONFLICT`. An operation-ID or legacy-key alias under a different namespace is also a conflict. Publish a safe conflict receipt and commit before throwing; a rejected identity mutation does not quarantine an otherwise valid existing operation merely because a caller proposed a different request. Existing evidence quarantine, however, always remains in force.

### Claim

Lock the bound row; reject missing/mismatched binding and any existing evidence conflict. Atomically bind the entire `SubmissionRequest`/fingerprint, including absence/presence, before deciding ownership. First claim sets `dispatch_claimed=true` only if it was false, no evidence has ever been recorded and dispatch is not blocked/conflicted. Commit before returning `firstDispatchOwner=true`. Any prior evidence sets the irreversible block even when no dispatch claim exists; later unknown observations cannot erase that history. All other calls return `firstDispatchOwner=false` and known projection if present. No timeout, dead worker, provider absence, expired PSP key, database restart or operator retry resets the flag.

Consequently a fresh bind followed directly by first claim can dispatch once, while a pre-submit reconcile that records `UNKNOWN` closes initial dispatch and may remain unresolved forever without a genuine fence/new separately admitted attempt. `dispatch_claimed` and `dispatch_blocked` are distinct: blocking on unknown does not claim proof that an earlier owner existed. The current interface has no independently proven fresh-source marker that would safely permit an exception.

If the transaction commits but its acknowledgment is lost, the caller cannot assume ownership. A second connection sees the durable claim and gets no new owner. A crash after the claim and before provider I/O deliberately leaves unresolved liveness; reconciliation/optional genuine provider fence may resolve the original operation. It does not justify a resend.

### Record and read

Lock the operation row and validate full operation binding. A fact for an unbound operation fails; the journal must not invent a mapping from an evidence value. For an already accepted evidence ID, an exact full duplicate is idempotent; any changed state/source/time/resource/action/operation content is a durable evidence conflict, even for nonterminal facts.

For a new ID, append the complete fact before applying these conservative rules:

| Existing projection / incoming fact | Projection and dispatch effect |
| --- | --- |
| No projection, `UNKNOWN` from any source | Retain unknown observation and irreversibly block first ownership without asserting that an owner/acceptance existed. Never reset an existing claim. |
| `PENDING`, including before claim | Retain accepted processing evidence and block a fresh owner. Later unknown cannot remove this acceptance history. |
| Existing pending, incoming unknown or another compatible pending | Append it; preserve the first pending projection and irreversible block. |
| `CAPTURED` after unknown/pending | Retain the first capture as the terminal replay projection and block fresh dispatch. |
| `NO_EFFECT_FENCED` after no fact/unknown only | Retain the first genuine fence as terminal replay projection and block fresh dispatch. It must come from the configured atomic peer guarantee. |
| Existing capture, incoming unknown/pending | Append late/weaker evidence; keep original capture and permanent claim/block. |
| Existing fence, incoming unknown | Append uncertainty; keep original fence and permanent claim/block. |
| Existing terminal, different-ID same terminal state with compatible original resource | Append it; replay original terminal projection. Do not substitute a newer timestamp/source as financial authority. |
| Pending/captured versus fenced, in either order | In this synthetic profile, processing acceptance excludes a winning no-effect seal. Append conflict evidence, quarantine, retain the original projection only for protected investigation and commit before throwing. |
| Any other terminal contradiction | Retain both facts and quarantine; no clean projection or owner is returned. |
| Changed known original provider-resource reference or incompatible action-reference replacement | Retain candidate conflict, mark conflicted and commit before throwing. An absent reference cannot erase a previously bound reference. |

The concrete journal profile accepts financial states `UNKNOWN`, `PENDING`, `CAPTURED` and `NO_EFFECT_FENCED`, matching the current bridge. `AUTHORIZED`, `DEFINITIVELY_REFUSED` and financial `ACTION_REQUIRED` remain general codec/contract values, but are unsupported observations for this bounded profile: retain a protected violation receipt, quarantine and fail safely rather than invent their financial mapping. The charge-only profile rejects authorization bindings before delegation and never interprets authorization as capture. Current bridge preparation actions remain outside financial evidence. Future provider profiles require their own reviewed transition/conflict matrix; this decision does not declare a universal pending/action/refusal ordering.

Once an evidence conflict is marked, subsequent binding replay, `claim`, `read` and `record` return fixed safe `EVIDENCE_CONFLICT`; further distinct facts may be retained as protected receipts, but no clean financial fact or new owner is returned. Retain both sides and identity/exposure. No automatic conflict clearing is supplied. A changed action reference without a profile-defined ordering is not silently treated as renewal. Future richer provider profiles need reviewed state normalization, rather than guessing from observation times.

Provider-resource ownership is first established by a nonempty verified trusted-port reference and cannot transfer to another operation. Unknown absence is not ownership release. The journal is a trusted internal component, not an event authenticator: callers must supply facts through the configured verifier/normalizer/adapter composition; constructing a Java DTO is not provenance proof.

A resource-ownership insertion likewise uses a non-aborting uniqueness decision and explicitly reads the existing owner. If another operation owns that reference, retain the candidate receipt and quarantine the current operation in the same transaction. Keep the original owner row and original terminal projection unchanged; conflict retention is never an ownership transfer or projection overwrite. Terminal mismatch and ownership conflict responses are raised only after their receipt/quarantine commit.

Internally return a success-or-safe-conflict decision from the transaction body, commit, then raise `ExecutionFailure` outside the rollback handler. An exact retry after a lost conflict response must still see the committed receipt/quarantine. Ordinary SQL failure rolls back the current transaction and returns fixed `CONFIGURATION_UNAVAILABLE` with conservative certainty; it never clears an earlier committed claim/evidence. No raw SQL exception/cause enters results or receipts.

## Strict versioned codec and failure persistence

Use the existing Jackson dependency only in infrastructure, with an explicit whitelisted `provider-compatibility-json-1` DTO/tree codec. Do not rely on automatic serialization of SDK `Optional`/`Instant` records or add Jdk8/JavaTime modules. Encode absent optionals as explicit nulls and instants as canonical ISO text; encode all enum/collection/legacy fields explicitly and deterministically. Required fields are always present, including null-valued optionals.

Decode with duplicate-field detection, exact field whitelists at every nesting level, required-field/type checks, collection/byte bounds, canonical integer handling and strict enum/version parsing. Reconstruct SDK values through validating constructors, recompute operation/submission fingerprints and compare complete selected offer/binding against trusted original configuration. Verify redundant stored digest/identity columns against the decoded values. Unknown codec revision, truncated/corrupt data, changed fingerprints, unknown fields and duplicate fields fail closed; never default a missing claim to unclaimed or a missing fact to no effect. Round-trip exact nanos, empty optionals, sets, limits, all representable states and redacted diagnostics.

Durable safe failure evidence consists of unknown financial observations and committed conflict receipts when storage is available. A bridge-sanitized provider timeout is retained as `UNKNOWN`; the journal does not recover or store the discarded raw provider error. Database unavailability cannot guarantee an additional durable failure row. Tests instead prove that previously committed bindings/claims survive failed writes and prevent resubmission after recovery. Decode corruption may retain a fixed protected failure receipt when the namespace is safely known; it must not repair or promote the corrupt row automatically.

## Genuine no-effect fencing and separated commit gaps

The compatibility journal does not itself implement `NoEffectFencer` and its claim/block flag does not fence a delayed worker. The existing `JdbcSimulatorAdapter` owns the atomic acceptance-versus-seal journal and its durable `NO_EFFECT` guarantee. It uses a separate committed transaction. `LocalFakeExecutionAdapter.fence` invokes that guarantee, then records the fact in the compatibility journal.

If the provider seal commits and the process dies before compatibility recording, recovery must look up the same original legacy operation and record the existing no-effect fact. If acceptance commits first, fencing returns existing acceptance and must not create a no-effect projection. If compatibility recording fails after provider acceptance/seal, the permanent claim/mapping and peer journal survive separately; reconciliation repairs the missing observation. A resumed original worker may invoke the legacy adapter and receive its already committed fact. Evidence must distinguish a Java adapter invocation from a new financial send/acceptance; do not claim that no Java call occurred.

No two-connection atomic transaction is assumed between compatibility storage and provider storage, even when both happen to share one PostgreSQL server in this reference. Both commit gaps require actual crash tests. Lookup absence remains unknown, including after claimed-before-I/O process death.

## Independent validation and actual child processes

Use real isolated PostgreSQL scopes and independent SQL inspection, not an embedded database. The existing `JdbcSimulatorAdapter` and `simulator_journal_events` supply a persistent synthetic peer; retained-memory `CompatibilityFixture.LegacyPeer` cannot prove its acceptance survives JVM death. No production crash hooks are added. Test-only wrappers/DataSource commit proxies halt a child JVM at committed/uncommitted boundaries; the parent checks witnesses only after child termination. Recovery uses a separate fresh JVM and newly constructed `DataSource`, journal, bridge and synthetic adapter.

| Case group | Required independent evidence |
| --- | --- |
| Migration | Populate V008 with native/checkout/provider-peer data; apply V009 twice; preserve every earlier row/checksum and historical migration-prefix tests; new tables empty initially. |
| Full binding | Mutate each identity/binding/money/selection/policy/legacy/scenario field under an existing key; reject before provider activity. Same operation/different key and same legacy mapping/different namespace fail. Separate trusted scopes may reuse key text; direct wrong-scope journal calls fail. |
| Claims/concurrency | Independent connections contend for identical requests: one owner; changed requests/aliases conflict. Unrelated operations/scopes proceed while one row is locked. Commit-ack loss and rollback are explicitly distinguished. |
| Evidence | Exact duplicates including nanos/source/action replay; changed same-ID nonterminal/terminal payload conflicts persist; contradictory terminal/resource/action facts quarantine; late weaker observations retain terminal projection; conflict survives another JVM. |
| Before-claim facts | Fresh bind with no prior evidence grants one first owner. Any prior unknown/pending/capture/fence blocks it; pending then unknown still blocks. Unsupported action/authorization/refusal observations quarantine. No recorded fact resets an existing claim or permits adoption of lost legacy history. |
| Version/codec/privacy | Historical exact config/scenario/credential binding survives reconstruction/rotation; missing old policy fails closed. Unknown codec/fields, duplicate keys, wrong types, overlong payloads and fingerprint corruption refuse safely. Synthetic canaries never enter child output, exceptions or safe reports. |
| Failure persistence | Inject failure before record commit: projection/evidence write rolls back while prior claim/provider acceptance remain; fresh reconciliation records the original result. Conflict receipt commits before throw and survives lost response. |

Actual process matrix:

1. Halt before bind commit: no new binding/claim/provider acceptance exists. An exact fresh operation may bind/claim normally.
2. Halt after bind commit, before claim: complete mapping/configuration is durable and unclaimed; fresh process claims it once with the identical legacy key/scenario.
3. Halt after claim commit, before delegate I/O: full submission and permanent claim survive; fresh submit/reconcile remain unknown with no provider acceptance and no replacement execute. An explicit genuine provider fence may resolve the same operation.
4. Halt after `JdbcSimulatorAdapter.execute` commits but before compatibility `record`: independent peer SQL shows one accepted operation/event; compatibility claim exists and terminal evidence is absent. Fresh process reconciles the same complete mapping to capture and never adds another acceptance.
5. Halt after compatibility terminal record commit but before caller response: fresh identical submit replays the original full terminal fact with zero new delegate execution/acceptance. Changing the request still conflicts.
6. Repeat accepted-response-loss recovery after explicitly clearing only a test transport's finite idempotency cache and forcing lookup absent/unavailable: claim remains, no resend occurs. Do not delete the durable synthetic peer journal and then imply its financial acceptance survived. A separate test-only finite cache models retention; the durable peer acceptance ledger remains intact and independently inspected.
7. Halt after provider seal commit before compatibility record, and after provider acceptance commit before fence record: separate processes reconstruct the original no-effect or accepted fact without substituting current absence; delayed original workers cannot create another acceptance.
8. Persist conflicting terminal evidence, lose the conflict response, then reopen in another JVM: both fact receipts/quarantine remain, all claim/read paths fail safely and no dispatch owner is issued.

Test child arguments contain only nonsecret mode/scope/operation IDs. Database credentials are inherited environment only; no command-line credential, connection-string logging or raw exception output. Crash helpers halt with a fixed expected code; recovery emits only a fixed safe success marker. Bound waits and forcibly terminate only the test-owned child if it fails its deadline. No whole PostgreSQL server crash or storage-device power-loss claim is made; successful committed transactions rely on the documented database durability configuration.

The reusable SDK TCK may run against a JDBC-backed in-process fixture, but its `restart()` only reconstructing Java objects must remain labelled `IN_PROCESS_RECONSTRUCTION`. Separate named child-JVM tests provide the actual process evidence. Do not label the entire suite `PROCESS_PERSISTENCE` unless every claimed case actually traverses that boundary. No IPC TCK redesign is required for this slice.

## Ownership and gate sequence

1. Design author edits this new ADR only while the previous aggregate/source is frozen. Independent Astra review and integration-owner acceptance freeze its exact hash before source changes.
2. Implementation author owns new `src/main/java/io/agenticpayments/infrastructure/provider/**` for journal, strict codec and immutable trusted mapping/configuration helpers; V009; and new `src/test/java/io/agenticpayments/provider/ProviderJournalCodecTest.java` only. Independent test author owns other new `src/test/java/io/agenticpayments/provider/**` integration/process/inspection helpers and the populated-V008-to-V009 migration test. Existing SDK/specification/financial storage/checkout code is outside this ownership. Do not amend accepted earlier ADR/review files.
3. Root owns `DatabaseMigrator` registration, existing `NativeMigrationUpgradeTest`/`CheckoutMigrationUpgradeTest` latest-version count amendments and aggregate/evidence/docs linkage. Preserve existing V007/V008 prefix/checksum assertions; increasing a latest migration count is not permission to rewrite old migrations. Root owns Maven, classifier checks and the full unchanged-source aggregate. Immediately after the gate, implementation author publishes exact constructor/configuration/codec signatures for parallel independent tests before editing those responsibilities.
4. Implement codec/journal/migration as one bounded slice, then independent SQL/concurrency/process tests. Review exact source/codec/schema/failure semantics before root aggregate and incremental publication. No package release, merge, deployment or real provider activation follows from a source push.

Open operational work remains production retention/key custody, richer provider profiles, durable preparation resources, authenticated external events, application financial wiring and process/storage failure coverage beyond this bounded synthetic profile. These are not prerequisites for implementing and testing the journal itself.
