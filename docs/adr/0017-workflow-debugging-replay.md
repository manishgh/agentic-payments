# ADR 0017: Bounded workflow diagnostics and read-only recorded-step replay

## Status

Accepted design. Implementation and capability claims require the acceptance evidence below. This decision complements [ADR 0007](0007-durable-workflow-runtime.md) and the external source handoff in [ADR 0016](0016-external-checkout-workflow-handoff.md).

## Context

A workflow failure needs an inspectable record of the input, decision, attempted activity and safe outcome. SQLite currently retains latest orchestration progress and a monotonic recovery projection, not the complete sequence of attempts. Temporal retains scheduler history and supports native history replay, but a worker may finish a timed-out activity after another retry has begun. An activity returning a result does not prove that the scheduler consumed that result.

Operational logging cannot supply these guarantees: it may be disabled, sampled or dropped. Conversely, retaining arbitrary exceptions, checkout responses or credential-bearing requests for debugging would violate the workflow's privacy boundary. Financial commands also cannot be repeated casually to reproduce an error.

## Decision and bounded scope

Add a durable, bounded activity journal to the existing runtime registration responsibility. Newly registered workflows using the modern SQLite/Temporal runtime receive this journal by default. Record safe activity inputs and invocation outcomes; provide local inspection, export and offline replay of recorded steps. Replay reruns only the versioned pure decision implementation and uses retained outcomes for effectful steps. It has no payment, merchant, scheduler or provider clients.

This is **recorded-step and decision replay**, not a time-travel debugger or a promise to reproduce external exceptions. It can show the failed invocation and its fixed error category, verify deterministic decisions, and validate the retained effect inputs/results without invoking those effects. It cannot reconstruct protected requests or arbitrary Python stacks. Live recovery remains the existing same-identity `resume`, with current core authorization and admission checks.

Both modern runtimes use the same journal contract and invocation wrapper. Temporal's native `Replayer` remains the separate check of deterministic scheduler history. This slice changes no deterministic Temporal workflow commands, scheduling sequence or activity payload shapes. The older standalone `PaymentWorkflow`, commerce workflow and pre-existing registrations are not retroactively advertised as having a complete journal.

## Responsibilities and ports

Keep journal writes within `RegistrationStore`, which already owns durable workflow identity, exact activity staging and recovery projection. Do not introduce a separately committed best-effort tracing sink. Extend this responsibility with typed methods whose precise names may follow repository conventions:

| Operation | Required behavior |
| --- | --- |
| `begin_activity(envelope) -> InvocationRef` | Validate the registered start and closed envelope, atomically retain the immutable logical activity if new, reserve one bounded invocation/outcome slot, and commit an invocation-start record before calling activity code. |
| `record_activity(envelope, result, invocation)` | Validate the closed result and invocation identity; atomically append its terminal success and apply the existing monotonic recovery projection. An identical duplicate completion is idempotent. |
| `record_failure(envelope, failure, invocation)` | Append a closed sanitized terminal failure for that invocation; never infer or change payment state. An identical duplicate is idempotent. |
| `trace_policy(reference)` | Return immutable journal version, coverage origin and limits, or explicit historical unavailability. It cannot enable, reset or replace an old journal. |
| `describe_trace() -> TraceWriterDescriptor` | Return the trusted typed declaration of the exact writer contract, immutable limits and guarantees. This is composition metadata, never a workflow-supplied assertion. |

The runtime adapters call a common bounded invocation wrapper around injected activities. The wrapper validates inputs, begins the invocation, invokes activity code, validates the output and records success or safe failure before delivering the outcome to the scheduler. This includes errors from decorated activities and result validation. Its activity/validation failure handler is separate from its journal-success publication handler: uncertainty from `record_activity` must never be caught as an activity failure and sent to `record_failure` for the same invocation. Calling an arbitrary activity directly outside that wrapper does not claim journal coverage.

Define a separate **read-only** `WorkflowTraceReader` responsibility with `read_trace(reference) -> TraceSnapshot`. Its SQLite implementation opens existing storage in read-only mode and takes a consistent snapshot. It never constructs the write-side registration store, creates schema, calls `reserve`, or obtains a live payment/scheduler client. A replacement reader or writer implements the same versioned contract. The local registration adapter may implement both responsibilities, but consumers receive only the interface they need.

Atomic success-plus-recovery publication is part of the writer contract. A replacement backend must implement it; forwarding journal writes to an unrelated log backend does not qualify. Optional telemetry may observe journal health through existing bounded categories, but cannot substitute for durable records or relax journal failure handling.

