# ADR 0003: Deterministic replay and reusable adapter conformance

Status: accepted for the bounded reference profile described below.
Scope: local simulation and reusable SDK metadata/test contracts. Existing HTTP schemas, live connectivity and G2-P are unchanged.

## Context and decision

The current core has durable identities, virtual time, run serialization and selected recovery tests, but no whole-run replay contract.
Multiple pending operations are selected using random UUID order, so the same seed or scenario names alone cannot reproduce a run.
Its `CapabilityOffer` and `PaymentAdapter` are deliberately local-only; treating those as a general provider API would require every provider to promise synthetic scenarios and atomic sealing.

Add three bounded components: a recorded deterministic runner, a versioned generic descriptor registry, and a reusable adapter test compatibility kit (TCK).
Use named logical entities, explicit ordered actions, stable work enqueue order, raw invariant checks and a versioned normalized evidence digest.
Keep provider metadata separate from executable registration. The HTTP runtime continues to compose only the explicit local simulator and enforce its complete required policy.
Descriptors can describe additional providers/methods/credentials/actions without enabling network execution or asserting real interoperability.
The TCK runs through trusted test harnesses; its result identifies tested capability/profile subsets, never an unqualified certification.

## Recorded plan and run identity

`ReplayPlan` is an immutable closed document with `formatVersion=simulator-plan-0.1`, extension/profile versions, runner version,
core/adapter artifact identities, fixture/catalog digest, logical run name, seed, initial UTC clock, exact safe configuration and expanded ordered steps.
The configuration names budget limits/currencies, trusted fixture actor aliases, available synthetic scenarios and a descriptor/policy digest.
It contains no bearer token, password, credential reference, arbitrary code, network endpoint or private authority payload.
Use the existing fixture catalog to resolve trusted actor and credential aliases inside the harness; report only the catalog identity.
Reject unknown required fields, duplicate labels/ordinals, unsupported versions/profiles, nonmonotonic times and unknown fixture references before seeding.

Store the seed as a canonical signed 64-bit decimal string. Seeded generation is optional; execution always consumes the fully expanded schedule.
For reproducible named choices use `SHA-256(UTF-8("plan-choice-0.1" + NUL + seed + NUL + ordinal + NUL + choiceName))`;
interpret its first eight bytes as unsigned big-endian and reduce modulo the versioned candidate-list length.
All generated choices are written into the expanded plan before execution. No runtime random draw may select faults or worker order.
Pin golden choice vectors, canonical plan bytes and their digest. Record generator version separately; the expanded plan is authoritative.

Each execution receives a fresh physical `(tenant,runId)` and isolated grants/budgets. Replays must never reuse or reset an unresolved original run.
Actual UUIDs may remain randomly allocated: their allocation order is not scheduling, and the normalizer maps them through semantic entity aliases.
Record plan digest, initial configuration digest and initial clock before executing the first step; do not edit them during a run.
Record outcomes as PASS, FAIL or INCOMPLETE. Preserve the original run when replaying a failure; a later pass does not overwrite it.

## Schedule vocabulary and execution

Each step has a unique positive contiguous ordinal, a stable step label, a closed action and explicit parameters.
Entity arguments use named logical references resolved by the runner, not caller-chosen database IDs. Actor aliases resolve trusted in-process sessions.
Expected API versions are explicit plan integers or references to a named prior command result; no hidden query-and-retry loop changes a rejected command.

| Step | Parameters and behavior |
| --- | --- |
| CREATE | Logical intent label, actor, scenario and required local capabilities; retain returned UUID alias. |
| QUOTE / APPROVE / EXECUTE | Intent label, actor, expected version and catalog/previous-result references; call the existing typed facade. |
| COMPLETE_ACTION | Intent/action label, trusted actor, expected action version and approve/deny; never asserts a payment result. |
| REVOKE | Trusted actor and fixture grant alias; use the existing monotonic command. |
| ADVANCE_CLOCK | Exact UTC instant; same run-row transaction and half-open expiry rules as ordinary commands. |
| RUN_NEXT | Execute one worker step and record the selected logical operation/work kind, or explicit IDLE. No background worker runs concurrently. |
| RECONCILE | Existing intent label and explicit `sealIfAbsent`; preserve optional capability policy described below. |
| RECONSTRUCT | Dispose/recreate service/adapter/repository instances against the same database; label as reconstruction, not process-kill proof. |
| ASSERT | Named versioned invariant/assertion with typed expected values, checked against authoritative database/evidence. |
| INTERLEAVING | A named, versioned finite recipe from the controlled harness, with exact parameters and barrier order. No arbitrary code. |

