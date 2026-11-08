# ADR 0011: Durable external checkout lifecycle integration

## Status

Accepted for the bounded reference profile described below. See the platform guides and executable tests for current implementation limits.

## Context

`CheckoutSessions` can contact an external merchant, but the purchasing application cannot yet retain its request across restart or distinguish an unclaimed command from a possibly accepted request. Calling HTTP inside native checkout freeze/validate would hold the payment transaction and would incorrectly turn a remote lifecycle document into locally signed merchant authority. A journal-only demonstration would also leave the application and LangGraph unable to use the integration.

The next bounded profile must expose a real, durable create/retrieve/update/cancel journey while preserving one financial owner and the existing synthetic payment journey. ACP's pinned lifecycle contract does not give a universal idempotency-retention horizon or a lookup-by-idempotency endpoint. A database lease cannot stop a delayed process from sending after it loses that lease.

## Decision and bounded profile

Implement `durable-checkout-lifecycle-0.1` as a separate commerce aggregate, scoped by authenticated tenant, run, principal and caller-chosen purchase UUID. It uses the existing `CheckoutSessions` responsibility through an exact-request dispatch extension, and initially registers the existing ACP `2026-04-17` lifecycle profile. Implementations may provide the same behavior directly, adapt an external checkout implementation, or delegate through a wrapper. No domain or workflow caller selects a vendor implementation.

The aggregate is deliberately separate from `payment_intents`, `FrozenCheckout`, grant consumption, reservations, financial outboxes and payment operations. There is no payment completion, approval, AP2 mandate issuance or signing operation. A lifecycle `completed` response remains commerce evidence only. External bytes are never signed with the synthetic merchant key. Future admission of a merchant-signed checkout and exact approval invalidation requires its own reviewed binding profile; this slice fabricates no approval marker. The existing synthetic payment path remains intact.

### Stable responsibilities

| Port | Responsibility and required behavior |
| --- | --- |
| `CheckoutLifecycleTransactions` | Versioned atomic scoped command staging, idempotency replay, current configuration metadata, exclusive claim, command/outcome storage and fenced projection update. PostgreSQL is the initial adapter; a replacement must preserve these indivisible guarantees, not merely supply CRUD. |
| `CheckoutCommandTransport` | Pure preparation of an exact reconstructible request and a single application-level send of that prepared request. Dispatch cannot replace its origin, path, bytes, identities, profile or credentials. The ACP adapter implements this contract alongside the existing typed `CheckoutSessions` interface; direct standalone clients keep their current methods. |
| `CheckoutConfigurationResolver` | Trusted infrastructure resolution of an exact enrolled configuration identity to a transport with its original origin, protocol/serialization profile and credential version. Secrets never enter command, application DTO, database journal or workflow. Missing historical configuration or credentials fails closed. |
| `CheckoutLifecycle` / `CheckoutLifecycleService` | Versioned application-facing responsibility facade and its implementation. Application authorization and orchestration: prepare, stage, claim, send outside the transaction, re-resolve current configuration, and atomically record the correlated outcome. It owns retry/recovery policy independently of JDBC and HTTP. |

These are responsibilities, not per-backend interfaces. A second transaction adapter, a decorated transport and a different configuration source use the same contracts. Descriptor negotiation names contract versions, exact profile and operations. Unknown profiles/capabilities fail before any send. Existing JDK HTTP, Jackson, PostgreSQL and workflow dependencies suffice; no new library is needed.

### Configuration and immutable command

Trusted infrastructure enrolls configuration metadata into the transaction backend. Its scoped key is tenant/run/principal/merchant/configuration ID. Each immutable version records configuration version, credential version identifier, transport profile, encoding version and a fingerprint of non-secret origin/settings. Secret material remains only in the resolver. Reusing a version with different metadata is a conflict. Selecting a new current version is an explicit trusted infrastructure action, never a value learned from a merchant response or arbitrary agent URL. Restart may re-enroll the identical version without resetting history. The resolver must prove the exact metadata before it supplies a transport; matching a configuration name alone is insufficient.

Staging stores a protected immutable record containing:

- Tenant/run/principal/purchase and command UUID; local command key and request fingerprint.
- Operation, expected aggregate revision, enrolled merchant and exact configuration/credential versions.
- Exact transport profile/encoding version, operation path, method, content type, currency and known session identity.
- Original separately generated merchant idempotency key and request correlation ID; retrieve has no mutation key.
- Original bounded request bytes, including the absent-body distinction, and their digest. A digest alone is insufficient. The prepared command can reconstruct the exact request after process restart.