Pin the configured runtime capability to `bounded-step-replay-1`. Its typed `TraceWriterDescriptor` declares this exact contract and guarantees `begin-before-effect`, `atomic-terminal-and-recovery`, `bounded-conflict-retention` and the documented limits. The composition root validates the injected writer's trusted descriptor before accepting new work. A runtime advertises this capability only when its actual injected writer declares and implements all guarantees; a static runtime catalog entry, method-name inspection or `hasattr` is insufficient. Decorators must preserve the descriptor and behavior. A trusted declaration is a composition assertion supported by contract tests, not cryptographic proof about arbitrary replacement code.

`reserve` must validate `StartWorkflow` against its registered runtime capabilities before any storage mutation, including direct calls by adapters or tests. New registration and the journal header commit together. The runtime and invocation wrapper validate the writer contract before new reservation or work; an unsupported/untraced replacement store fails closed. Historical unavailability is permitted only for a positively identified existing registration without a header, never as a fallback for an absent registration or a new store lacking journal support.

## Append-only data contract

Retain a versioned journal header alongside the existing registration. It includes the exact original start data/fingerprint, coverage `FROM_REGISTRATION`, journal version and immutable limits. It does not alter historical `StartWorkflow.data()` or its fingerprint. Existing registration rows without a header report `HISTORICAL_TRACE_UNAVAILABLE`; do not fabricate an initial event or infer a missing prefix from latest progress. New workers may continue those historical workflows through their existing recovery behavior, without a claim of full diagnostic coverage.

The safe journal consists of:

- **Logical activity:** registered workflow reference, activity ID, logical sequence, kind, exact validated safe envelope and a canonical envelope digest. One activity ID has one immutable envelope. A retry with a changed envelope conflicts before activity execution.
- **Invocation start:** a store-assigned positive invocation ordinal, a monotonic journal event ordinal and the logical activity reference. Every actual call of activity code gets a separate invocation, even when Temporal reuses an activity ID. A start with no terminal outcome is explicitly unresolved.
- **Invocation outcome:** exactly one validated result or fixed safe failure, linked to that invocation and a monotonic event ordinal. Duplicate identical writes create no additional terminal event. Contradictory terminal writes are rejected and surfaced as evidence conflict, never overwritten.
- **Conflict finding:** an append-only fixed safe conflict category linked to the affected logical activity or invocation. Reserve at most one logical-envelope conflict slot per activity and one terminal/result-consistency conflict slot per invocation. Repeated findings in an occupied slot are idempotent and do not append unbounded records. The marker never contains the rejected raw value.

`InvocationRef` is an opaque local diagnostic identity derived from the workflow/activity reference and allocated ordinal. It is not a payment command ID, activity retry key, lease or financial fence. It never enters core requests or changes `command_key`, attempt, operation or provider identity. Store-generated ordinals, not process-local counters or wall-clock ordering, establish append order. Times are unnecessary for this first contract and are not used to reconstruct decisions.

Preserve the current safe start/state/result allowlists, including ADR 0016's three external-source selectors. Do not store transport request bodies. The exact immutable envelope is already safe; tender credentials remain materialized only inside trusted activity code. Success records contain only `validate_activity_result` output. Failure records contain only allowlisted codes and fixed categories described below.

Retain at most 512 logical activities and 4096 invocations per workflow, with the existing 16 KiB envelope/result limits and small fixed-size failure/conflict records. Store an envelope once per logical activity. These limits bound retained application payload size; they do not promise an exact physical database size. A begin transaction reserves space for its eventual outcome and invocation conflict marker; first registration of a logical activity reserves its envelope-conflict marker. Filling the start quota cannot prevent already-started invocations from recording completion or known conflicts. No ring buffer, silent eviction, in-place overwrite or automatic retention reset is allowed.

When capacity is exhausted, refuse a new invocation before activity code and return a fixed `WORKFLOW_TRACE_LIMIT` operational failure. Retain existing progress, journal and recovery references. Debug/export and authorized direct core inspection remain available. The limit does not cancel the payment or release its exposure. Increasing/resetting an existing workflow's limit or deleting its history is not a recovery mechanism in this slice.

## Ordering, retries and concurrent completion

An activity ID identifies a logical step, not an execution attempt. Retries of create, quote, execute and deterministic decisions must preserve their exact envelope and immutable successful output where that contract requires it. Each invocation still retains its own outcome, including a failure followed by a successful retry. Read-only `read_status` retries under the same activity ID may legitimately return newer core versions and must retain each validated result separately.

Never deduplicate success solely by activity ID or choose the last journal row as payment truth. A late thread may append an older observation after another thread has observed a newer version. The existing recovery projection stays monotonic and preserves original intent/attempt/operation identity; journal append order preserves what was observed without regressing that projection. Record the outcome even when it supplies no newer recovery information.

