# ADR 0016: Register external checkout sources before durable payment orchestration

## Status

Accepted design. Implementation and runtime support require the acceptance evidence below. This decision extends [ADR 0014](0014-external-checkout-authority-binding.md) and preserves [ADR 0007](0007-durable-workflow-runtime.md).

## Context

The commerce workflow can retain a completed synthetic checkout observation. The financial HTTP API can bind that observation to an AP2 payment intent, but the payment workflow currently quotes a generated fixture. Passing a purchase reference to a graph node without durable registration could select different evidence after a lost reply or restart. Merely adding the external profile to the shared profile enumeration would also enable the older unregistered graph accidentally.

The existing durable runtime already reserves immutable start inputs before an attempted start, validates workers against that registration, stages exact activity envelopes and retains safe recovery references. Its SQLite and Temporal adapters use the same payment activities. The financial core already atomically validates retained external bytes, seals a purchase to one intent and admits only an independently approved synthetic payment. The new slice joins these existing boundaries; it does not create another financial coordinator.

## Decision and scope

Enable `local-external-ap2-acp-single-tender-0.1` only through an explicitly source-bound template of the shared durable runtime. Keep the existing standalone `PaymentWorkflow` and its CLI limited to their current native and legacy profiles. Preserve their constructors, command identities and persisted histories.

The handoff begins with a selected observation already retained by the commerce service. The caller supplies its safe purchase UUID, observed purchase revision and response UUID to the trusted local runtime control process. The commerce workflow may produce these values, but no commerce checkpoint, remote status or caller assertion is financial authority. The handoff does not create, retrieve, update, recover or complete merchant checkout on behalf of the financial graph. The financial quote command reloads and validates the protected observation under the existing PostgreSQL lock.

Use the existing `WorkflowRuntime`, `RegistrationStore` and `PaymentPort` responsibilities. Add a typed external quote operation to the payment port and an injectable, versioned request-template policy for materializing the appropriate quote command. Implementations may be direct, adapted or decorated. No vendor-specific port, dependency, Java API, financial schema or admission change is required.

The intended executable boundary includes both SQLite and Temporal through the common activity implementation. Temporal support is conditional on actual external-profile restart, retry, history replay and privacy tests; supporting the Python value type alone does not establish support. An adapter that has not met those requirements must reject this template before reserving a workflow or contacting its scheduler. There is no fallback to another runtime. Distributed registration, cross-runtime migration, history reset, automatic replacement workflows and production availability remain excluded.

## Immutable start and source contract

Introduce a closed immutable source value, versioned by the external template:

| Field | Meaning |
| --- | --- |
| `purchase_id` | Canonical local commerce purchase UUID. |
| `expected_purchase_revision` | Positive integer revision selected from the retained observation. |
| `response_reference` | Canonical local retained response UUID. This is a selector, not authority or an artifact payload. |

The source has no amount, currency, remote session ID, URL, merchant selector, source-principal selector, configuration override, artifact, key, tender or credential. Scope, run and source principal are inherited from the immutable `StartWorkflow` and the injected authenticated client. The source must belong to that principal; the existing core rejects another principal's source. Later trusted customer approval remains independently authorized and uses the source principal retained by the financial binding.

Use explicit compatible combinations of profile, template and activity versions. The existing `synthetic-request-1` / `payment-activities-1` combinations retain their exact current behavior. The external combination is `external-checkout-request-1` / `external-checkout-activities-1`, requiring the external profile and a complete source. It also requires the runtime capability `external-checkout-handoff-1`, even when the caller's `required_capabilities` is empty. Native/legacy templates reject a source; the external template rejects its absence, partial values and unknown members. A wrong combination never falls back to fixture quoting.

Keep two explicit serialized start schemas. The historical schema omits the source field entirely and has exactly its existing members and fingerprint bytes. The external schema adds the closed source field and carries the external versions. The decoder accepts only those exact supported shapes. Do not serialize a new null field into old registrations, reinterpret historical fingerprints or backfill an external source into an old workflow. Dataclass defaults alone do not supply this compatibility guarantee.

The canonical external start fingerprint covers the source, scope, principal, run/workflow identity, profile, scenario, runtime, requirements, limits and all existing version selectors. `RegistrationStore.reserve` validates and atomically retains this complete start before `claim_attempt`, scheduler creation or any core command. Every effectful activity validates its envelope against the retained registration before materializing a request. A different source, template, scenario, profile or owner under the same workflow identity conflicts. Missing registration fails closed. There is no patch-source, delete, unbind or automatic reset operation.

The source remains in the registered start and validated activity envelope. It is not mutable graph state and cannot be replaced by a wake, resume value, query result or a later commerce observation. Resume/control operations recover the registered start by the existing scoped workflow reference rather than requiring the operator to reselect a source. If optional repeated source arguments are accepted, they must match the retained value exactly before any work; omission is not permission to invent a new value.

## Ownership and trust