The command's outer representation and `toString` are redacted. No raw request bytes, item IDs, merchant session IDs, configuration fingerprint, origin, bearer credential or merchant idempotency key are copied to graph state, HTTP responses or safe reports. The existing body and path bounds remain in force. The transport validates its prepared request against its pinned encoding/profile and trusted configuration before send; a deserialized arbitrary path is not an escape hatch to `/complete`.

### Atomic stages, claims and outcome correlation

1. Authenticate and check required role, tenant/run/principal and allowed merchant before inspecting an existing command or returning replay. Fingerprint the immutable caller `StageCommand` independently of current configuration or newly generated transport identities. Authorized local-key replay checks this fingerprint and returns the historical command's current safe result before any configuration/credential resolution or expected-revision check. A changed request with the same key conflicts; it never creates another merchant key. For a new key, read the current scoped purchase/configuration, compare revision, prepare locally without network I/O, then recheck those same facts in the short atomic staging transaction. Historical command replay remains available after credential retirement, configuration rotation or later aggregate revisions, subject to current authorization.
2. Dispatch authenticates the same scope and allowed merchant again. In a short transaction, require `STAGED`, matching current aggregate revision/configuration, and no conflicting active command. Atomically store a random claim token and mark `CLAIMED` before returning the protected immutable request. Exactly one concurrent claimant succeeds. Database commit precedes any transport call.
3. Resolve only the original configuration/credential version and send the immutable request outside every database transaction and lock. There is no caller-controlled endpoint, replacement credential, fallback merchant, new key or financial execution path. A transport exception is sanitized and treated conservatively as an uncertain attempt; raw exception text is never returned.
4. Re-resolve configuration metadata without network I/O, then open a short transaction. Always correlate retained outcome with the original command, request digest and claim token. Store exact successful response bytes behind an opaque protected response reference and the bounded validated result projection. Recheck current configuration and aggregate revision before updating the current view. Changed configuration/revision or a revoked claim makes the response historical/stale evidence, not current checkout state. Every possibly sent stale mutation retains a permanent mutation barrier in this profile, even when its late result is `OK`. Historical evidence is retained rather than silently discarded.
5. Only an accepted current `OK` result promotes a known merchant session and increments the observation revision. Any changed exact response document changes its digest/revision; an unchanged document does not fabricate new authority. The view contains no approved checkout or financial state. No transaction is held while awaiting headers or body, including a stalled peer.

The transaction adapter checks scope in every command/outcome query and uses parameterized SQL. Its public use-case methods enforce authorization; possessing an opaque UUID is insufficient. Dispatch and status never disclose whether another principal owns the resource. Stored command replay is authorized against current roles and merchant access before returning the safe result.

Only one `STAGED`/`CLAIMED` command may be current per purchase; concurrent staging of another operation conflicts, including GET. Claim recovery transitions the old command out of that active set while retaining a separate unresolved-mutation barrier. Only then may a known-session retrieve stage; update/cancel/create remain blocked. GET failure/recovery never clears that barrier. The application validates transport result provenance for every implementation: `OK` requires `OBSERVED`, a document, matching original binding/currency/known session, and an allowed lifecycle status/version projection; other results have no document. Invalid/incoherent replacement results become fixed `INVALID_RESPONSE`/`UNKNOWN`, without promotion. A create may introduce a valid new session only for its original binding/currency. This validation is part of the service contract rather than an assumption about the ACP adapter.

### Crash, stalled worker and uncertainty policy

| Interruption or result | Durable behavior |
| --- | --- |
| Crash before staging commits | No accepted local command; caller retries the same local key and purchase. |
| Crash after staging, before claim | Restart dispatches the same staged command, original merchant key and bytes. |
| Crash after claim, before/after actual send | Claim remains possibly sent. Restart never automatically resends the mutation. An explicit recover-claim operation revokes the claim's projection fence and records `UNKNOWN`. |
| Worker stalls while another process recovers its claim | Original worker may still send; database fencing cannot prevent that. No replacement mutation is claimed. Its late result is retained as stale protected evidence and cannot silently clear uncertainty. |
| Mutation gets timeout, 5xx or invalid success data | `UNKNOWN`, with original identity retained and future mutations blocked. A transport's definite `NOT_SENT` result is recorded without claiming merchant acceptance, but does not create a new command or key automatically. |
| Known session after uncertainty | A newly staged retrieve may observe that same session through GET. It cannot prove the abandoned POST has stopped or clear the mutation fence; its result is an observation, not permission for another mutation. |
| Create loses response before session is known | `UNKNOWN` and `TRUSTED_RESOLUTION_REQUIRED`; there is no invented idempotency lookup, replacement create, new purchase identity as recovery, or unbounded same-key replay. |
| Current config/credential version changes before claim | Fail closed without sending; staged command retains its original identity and can never be rebound. |
| Config/revision changes during send | Persist correlated response as historical evidence, preserve uncertainty where applicable, and refuse promotion to the current projection. |