The first implementation can ship the sequential vocabulary and two fixed interleaving recipes: admission/revoke/send and seal/send.
A recipe records entry/release/commit boundary labels, outcome and any timeout; threads alone do not constitute a recorded schedule.
Use explicit barriers at boundaries outside core transactions, such as after admission commit before adapter send or after journal commit before return.
The run-row lock continues to order all core decisions. A barrier timeout is an incomplete/failed test, never permission to advance the financial state.
Never hold a core transaction while blocking on a simulator barrier or journal operation.
Clock-race recipes can be added with their own fixed boundary sequences and evidence; do not claim their coverage before implementation.

There is one runner owner for a replay run. Ordinary graph/UI commands and the wall-clock scheduled worker must not mutate a replay-owned run.
Use an internal execution mode/owner check rather than relying on the test author to remember to stop background processing.
The minimal implementation can require `runner:write` plus membership in the run's immutable trusted fixture-principal allowlist for every mutation of a REPLAY run.
Only the in-process runner constructs those fixture actor sessions, retaining the normal command-role/subject checks as well; no HTTP token maps to such a session.
Use distinct replay principals so the interactive application's fixed background-runner principal cannot accidentally operate a replay run.
No new HTTP route exposes plan execution, fault controls, clock control, journal injection or runner ownership.
The public reference application remains interactive mode; only trusted CLI/test composition can create a replay-owned run.

## Stable work ordering and receipts

Add a per-run monotonic `next_work_sequence` and an immutable outbox `enqueue_sequence` allocated under the existing run lock when new work is inserted.
A duplicate `(tenant,run,operation,kind)` keeps its original sequence. Do not increment/reassign its identity on retry, lease/fence change or restart.
Select by the existing documented kind priority PREPARE, DISPATCH, RECONCILE, then enqueue sequence; never by random UUID or database physical row order.
An explicit schedule controls command insertion order; the stored sequence determines worker order even after reconstruction.
Expiry scans and revocation loops must also use deterministic logical creation/admission order when they produce observable audit events.
Add an immutable intent creation sequence, or derive it from the initial DRAFT audit sequence; do not leave audit-producing loops ordered by random intent UUID.
Order returned admitted-attempt IDs by the existing HTTP lexical rule; normalize that response set by logical aliases when comparing replay evidence.

Preserve `runNext(session): boolean` for current callers. Add an internal detailed runner result, for example:

```java
record WorkerReceipt(Optional<UUID> operationId, Optional<String> workKind,
                     long fence, long firstAuditSequence, long lastAuditSequence,
                     boolean progressed) {}
WorkerReceipt runNextRecorded(Session runner);
```

The result reports actual selected work and committed audit extent, including expiry-only progress and IDLE; it must not select work a second time.
The exact Java type may use enums instead of strings. The runner records a safe logical form of this receipt with the step result.
Unknown polling that changes nothing must remain visibly unchanged; it must not generate fake progress or reset an operation's work order.

Runner step recording crosses core/journal commit boundaries. Before invoking a step, persist STARTED with plan/step identity; afterward persist result, aliases and DONE in one runner transaction.
Financial commands use a stable command key derived from plan digest/step ordinal/actor alias, so an interrupted accepted command can be located safely.
However an interrupted RUN_NEXT cannot simply be rerun: it may select the next work item after the previous work already committed.
For this minimal design, any STARTED step without a durable DONE receipt after runner process loss marks that execution INCOMPLETE and stops automatic plan continuation.
Keep payment recovery available for its existing operations, and reproduce the plan in a fresh isolated run. Do not mislabel this as resumable exactly-once scheduling.
Later resumable schedules require per-step durable worker-stage receipts and a separate review; ordinary payment recovery already remains durable.