Validate the invocation against its registered workflow, exact activity envelope and terminal slot in the same transaction as completion. Concurrent begins allocate different invocation ordinals. Concurrent identical completion writes converge; differing terminal writes conflict. A differing envelope for an existing activity or a contradictory terminal/result must commit its reserved safe conflict marker while preserving the original envelope, terminal and recovery projection. Raise the fixed conflict exception **after** that transaction commits; raising inside a rollback context must not erase the finding. The operation makes no new activity call on envelope conflict. Each worker thread keeps its own returned invocation reference. The SQLite adapter's `RLock` protects its connection, while database transactions/uniqueness enforce invariants across allowed connections; an in-memory lock alone is insufficient. This adds no distributed-driver or lease guarantee to the local runtime.

If an otherwise safe output conflicts with retained recovery identity or violates result consistency, do not publish it as success. Preserve the existing projection, commit its reserved fixed conflict marker and a safe failed terminal if the invocation is still open, then fail outside the transaction. Across invocations of a single logical activity, successful non-`read_status` results must agree exactly; the writer enforces this and the offline verifier independently recomputes it. Distinct valid `read_status` results remain permitted. A complete-replay verdict must reject explicit conflict markers and inconsistent retained non-read successes. Unvalidated output and arbitrary exception contents are never retained to explain the conflict.

**Journal completeness and scheduler consumption are different claims.** A complete journal records the start and terminal outcome of every instrumented invocation in its snapshot. It does not prove which successful retry result the scheduler accepted. In particular, an unacknowledged or late Temporal completion remains an observed activity outcome. Present logical sequence and invocation append order separately. Do not merge all retries into a single invented path or label the last success as the consumed result. Exact scheduler-consumption replay remains Temporal history replay; a generic cross-runtime acknowledgement protocol is deferred.

## Failures and causal diagnostics

Introduce a closed safe activity-failure value with `code`, `phase` and `retryable`. The phase is a fixed category such as `REGISTRATION`, `PREPARATION`, `DECISION`, `CORE_CALL`, `RESULT_VALIDATION` or `JOURNAL`. Codes are an explicit bounded allowlist. Preserve existing typed safe errors where available; a known rejected core command must not be mislabeled as an access denial, and a missing template must not become indefinitely retryable transport unavailability. Unexpected exceptions become a fixed unexpected-activity code in the known phase.

Never derive a persisted code from arbitrary `str(exception)`, class/module names, stack traces, URLs, HTTP bodies, exception arguments, chained causes or local paths. If a failure is not already a declared safe application code, map it to the fixed category. The recorded cause is the original **safe classification**, not the original sensitive exception. A generic classification may limit diagnosis; the public contract makes no claim to retain full stacks.

| Failure window | Required behavior |
| --- | --- |
| Input or registration rejected before a safe invocation exists | Make no activity/core call. Return a fixed failure; no invalid input is written as diagnostic payload. Report missing trace coverage rather than invent a recorded invocation. |
| Begin unavailable or its commit unconfirmed | Make no activity/core call. Retry may attempt a new diagnostic begin for the same logical activity; an orphan committed start remains explicitly unresolved. |
| Activity failed and failure append succeeds | Return the original safe classification and retryability, with the retained invocation reference available to local inspection. |
| Activity failed and failure append is unavailable or its acknowledgement was lost | Preserve the original safe failure as primary and report fixed journal-unconfirmed status as secondary. The failed terminal may already be committed. Do not write a different terminal or infer that the invocation is open. Offline verification judges the actual durable snapshot. |
| Core command may have committed, but success/recovery publication is unconfirmed | Return fixed journal-unconfirmed/uncertainty behavior. Its successful terminal and recovery projection may already be committed. Never call `record_failure` for that invocation to describe this publication uncertainty: doing so could manufacture a conflict against a committed success. Retain the original staged envelope and payment identities; a permitted live retry creates a new diagnostic invocation using the original command/key. Never claim rollback, no-effect or permission for a replacement payment. |
| Result was committed, but scheduler acknowledgement was lost | A live retry may add another invocation with the same logical envelope. Preserve both observations and core idempotency; do not discard the first because it was not acknowledged. |

Success and recovery publication are atomic: both commit or both roll back. A lost commit acknowledgement leaves which outcome occurred uncertain; it is not proof of rollback, and neither case rolls back a prior HTTP command. The original intent can still be recovered through the same create key, and later commands through their registered original keys. Do not append a synthetic failure, conflict or incomplete marker merely because a journal acknowledgement was lost. The reader determines whether the invocation actually has a durable terminal. No trace operation changes financial state or releases reservations.