There is no time-based claim takeover or automatic mutation replay in this bounded profile, even with an unchanged key. ACP has no universal safe retention horizon. Expiry, a 404 or a successful GET does not establish a no-effect fence. Future negotiated replay/resolution capabilities require explicit review and cannot be enabled merely by renaming this profile. An uncertain purchase remains identifiable and blocked across service and workflow restarts. The APIs must not describe this as automatically resolving unknown-create outcomes.

### Application API and workflow integration

Expose versioned application responsibility `CheckoutLifecycle`, implemented by `CheckoutLifecycleService`; HTTP, workflow bridges and decorators depend on the interface. Its Java contract uses `PaymentService.Session` as the authenticated context:

```java
SafeView stage(Session session, UUID purchaseId, String localKey, StageCommand command);
SafeView status(Session session, UUID purchaseId);
SafeView dispatch(Session session, UUID purchaseId, UUID commandId);
SafeView recoverClaim(Session session, UUID purchaseId, UUID commandId);
```

The exact initial Java values are:

```java
String CONTRACT_VERSION = "checkout-lifecycle-0.1";
String PROFILE = "durable-checkout-lifecycle-0.1";
enum CommandState { STAGED, CLAIMED, RECORDED, UNKNOWN, STALE }
enum Recovery { DISPATCH_STAGED, IN_FLIGHT, RETRIEVE_ONLY,
                TRUSTED_RESOLUTION_REQUIRED, NONE }
record StageCommand(CheckoutSessions.Operation operation, int expectedRevision,
                    String merchantId, String configurationId,
                    String currency, List<String> itemIds) {}
record SafeView(String contractVersion, String profile, UUID runId, UUID purchaseId,
                UUID commandId, int revision, CheckoutSessions.Operation operation,
                CommandState commandState, String outcomeCode,
                CheckoutSessions.Observation observation, int httpStatus,
                boolean remoteSessionKnown, String remoteStatus,
                UUID responseReference, boolean mutationBlocked, Recovery recovery) {}
```

`outcomeCode` is drawn only from `CheckoutSessions.Code` names plus fixed `NONE`, `WORKER_INTERRUPTED`, `CONFIGURATION_CHANGED` and `STALE_OUTCOME`; peer text is never copied. Staged views use `NONE`, `NOT_SENT`, HTTP zero and null response/status. Claimed views use `NONE`, `UNKNOWN`, HTTP zero and null response/status because the durable claim may precede an actual send or already have sent. Claim recovery uses `WORKER_INTERRUPTED`, `UNKNOWN` and no fabricated peer result. A staged command has a durable revision; accepted staging and accepted outcomes advance the aggregate revision to fence concurrent decisions. Replays never replace its command ID. A mutating uncertain command sets a persistent mutation barrier independently of the latest GET command or lifecycle projection.

`StageCommand` contains `operation`, nonnegative `expectedRevision`, `merchantId`, `configurationId`, `currency` and bounded `itemIds`. Create requires revision zero and no existing purchase; later operations bind the stored merchant/configuration/session and require the current revision. Retrieve/cancel have no items. The request never carries an origin, secret, remote session ID, merchant key, raw bytes or arbitrary operation path. Stage/dispatch/recover-claim require `intent:write` and current merchant access; status requires `intent:read` and current merchant access. Purchase ownership is the authenticated principal, rather than a request field.

`SafeView` contains fixed contract/profile markers, run/purchase/command UUIDs, revision, operation, local command state, fixed outcome code/observation, original HTTP status, a `remoteSessionKnown` boolean, validated lifecycle status, opaque response UUID, `mutationBlocked` and fixed recovery enum. Recovery is one of `DISPATCH_STAGED`, `IN_FLIGHT`, `RETRIEVE_ONLY`, `TRUSTED_RESOLUTION_REQUIRED` or `NONE`. It excludes all protected fields listed above. Status reports the authoritative current command; the command ID binds any dispatch/recover-claim response. Fixed conflict and not-found errors use the existing sanitized application error format.

The loopback HTTP surface maps these methods to:

- `POST /checkout-purchases/{purchaseId}/commands`, using the existing local `Idempotency-Key` convention.
- `GET /checkout-purchases/{purchaseId}`.
- `POST /checkout-purchases/{purchaseId}/commands/{commandId}/dispatch`.
- `POST /checkout-purchases/{purchaseId}/commands/{commandId}/recover-claim`.

Registration is explicit trusted startup configuration; when absent, the external lifecycle capability fails explicitly. It does not change the existing payment endpoints or register an external merchant as a native signer.