Smallest runner entry point: `ReplayReport execute(ReplayPlan plan)` creates a fresh physical run, resolves trusted fixture actors, executes exactly the expanded steps and persists the report.
Use `ReplayPlan(formatVersion, logicalRunName, seed, initialClock, configuration, artifacts, steps)` and `ReplayStep(ordinal, label, action, actorAlias, arguments)`.
`configuration` and `artifacts` are immutable typed records with the fields defined above; `action` is a closed enum.
The JSON parser must convert each step's arguments into a closed action-specific record before execution, rejecting unknown fields rather than passing arbitrary maps into core commands.
Expose `ReplayReport compare(ReplayReport first, ReplayReport second)` or an equivalent pure comparison result; require matching plan/normalization versions and passing raw invariant checks before declaring replay equality.
Plan-file parsing and report JSON belong to runner infrastructure, not the domain or SDK adapter port.

## Minimal additive persistence

Use new numbered migrations; do not change published V001..V003 checksums.

| Storage change | Minimum contents and constraints |
| --- | --- |
| `simulation_runs` additions | execution mode INTERACTIVE/REPLAY, next work sequence; optional immutable intent sequence counter if not using audit creation order. |
| `outbox` addition | Nonnegative/positive immutable enqueue sequence, unique within tenant/run for newly sequenced work. |
| `replay_runs` | Tenant/run FK, unique plan execution ID, logical run name, seed, canonical plan/digest, configuration/catalog/artifact digests, initial clock, owner/fixture-principal allowlist, RUNNING/PASS/FAIL/INCOMPLETE and safe final report reference/digest. |
| `replay_steps` | Tenant/run/ordinal PK, stable step label, action/request digest, STARTED/DONE/FAILED, safe response/receipt, observed invariant summary; no financial state authority. |
| `replay_aliases` | Tenant/run/entity kind/logical alias unique; physical UUID unique within entity kind; inserted from accepted results and verified relationships. |

The runner may persist aliases for an intent and its attempt/operation/action; journal event aliases derive from operation alias plus fact sequence.
Audit aliases derive from durable run audit sequence, not arbitrary event UUID. A duplicate event keeps the same alias; conflicting variants include their safe fact digest.
For old interactive runs, backfill stable sequences once using a documented historical order and label them LEGACY/non-replayable.
Do not claim that a backfill reconstructs the actual historical schedule. New replay runs require all sequencing fields from creation.
Runner metadata never updates payment amounts, balances, admission state or journal outcome directly.
At final report collection, stop/finish all runner recipes and ensure there is no active worker/journal call; then read a consistent database snapshot.
A snapshot with an unresolved active call is not final replay evidence. It may be preserved as an explicitly incomplete diagnostic snapshot.

## Normalization and invariant digest

Use `simulator-normalized-state-0.1`, fixed JSON field order, ordinal sorting of named collections, exact integer-money strings and UTC instants.
Compare fresh executions by their logical run/intent/attempt/operation/action aliases, not physical tenant/run/UUID/correlation values.
Do not remove meaningful order: preserve audit sequence, admission sequence, work enqueue sequence, fact sequence and recorded barrier order.
Exclude wall-clock diagnostics, local database addresses, passwords, bearer sessions, raw credential references and transport correlation IDs.
Physical run IDs are replaced by a fixed logical run alias; timestamps remain the pinned virtual timestamps, not rounded wall time.

The normalized snapshot includes:

- Exact profile/config/catalog/descriptor/policy/plan identities and executed ordered step receipts, including errors, IDLE and fault outcomes.
- Each intent's logical identity, merchant, scenario, state/version, checkout revision/digest, exact money, owner and bound grant alias/version.
- Reservations and budget limit/held/consumed/frozen totals, operation admission order and final outcome, action state/version/expiry and logical subject alias.
- Outbox kind/order/fence/done state, accepted evidence sequences/outcomes, journal key-to-operation correlation, terminal sealing and safe conflict summaries.
- Safe audit changes in their original order and the exact named invariant outcomes. Unknown/unresolved exposure remains represented, not omitted.

Before normalizing, independently assert raw database facts: tenant/run-qualified FKs, one attempt/operation/owner, exact budget sums,
HELD unknown exposure, immutable request bindings, journal/core identity and money matches, stable keys, terminal seal immutability and no unauthorized admission.
Reconstruct and verify raw `OperationBinding` digests against persisted values and journal identity; physical IDs inside those digests differ across runs.
Then compute a separate normalized binding digest from the validated safe logical fields. Do not copy the raw ID-dependent request digest into the cross-run comparison.
Credential binding is verified inside the trusted check and represented only by its catalog identity plus a binding-valid assertion, never by its raw reference.
A failed raw check fails the run before equality can be considered; normalization must not erase a wrong identity, amount, run, missing fact or contradictory evidence.
Unknown physical references are errors/quarantined conflicts, not auto-assigned aliases that could hide a cross-operation substitution.