Registration owns one workflow's immutable selection; it does **not** claim global exclusive ownership of a purchase before quoting. Two distinct workflow IDs or registries may create separate DRAFT intents for the same source. The existing atomic core seal permits only one of those intents to publish an external quote and proceed to approval. The loser receives a refusal and remains without financial authority. Do not describe local registration uniqueness as a cross-runtime purchase claim, and do not add an optional non-atomic claim and rely on it for payment safety.

The authoritative financial binding remains the purchase-to-intent seal from ADR 0014. The workflow neither duplicates nor weakens it. Permanent expiry, refusal or uncertainty is not repaired by choosing another workflow, purchase, source response, runtime or financial intent. No registration is deleted to recover liveness. A later exclusive pre-create workflow claim would need a separately specified shared atomic registration capability and its own recovery semantics.

Trusted composition selects the payment client and immutable template catalog. Workflow payloads cannot select a URL, credential, Python callable, authority implementation or provider. External activities require explicit exact `client.profile`, `scope_ref`, `principal_id` and `run_id` matches; missing profile metadata is not accepted through the existing fallback used by some test ports. The older `PaymentWorkflow` must also reject an external-profile client at construction/start/resume and before an effect, including programmatically supplied or subsequently changed clients.

The reference continues to assume trusted host configuration directs the client to the same logical payment ledger. An endpoint string or opaque hash cannot prove database identity. This slice does not add remote service enrollment or claim detection of a maliciously replaced ledger at the same endpoint. The host may change a local port or rotate a token while reconnecting to the same authorized ledger and principal; it may not silently redirect a registered workflow to another ledger. A deployment requiring independently verifiable ledger binding needs a separate service-identity contract. Runtime/backend binding, including Temporal endpoint/namespace/queue binding, retains its existing independent checks.

Replacing or decorating a client/template is permitted only when the implementation preserves the same registered semantic version, scope, profile, request construction and failure behavior. A changed semantic template requires a new version and cannot resume old registration. Retain historical template implementations while their workflows remain resumable. Unsupported or missing templates fail before a mutation; they never select a current default. Current merchant configuration, source freshness, keys and admission authority remain core checks, including after a worker implementation is replaced.

## Request preparation and command identity

Keep request preparation separate from scheduling and bounded decisions. A small trusted request-template policy, selected by the registered version/profile/source kind, validates the start and returns an immutable quote preparation. The activity validates that preparation against the registered profile and exact source before invoking the client. The fixture preparation uses the existing `quote`; the external preparation uses `quote_external` with intent, key, created version and the three source values. The policy can be injected or decorated without branching on a concrete client class. Registry lookup is closed and rejects duplicate registration or unsupported combinations. This is an application policy seam, not another vendor adapter. Unsupported templates, changed preparations and absent external quoting support must fail before `create`, not only when the workflow reaches `quote`.

The external client posts the exact existing five-member `quote-external` request: expected intent version, registered purchase UUID/revision/response UUID, and the existing trusted single synthetic tender. Credential references and tender configuration are reconstructed inside the trusted activity implementation and never enter start values, graph state or runtime history. The runtime cannot choose another tender. The quote response is reduced to the existing validated revision, digest and exact money projection, with matching intent identity.

Preserve the existing `command_key` algorithm for create, quote and execute. The source and retry count must not generate new financial command identities. The exact source and created intent version remain bound by registration and the staged activity envelope; attempts to reuse an accepted command with changed inputs receive the existing core conflict. Activity IDs remain derived from registered workflow identity, decision version, sequence and kind, with no transport attempt counter. Existing fixture workflow keys remain byte-for-byte unchanged.

Activities still create one external-profile intent, quote once, read current core status, wait for trusted approval, execute only the existing approved attempt, and read again. There is no graph approval/customer-action/revocation method. A wake or resume contains no trusted consent, amount, authority or outcome. AP2 verification and embedded ACP validation remain independent core gates.

## Restarts, lost replies and payment truth

| Boundary | Required recovery behavior |
| --- | --- |
| Registration accepted, start reply lost | Retain the same reference and fingerprint; apply existing attempted-start and backend-history confirmation rules. Never start a replacement after an uncertain attempt. |
| Create accepted, activity completion lost | Replay the exact create request/key and recover the original intent UUID. Never create from a new workflow identity as recovery. |
| External quote accepted, completion lost | Replay the original quote key and exact registered tuple/version. Core replay returns the original quote even if later status, freshness or configuration changed. Do not resolve a newer commerce response or require the original source to be currently quotable before replay. |
| Execute accepted, completion lost | Read current intent status and preserve its existing attempt/operation; retry only the original staged command where applicable. An accepted historical receipt is not current status. |
| Source changed or expired before quote acceptance | Keep registration and any existing DRAFT intent; report refusal. No refresh, replacement source or fixture quote is automatic. |
| Configuration/authority changed after admission | Preserve original operation and unresolved exposure. Continue authorized core status/reconciliation visibility; no source refresh or quote is needed to read existing payment truth. |
| Runtime pause, failure, history loss or activity limit | Retain source/intent/recovery references and existing recovery-required semantics. None implies financial cancellation, no-effect or permission to release exposure. |