When a sanitized failure is passed to Temporal or emitted by the CLI, its primary/secondary fields use the same closed safe schema. Preserve retryability: journal capacity/conflict and invalid inputs are fixed refusals; transient journal availability may use the existing bounded runtime retry policy. Do not introduce unbounded retries. An abrupt process kill has no captured exception; its unresolved invocation is the evidence. Debugging must not invent a cause such as provider failure from that absence.

## Offline replay and verdicts

Export a self-contained safe snapshot: journal version, exact registered start/fingerprint, coverage metadata, declared snapshot event count/head, logical activity records, invocation starts, terminal outcomes and conflict findings. Build it in one consistent read transaction. Include canonical digests sufficient to detect mismatched or omitted records against the declared snapshot. The verifier validates every field, cardinality, bound, ordinal, linkage, envelope/result digest and start fingerprint before replay, rejects any conflict marker and independently checks consistency across successful non-read invocations.

These consistency digests do not authenticate a journal against a malicious party able to rewrite its entire contents and manifest. The reference trusts local registration storage and explicitly exported files. It provides no signed forensic evidence, external attestation or financial proof.

Offline replay accepts only the exported value and an allowlisted local pure decision implementation catalog keyed by the retained decision version. It must not construct `PaymentActivities`, a request-template client, a registration writer, an HTTP client or a Temporal client. For each **successfully recorded** decision invocation it reruns the pure decision on the exact recorded safe input and compares the closed result. If that replay throws, return `MISMATCH` with a fixed safe replay-failure classification; never expose the new exception or reinterpret the original success. A recorded decision failure has no successful result to compare: validate and display the safe failure without rerunning it or claiming to reproduce its exception. For effectful invocations it validates and displays the retained outcome; it does not call, mock-call or regenerate the financial action. Arbitrary dynamic import/module selectors from a trace are forbidden.

Return explicit outcomes:

| Verdict | Meaning |
| --- | --- |
| `VERIFIED_RECORDED_STEPS` | The snapshot is structurally complete, all successfully recorded decisions reproduce, and safe effect observations validate. This says nothing about current payment truth or which retry the scheduler consumed. |
| `VERIFIED_WITH_RECORDED_FAILURE` | The same checks passed, and an expected safe activity failure is present. Playback displays that failure; it did not reproduce an external failure by executing the operation. |
| `INCOMPLETE` | Missing historical prefix, missing declared records, an actually unresolved invocation in the snapshot or unsupported coverage. A caller's lost journal acknowledgement alone is not incompleteness when its terminal is retained. Show available safe diagnostics, but refuse a complete replay verdict. |
| `MISMATCH` | A deterministic decision differs or a supported-version record violates its contract/integrity. Stop replay at the first mismatch; never repair the trace or continue by substituting a new decision. |
| `UNSUPPORTED_VERSION` | Required journal/decision/profile semantics are unavailable. No fallback implementation or current-default replay is permitted. |

Evidence conflicts fail verification as `MISMATCH`; they are not an expected reproduced external error. A known recorded transport/core failure is a recorded failure, not automatically a replay mismatch. Truncation and unresolved attempts are `INCOMPLETE`, even if every available decision matches. An actively progressing workflow may provide a complete invocation snapshot at a stable boundary; label it as a snapshot rather than implying the workflow has ended.

Offline replay never mutates registration, graph checkpoints, runtime history, financial state or source bindings. It never starts/resumes a workflow, resets activity limits, resolves uncertainty, changes a source or issues approval. A successful replay is not authorization to retry any side effect.

## Local debugging interface

Keep runtime `inspect` as the existing explicitly non-authoritative progress view. Diagnostic reports explicitly carry `decisionsReplayed=false`; only a replay that finishes decision comparison reports `decisionsReplayed=true`. Structural inspection alone does not verify deterministic decisions. Add separate local CLI commands with clear descriptions:

- `debug`: read the existing local journal and show logical steps, per-invocation outcomes, safe failure codes, unresolved attempts, coverage and original safe payment references. It does not connect to core or scheduler services.
- `debug --export <path>`: write a validated bounded safe snapshot to an explicitly chosen local file. Reference examples use ignored `.tools/` paths; no upload or message transmission is implied.
- `replay --trace <path>`: run the offline verifier/playback and print the precise verdict and first mismatch/failure location. It needs no bearer token, running Java service or Temporal server.
- `resume`: retain existing live same-reference behavior and current financial gates. Help text explicitly distinguishes it from offline replay.