Publish separate normalized core-state, journal, audit/schedule and combined invariant digests, with their normalization version.
Matching hashes show reproduction of these specific validated projections; they are not cryptographic provenance, signature verification or interoperability evidence.
Record why intentionally random physical fields are excluded, and golden-test both equal replay and changed-money/order/conflict negative comparisons.

## Generic descriptor registry without enabling providers

Keep existing `CapabilityOffer`, `ChargeOperation`, `Money`, `ActionType` and `PaymentAdapter` explicitly local-profile APIs in this slice.
Do not loosen their validated constructors to accept arbitrary real providers or unsupported currencies/actions.
Add generic immutable metadata types in a separate SDK package; they contain no network endpoints or credential material.
Suggested Java signatures are:

```java
record AdapterDescriptor(String schemaVersion, String providerId, String revision,
                         Set<String> extensionVersions, Set<String> profiles,
                         Set<String> capabilities, List<AdapterCombination> combinations) {}
record AdapterCombination(String methodId, String credentialTypeId,
                          Set<String> actionTypeIds, Set<String> operations,
                          Set<String> capabilities, List<CurrencyLimit> limits) {}
record CurrencyLimit(String currency, int exponent, String minimumMinorUnits,
                     String maximumMinorUnits) {}
record AdapterSelection(String tenantId, String merchantId,
                        String providerId, String descriptorRevision,
                        String extensionVersion, String profile, String methodId,
                        String credentialTypeId, String actionTypeId,
                        Set<String> requiredCapabilities, String operation,
                        String currency, String minorUnits) {}
record AdapterAvailability(String providerId, String descriptorRevision,
                           String tenantId, String merchantId, String accountAlias,
                           boolean configured, boolean enabled, boolean available,
                           Instant validFrom, Instant expiresAt) {}
record NegotiatedDescriptor(String descriptorDigest, AdapterSelection selection,
                            AdapterCombination combination, CurrencyLimit limit,
                            String accountAlias) {}
interface AdapterRegistry {
    AdapterDescriptor require(String providerId, String exactRevision);
    NegotiatedDescriptor negotiate(AdapterSelection selection,
                                   AdapterAvailability availability, Instant now);
}
```

Descriptor schema is exactly `adapter-descriptor-0.1`; collections are immutable, duplicates reject, versions/revisions are exact and unknown required fields reject.
Provider, method, credential type, action type, operation and capability are independent versioned identifiers, not a provider enum or a Cartesian product.
Use an explicit action identifier such as `none`; an empty action set must not silently mean no authentication.
A combination is an allowed tuple with its own operation/capability/currency limits; global capabilities cannot manufacture a missing tuple.
Currency limit metadata may describe arbitrary uppercase three-letter codes and nonnegative integer exponents within the schema bound; this does not change local Money support.
Validate positive decimal integer bounds and `min <= max`; avoid floating point. Metadata recognition is distinct from supported execution semantics.

`AdapterAvailability` is a trusted, separately supplied tenant/merchant/account-configuration snapshot with descriptor revision,
configured/enabled/currently-available flags and virtual validity interval; it contains an opaque account alias, never account credentials.
Negotiation requires both a matching descriptor tuple and current availability. A supported-but-unconfigured provider is not executable.
Match selection tenant/merchant/provider/revision to availability, require `validFrom <= now < expiresAt`, and reject disabled/unavailable configurations.
Match selected currency and exact minor-unit amount to one allowed limit; return that limit with the selection. No FX or implicit exponent conversion occurs.
Negotiation is a metadata/configuration check, not spend authorization or permission to instantiate a connector.
The registry has explicit immutable registration at composition time. Duplicate provider/revision with different content rejects; never silently replace a descriptor.
Negotiation returns the selected descriptor digest/tuple/limits for later binding. Unknown required capabilities reject; unknown optional declarations remain unclaimed metadata.
An `ExecutionPolicy` separately decides whether this process may instantiate/use a negotiated provider. The local policy allows only the simulator profile and exact existing capabilities.
Registering a synthetic descriptor named like a real provider is merely a fixture; it creates no connector, credentials, network call or compatibility assertion.