Do not add a workflow-side prequote lifecycle GET that would block accepted-key replay after sealing, or reinterpret a timeout as rejection. The core owns exact-byte validation, original-source/configuration checks, freshness, atomic quote/seal publication, current admission, provider reconciliation and all reservation/effect transitions. Registration and checkpoint files remain orchestration storage; `inspect` stays explicitly labeled `authoritativePaymentState=false`. A reported remote `completed` state cannot produce local payment success. Only a validated core terminal observation ends the financial workflow.

Expected invalid source/template/profile/registration and access failures produce fixed nonretryable workflow failures. Unconfirmed transport or temporary service failure may retry only the original command/envelope. Preserve safe distinctions so a missing source/template or rejected registration is not converted into endlessly retryable generic unavailability. Do not serialize raw HTTP failure bodies, arbitrary exception messages or source documents. Existing core failure names and financial behavior remain unchanged.

## Runtime and compatibility boundary

SQLite retains one local driver, explicit resume and existing durable pause/activity staging. Temporal retains one scheduler history, bounded activity retry, durable polling and existing attempted-start protection. Both must validate the external template capability before first registration/start and use the same source-bound activities. A worker receives a source from the validated per-workflow envelope and registration; worker startup must not require one global purchase/source or manufacture an external `StartWorkflow` with a random workflow ID.

The deterministic Temporal workflow must not load a commerce service, template with credentials, registration database or HTTP client. It carries only closed safe start values and activity results. Existing old start values and histories must replay unchanged. A new external activity version may be dispatched through the existing activity wrapper only if that wrapper checks the exact supported version and preserves historical behavior; version labels cannot merely claim equivalence. Keep the current orchestration definition when its scheduling semantics are unchanged. A changed deterministic sequence requires explicit workflow versioning and replay evidence.

There is no automatic transition from a commerce runtime to a financial runtime. The handoff is an explicit start with immutable references after commerce observation. No guarantee of atomically completing commerce workflow history and starting payment history is needed: a missed financial start can be repeated under its retained identity, and an uncertain start follows the existing conservative recovery rule. This slice never calls merchant `/complete`, the provider-execution journal or a live connector.

## Acceptance evidence

1. Prove old start serialization and fingerprints remain exact, old registrations reopen, and actual native/legacy Temporal histories replay. Reject source/template/profile shape mismatches and unsupported runtime capabilities before scheduler/core work. Test the legacy graph directly, not only CLI choices.
2. Prove immutable source registration survives a fresh process and conflicts on every source field, profile/version, scope/principal and runtime drift. Lost create/quote replies must replay the original key/body and retain one intent/quote. A changed or missing injected external client profile and changed template selection must fail before HTTP. Direct/adapted/decorated clients and registration implementations obey the same contract.
3. Exercise real PostgreSQL, the authenticated Java API and the synthetic financial-ready ACP peer through commerce observation, durable external start, trusted approval, synthetic challenge, fresh process restart and success. Verify the selected external money, original purchase/intent/attempt/operation, one reservation and one simulator effect. Test two supported amounts/currencies without embedding a cart default.
4. Exercise accepted external quote followed by lost reply and restart, including expiry or configuration drift before replay. Check the same seal and quote result survive, while new approval/admission still respects current core policy. A changed observation, wrong owner, in-flight/unknown commerce command and permanently blocked source reject without financial execution.
5. Exercise provider response loss/UNKNOWN with restart and reconciliation of the original operation. Pauses, stale wakes, history/recovery failures and post-admission drift never create another intent/attempt/operation or release unresolved exposure. Test competing workflow identities for the same purchase: at most one quote/approval/charge; any losing DRAFT is not reported as a second financial owner.
6. For Temporal, kill an actual worker after accepted external quote before activity completion, restart it against the retained local server and registry, and replay the resulting history. Cover the external trusted-challenge journey and UNKNOWN recovery with retained payment identity. The existing native tests remain required; they do not substitute for the external path. Missing history retains source and payment recovery references and never causes another start.
7. Scan SQLite start/progress/recovery rows, Temporal raw and decoded payloads, errors, process diagnostics and emitted results for tokens, credential canaries, remote session identifiers, exact external bytes, signed artifacts and keys. Only the closed safe source tuple is added to the prior allowlist. Decorated activity outputs and malformed source values must be rejected before persistence/SDK serialization. Assert no merchant completion, source mutation or provider-journal execution.

Run targeted workflow/HTTP/process tests plus the documented PostgreSQL-backed aggregate conformance runner, documentation check and whitespace check. This remains a synthetic reference with `NOT_CERTIFIED`; no merchant authenticity, live provider, production availability or upstream certification claim is added.

## Consequences and alternatives

This adds durable selection and replay of one already-retained observation while reusing core authority and ownership. It permits controlled client/template replacement and both existing schedulers without a new financial transaction boundary. It deliberately leaves competing prequote DRAFT intents and conservative failed-history recovery as liveness limitations.

Rejected alternatives are using the latest commerce status on each quote retry, placing raw checkout bytes in graph state, adding the external profile to the legacy graph, treating source registration as consent, and adding a second payment executor. Requiring a global pre-create source claim would introduce another shared coordination protocol without improving the existing core's single-charge guarantee; it is outside this bounded slice.