LangGraph gets a `CheckoutPort` lifecycle client and `LocalCheckoutClient` with strict fixed-loopback/authentication and safe DTO validation. This is a separate commerce `SqliteSaver` local-restart/manual-resume workflow; the existing payment runtime is payment-specific and is not relabeled as commerce support. An injected compatible `BaseCheckpointSaver` can replace SQLite, but no Temporal commerce adapter is claimed.

One operation binds each workflow identity to its scope, purchase and protected input-template fingerprint before the first API effect. The immutable template (items/configuration) stays in a trusted closure/file outside checkpoints; tokens stay in the client closure. Checkpoints contain only safe IDs/status, fixed recovery values and the template fingerprint. Restart validates workflow/scope/principal/contract/template identity before port calls; changed identity/template fails closed. Purchase ID and local stage key are stable across replay. A client must reject unexpected command IDs rather than adopt another command from status.

An injected checkpointer alone cannot atomically bind concurrent workflow starts. Add responsibility `CheckoutWorkflowBindings.reserve(scope/run/workflowId, principal/templateFingerprint/purchase/contract)` with immutable compare-and-set registration before any lifecycle port call. Its first adapter is SQLite; a replacement backend implements the same responsibility. Store only safe identity/fingerprint and an immutable command ID once known. No reset/delete/rebind operation exists. Conflicting templates for one workflow fail atomically even before the first API effect. The existing payment-specific SQLite registration type is not reused by relabeling its semantics. Inject the bindings port alongside the checkpointer.

The journey stages the immutable command, reads authoritative status, dispatches only its own `STAGED` command and observes status again. `RECORDED`/`STALE` finish; `CLAIMED`/`UNKNOWN` interrupt with the fixed recovery reason. Restart never executes a possibly sent command again. An explicit CLI recovery command invokes `recoverClaim`, then reads/resumes; it cannot unblock mutations. An unknown create pauses with `TRUSTED_RESOLUTION_REQUIRED`; a known-session retrieve is a separate explicit workflow operation and remains observation-only. An HTTP response lost after successful outcome persistence is recovered by status or authorized local command replay, without another merchant send. The demo provides a user-runnable explicit synthetic loopback-peer/configuration launcher, disabled by default, and preserves existing payment startup behavior.

### Storage, migration and evidence

Add V008 tables for current/immutable configuration metadata, purchase projection, immutable lifecycle commands/claim state and protected command outcomes. Store them separately from financial tables and the existing signed-artifact backend: an ACP lifecycle response is not a signed artifact. Scope every key/FK by tenant/run/principal; bind commands and outcomes to purchase and exact immutable configuration identity. Retain request/response bytes in protected database columns with bounds and immutable identity constraints. Safe status exposes an opaque response reference only; no public raw-response endpoint is added. Deployment requires the same database access restrictions as other protected platform records. Rollback is additive and must not erase unresolved command history.

Required tests use real PostgreSQL and an independently authored loopback HTTP merchant peer:

- End-to-end create/retrieve/update/cancel through the application, actual immutable wire identity and protected response retention across service reconstruction.
- Concurrent staging/idempotency conflict, exclusive claims and stale fenced results; cross-tenant/run/principal/merchant refusal before replay or network I/O.
- Crash before claim, committed claim without send, accepted create with response loss, committed outcome with lost caller response, claim recovery and delayed original worker. Count peer requests and prove no replacement mutation or key.
- Known-session GET after uncertainty, unknown-create blocked recovery, 404/expiry not treated as no effect, credential/configuration rotation before claim and during a blocked send.
- Peer blocked while another database transaction progresses, demonstrating no network I/O under lock. No test relies on wall-clock lease expiry.
- A replacement transaction adapter/test double and decorated transport meet the same service contract; secret/request/response canaries stay out of safe DTOs, exceptions, representations and graph checkpoints.
- HTTP authorization/replay/concurrency plus a real LangGraph local-peer journey and process restart. Existing synthetic payment, native authority, provider SDK and durable runtime checks remain intact.

Validation includes Maven, the full unchanged-source conformance aggregate and documentation checks. Evidence names exact profile, source and run; local-peer success is not a vendor sandbox exchange or certification. Existing certification gaps remain open.

## Consequences and alternatives

The application gains usable durable external commerce operations with explicit conservative recovery. A crash after claim can sacrifice liveness: an unknown create remains blocked until a future trusted resolution profile exists. This is deliberate because neither a database fence nor an idempotency-key string proves a delayed external mutation is impossible.

Injecting HTTP into native freeze/validate, locally signing remote responses, lease-stealing unknown mutations, or treating GET/404 as an external send fence are rejected. Folding lifecycle documents into native payment intent state would require unresolved signed-artifact and authority semantics. Completion and provider execution remain separately reviewed responsibility contracts and integrations.