The local reader authorizes the requested reference against the retained scope/principal/run/workflow registration. Host filesystem access remains trusted, as for the existing local registry. This introduces no public debug endpoint or production access-control/retention system. Export can contain safe financial identifiers and money, so it remains a local user artifact rather than telemetry.

## Compatibility and runtime integration

Add journal tables without changing existing registration rows or start fingerprints. Header absence remains distinguishable from an empty newly registered journal. An existing attempted workflow is never silently upgraded to `FROM_REGISTRATION`. Unsupported historical journals are inspectable only through bounded metadata; they do not run against new semantics.

SQLite retains its existing pause-before-stage ordering. Its common wrapper begins after the exact envelope is staged, records success/failure before the driver advances progress, and preserves a pending envelope on an unconfirmed completion. A crash between outcome recording and progress update permits the same-envelope retry; it does not erase the first observation.

Temporal invokes the same wrapper inside the existing registered activity boundary, outside deterministic workflow code. The wrapper commits begin before executing the activity and success/recovery before returning to the SDK. Temporal timeouts may leave worker threads running; per-invocation references prevent one attempt overwriting another. Actual native history replay remains required for scheduler compatibility. No journal access, diagnostic I/O or new activity is added to deterministic orchestration code.

Historical registrations without journals preserve their earlier runtime behavior and explicitly unavailable replay coverage. Direct/adapted/decorated registration and activity implementations must preserve the new contract when advertising the capability. A no-op journal cannot advertise it. There are no new vendor APIs, packages or financial database migrations.

## Acceptance evidence

1. Exercise the writer/reader contract through direct and decorated implementations: immutable envelopes, concurrent invocation allocation, identical duplicate completion, contradictory completion, safe failure records, old registration detection, strict source/input validation before writes and bounded capacity with reserved outcome/conflict slots. Prove envelope/terminal conflicts remain durably marked after the method raises, preserve prior terminal/projection and force offline `MISMATCH`. Independently detect differing successful non-read results across invocations. Reject absent/misdeclared writer guarantees before new reservation or work; static capabilities or matching method names alone must not enable support.
2. Demonstrate successful SQLite and Temporal flows and failing activities with retained exact safe inputs. Kill a fresh worker after a persisted begin and after an accepted create/quote/execute before recorded completion. Verify unresolved evidence or same-key recovery, one financial effect and no invented rollback/no-effect.
3. Retry `read_status` under one activity ID with newer and late older results. Retain distinct outcomes while recovery remains monotonic. Demonstrate debug output does not label a late result as scheduler-consumed state. Preserve the existing original attempt/operation guards.
4. Inject storage faults before begin, after a possible financial commit and while recording the original failure. Include committed success-plus-recovery and committed failure with lost acknowledgement. Prove no activity call occurs without confirmed begin, no publication-uncertainty handler writes a false failure/conflict, durable outcomes remain verifiable, and live retries retain the original command identity under a new diagnostic invocation. Trace capacity stops new calls while existing outcomes/conflict markers and read-only debugging remain available.
5. Export, stop Java/Temporal, and replay with all network/payment/scheduler constructors replaced by failing sentinels. Match successfully recorded decisions, display recorded decision failures without rerunning them, and return safe `MISMATCH` if a formerly successful decision now throws. Display other known recorded failures, reject altered decisions/evidence, report missing prefixes/records/unresolved invocations as incomplete, and reject unknown versions without fallback. Verify replay does not change any local database or checkpoint bytes.
6. Scan journal tables, exports, CLI output, SDK failures and raw/decoded Temporal payloads for token/credential/exception/remote-session/raw-ACP/artifact/key canaries. Malicious decorated outputs and unknown exception strings never enter persistence. Safe failure messages remain bounded and useful.
7. Replay actual pre-change native/legacy Temporal histories and verify old fingerprints remain exact. Run the external handoff restart/privacy/UNKNOWN evidence from ADR 0016 through the instrumented runtime. Keep aggregate PostgreSQL-backed conformance, documentation and whitespace checks passing.

## Consequences and alternatives

The bounded journal makes failure location and recorded decisions inspectable across process loss without replaying a charge. It adds durable writes and an intentional operational stop when diagnostic capacity or storage is unavailable. An unresolved attempt may prevent a complete diagnostic verdict even when core status later confirms payment success; payment truth and diagnostic completeness remain separate.

Best-effort logs, replaying live HTTP commands, overwriting one row per activity ID and inventing scheduler consumption from the latest successful retry are rejected. A fully acknowledged cross-runtime execution timeline, debugger breakpoints/locals, arbitrary historical code loading, distributed trace services, automatic retention rollover and production forensic assurance remain future work.