## Optional sealing and TCK contracts

Atomic absence sealing is optional at the generic capability level: use an explicit `atomic-no-effect-seal` identifier.
An adapter lacking it must retain unresolved NOT_FOUND exposure and use lookup/manual investigation; absence cannot be converted to NO_EFFECT.
For source compatibility the existing local `PaymentAdapter.sealNoEffect` remains required. It represents the stronger simulator contract only.
A generic TCK harness discovers a separate optional extension instead of requiring every adapter to implement the local interface:

```java
interface AtomicNoEffectSealer {
    Fact sealNoEffect(ChargeOperation operation);
}
interface TckExecutionDriver {
    Preparation prepare(ChargeOperation operation);
    Fact execute(ChargeOperation operation);
    LookupResult lookup(OperationIdentity identity);
}
record TckCaseContext(String caseId, String fixtureNamespace, String seed,
                      Instant initialClock) {}
record TckObservation(long acceptedOperations, long successfulCharges,
                      boolean immutableBindingsValid, List<Fact> facts) {}
interface AdapterTckFixture extends AutoCloseable {
    AdapterDescriptor descriptor();
    ChargeOperation operation(String logicalName, String scenarioId);
    TckExecutionDriver execution();
    Optional<AtomicNoEffectSealer> noEffectSealer();
    TckObservation inspect();
}
interface AdapterTckFactory {
    AdapterTckFixture open(TckCaseContext context);
}
record TckPlan(String suiteVersion, String selectedProfile,
               Set<String> requiredCapabilities, Set<String> requiredCaseIds,
               List<String> caseIds, String seed, Instant initialClock) {}
enum TckStatus { PASS, FAIL, UNSUPPORTED, INCOMPLETE }
record TckCaseResult(String caseId, TckStatus status, String reason,
                     Map<String, String> safeAssertions) {}
record TckReport(String suiteVersion, String profile, String descriptorDigest,
                 List<TckCaseResult> results, boolean requiredCasesPassed,
                 List<String> exclusions) {}
TckReport run(AdapterTckFactory factory, TckPlan plan);
```

`TckExecutionDriver` is a test bridge with prepare/execute/lookup and controlled delivery/fault operations using the existing synthetic test values initially.
The JDBC simulator fixture delegates to the current PaymentAdapter; an in-memory independent stub validates harness reuse but cannot substitute for PostgreSQL recovery evidence.
Open a fresh isolated fixture per case and always close it. Require identical descriptor revision/digest across a suite, and record mismatch as failure.
Fixture cleanup must not erase unresolved exposure silently; retain diagnostic evidence or use explicit stopped isolated-run teardown after report extraction.
`operation(logicalName,scenarioId)` allocates stable test identities within that fixture; it cannot set a result in the adapter under test.
The generic TCK runner itself has no JUnit dependency; JUnit tests exercise its cases and deliberate failing fixtures.
These signatures make generic metadata and optional features reusable; they do not claim that the local ChargeOperation model represents real auth/capture, FX or partial payments.
A future real connector must supply a reviewed profile-specific bridge/model and its actual event/credential semantics under G2-P.
The harness alone obtains test credentials if ever needed; none enter descriptors, generic report fields or model context.

TCK cases inspect independent safe observations rather than treating an adapter-returned success string as the sole assertion.
For the local fixture, observations include journal charge/key count, immutable binding, event history and core exposure where relevant.
Adapter-only tests cover capability tuples, effect-free prepare, key/digest/money/scenario conflicts, stable duplicate outcomes, lookup uncertainty,
event correlation/order and optional seal-before/after-acceptance behavior. Core-composed cases separately test reservation/authority/action/frozen exposure.
Do not label adapter-only tests as proving the core's entire CT-003/004/005/006/009 requirements.

The minimal initial TCK case catalog is versioned `adapter-tck-0.1`:

| Case | Required observation |
| --- | --- |
| AT-PREPARE | Calling prepare repeatedly creates no accepted operation or successful charge; only the profile's declared action result appears. |
| AT-IDEMPOTENCY | Duplicate same-identity execution preserves event/operation identity and one economic effect; changed request digest/money/scenario is refused without changing the first binding. |
| AT-LOOKUP | Known identity returns the same authoritative binding; an unused identity is NOT_FOUND/UNAVAILABLE with no invented no-effect fact. |
| AT-SEAL | When advertised, seal-before-send prevents effect and acceptance-before-seal returns the accepted/final fact. Without the capability this case is explicitly unsupported unless required by the selected profile. |

Additional fault/event/capability cases need their own observation controls and versioned IDs; do not imply they passed because these four did.
The plan's required case IDs must all be present, unique and known; omitting a required case cannot make `requiredCasesPassed=true`.
Reject an empty selected mandatory suite. Safe assertion keys/reason messages are predefined; never copy arbitrary exception text or payloads into a report.

Each result is PASS, FAIL, UNSUPPORTED or INCOMPLETE and includes case/version, asserted capability/profile, observed safe invariants and source/artifact digests.
UNSUPPORTED is valid only when a capability/profile is not advertised and not required by the selected test profile; provide the declaration/reason.
Advertised but untestable behavior is INCOMPLETE. Required unsupported cases, failures, missing evidence and unexplained skips prevent a profile pass.
Descriptors may advertise features outside the selected TCK plan; list them as unevaluated, and scope the result to selected cases rather than calling the entire descriptor certified.
The local exact profile requires sealing and all baseline capabilities, so a no-seal adapter can pass generic baseline cases but cannot pass that profile.
CT-008 stays unsupported until a separately reviewed voucher/split execution model exists; reporting a descriptor tuple does not implement it.
Reports include the original failure alongside rerun references and explicit local-fake/no-external-accreditation labels.

## Required implementation slices and acceptance tests

1. Add descriptor/availability/selection/registry metadata and a local policy adapter. Test two synthetic providers, multiple allowed tuples,
   invalid cross-products, missing required features, unknown versions, unsupported/unconfigured/temporarily unavailable choices and duplicate revision conflict.
2. Add additive work/intent ordering migrations and detailed receipts. Test two interleaved intents in identical plans with different physical UUIDs,
   reconstruction before work, duplicate work insertion and deterministic expiry/revoke audit order. Existing HTTP contract tests must remain unchanged.
3. Add plan storage/parser/runner/aliases and normalized snapshots. Run the same expanded plan in two fresh database runs and compare every digest;
   change seed-generated schedule, amount, action choice, failure or barrier order and prove the relevant digest/assertion changes.
4. Test interrupted STARTED step becomes INCOMPLETE, with no blind next-work replay or erased original exposure; replay occurs in a fresh run.
   Prove ordinary graph/UI/background worker attempts cannot mutate a replay-owned run.
5. Add reusable TCK fixtures for the JDBC simulator and an independent synthetic no-seal adapter. Test truthful optional-feature exclusion,
   mandatory-profile refusal, advertised-but-untested INCOMPLETE, preserved failure evidence and lookup absence that remains unresolved.
6. Exercise both fixed interleaving recipes with recorded barriers, stable journal identities, held exposure and final replay equality.
   Add fresh-process crash and additional concurrent-clock cases separately; source reconstruction alone must keep its narrower label.

Run real PostgreSQL migrations/integration tests, SDK/unit/schema/golden tests, unchanged HTTP tests and independent source review.
Record exact artifact identities and test commands. Passing these slices closes only their named CT-011/012 and adapter-TCK evidence gaps;
the complete CT matrix still requires its remaining action/binding/event/privacy/concurrency cases and a separate acceptance decision.

## Consequences and excluded alternatives

Explicit schedules and persisted work order are simpler to audit than attempting to replay arbitrary thread timing from a seed.
Physical UUID normalization preserves isolation without making externally visible IDs predictable; raw invariant checks prevent normalization from hiding identity faults.
An interrupted runner may be incomplete even while payment recovery remains correct; separating those concepts avoids unsafe duplicate worker progression.
Generic descriptors preserve extensibility while the closed execution policy protects the already-reviewed runtime.
Making seal mandatory for all future providers, silently treating NOT_FOUND as no effect, weakening local validation or enabling arbitrary connector URLs is rejected.
No real account call, live money, AP2/ACP wire claim, sandbox-provider activation, split-tender implementation, public release or deployment is authorized by this ADR.
