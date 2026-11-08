# Provider execution contract 1

Version identifier: `provider-execution-1`. Status: bounded provider-neutral SDK contract and synthetic adapter-only behavioral suite. This profile does not enable financial dispatch or implement a vendor API. The accepted design is [ADR 0012](../../docs/adr/0012-provider-execution-contract.md).

The keywords MUST, MUST NOT, SHOULD and MAY state requirements for an implementation claiming this version. Interface compatibility alone does not establish behavioral conformance. A claim MUST identify the exact contract, profile, implementation revision, offer/selected-combination digests, suite revision, fixture provenance and evidence limitations. No result grants provider, AP2/ACP, PCI, regulatory or network certification.

## Composition and responsibilities

Java interfaces are in `io.agenticpayments.sdk.execution.v1`. They depend only on `java.base`. Implementations MAY implement the interfaces directly, adapt an external implementation or forward/decorate another implementation. Vendor identity belongs in configuration and offers, never in a new vendor-specific port. A decorator MUST preserve the original immutable binding, selection, financial certainty and evidence meaning.

| Interface | Required behavior |
| --- | --- |
| `ExecutionPreparer.prepare(operation)` | Validate and prepare the exact operation. It MUST NOT authorize, confirm, capture or otherwise initiate financial movement. A declared resource-creating preparation MAY create a nonfinancial external resource. |
| `FinancialSubmitter.submit(request)` | Submit one durably admitted operation and its originally bound prepared-resource association. It MUST NOT create replacement identities after uncertainty. |
| `ExecutionReconciler.reconcile(operation)` | Read evidence only. Lookup absence/unavailability MUST remain `UNKNOWN`; reconciliation MUST NOT submit. |
| `ProviderEventVerifier.verify(request)` | Verify authenticity, configured account, event/request binding and replay profile behind an opaque trusted-envelope reference. Emit a verified reference, not financial authority. |
| `ProviderEventNormalizer.normalize(operation, event)` | Resolve trusted provenance of a verified reference, then interpret its status under the pinned financial profile. A caller-constructed Java value MUST NOT establish authenticity. |
| `NoEffectFencer.fence(operation)` | Optional atomic exclusion against every admitted/delayed/future financial sender. Ordinary cancellation or current absence MUST NOT be advertised as this guarantee. |

The application remains responsible for durable spending authority, reservation/admission, outbox publication, one execution owner and atomic application of verified facts. Constructing an operation or calling negotiation does not grant authority. The new interfaces are not connected to the existing financial store in this slice.

## Immutable operations and scoped idempotency

`ExecutionIdentity` contains trusted tenant, merchant and run IDs; intent, attempt and operation IDs; and caller idempotency key. The durable namespace is exactly `(tenantId, merchantId, runId, callerIdempotencyKey)`. A provider/account/region change MUST NOT change that namespace and evade a conflict. Separate authenticated scopes MAY reuse caller-key text. Untrusted scope changes MUST be rejected before accessing another scope's journal.

`ExecutionBinding` binds provider ID, account reference, region, immutable configuration reference/version, credential reference/version and execution-owner ID. Trusted infrastructure resolves references. Implementations MUST retain the original configuration/credential resolver across recovery, or explicitly return unavailability/uncertainty. They MUST NOT substitute current defaults or another account/region/version. Secrets and raw payment/authentication payloads MUST NOT enter these values.

`ExecutionOperation` binds identity, binding, complete capability selection, exact money, purpose and its checked request fingerprint. An implementation MUST compare the complete immutable operation when a scoped key already exists, not merely a digest. Changed intent, attempt, operation, amount, currency, exponent, purpose, account, region, owner, configuration, credential or selection under that key MUST reject before another external activity.

`ExactMoney` uses an uppercase three-letter currency, explicit exponent 0..9 and integer minor units 1..999999999999999999. Canonical parsing accepts 1..18 decimal digits with no zero prefix, sign, whitespace, decimal point or exponent notation. The offer MUST explicitly permit the currency/exponent/range; a syntactically valid currency code does not imply support. No floating-point conversion is permitted. `AUTHORIZE` and `CHARGE` are separate purposes. This version excludes later capture operations, partial amounts, multiple capture, split tender and refunds.

Identifiers/references are bounded ASCII tokens matching `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`. They MUST be opaque, trusted synthetic-safe identifiers. Token syntax is not authorization. References MUST stay in trusted infrastructure, not public status/model/graph state. Reference-bearing diagnostics are redacted; validation messages MUST NOT echo rejected input. Sets are bounded to 32 immutable elements; offers and currency-limit lists are nonempty and bounded to 32; duplicate combinations and duplicate currencies within a combination are invalid.

## Offers and fail-closed selection

`ExecutionOffer` identifies contract version, profile version, provider ID, implementation revision and complete supported `Combination` tuples. Each tuple declares method, credential format, supported actions, purposes, preparation effect, optional capabilities and exact currency limits. Independent provider-wide lists MUST NOT be interpreted as a Cartesian product.

Preparation effects are `EFFECT_FREE` and `RESOURCE_CREATING`. Optional capabilities are `VERIFIED_EVENTS` and `ATOMIC_NO_EFFECT_FENCE`. Action types are `SYNTHETIC_CHALLENGE`, `REDIRECT` and `SDK_HANDOFF`; declaring an action type does not implement its trusted UI or prove human consent. Unknown required versions/capabilities/types MUST fail closed.

Trusted policy constructs `CapabilitySelection.Requirement` with exact contract/profile/provider/implementation revision, configured account/region, required method/credential/actions/purpose/effect/capabilities and money. `CapabilitySelection.select` MUST find exactly one complete permitted tuple. Zero or ambiguous matches are unsupported. Selection stores the entire selected tuple, offer digest and binding. Before activity, implementations MUST validate it against their trusted original offer/configuration, including configuration and credential versions. Selection is not financial admission.

An evidence run covers its exact selected tuple. Additional offered combinations require their own selected suite runs. An offer digest alone is not evidence that every declaration was exercised.

## Canonical fingerprints

All hashes below are SHA-256, lowercase hexadecimal prefixed `sha256:`. `H(fields)` encodes a list as a big-endian 32-bit element count followed by, for each string, its big-endian 32-bit UTF-8 byte length and exactly those bytes. There is no BOM, locale formatting, delimiter splitting or record `toString()` encoding. Numeric strings are canonical decimal integers. Enumerated sets are their exact enum names sorted lexicographically and encoded with `H`; empty sets use `H([])`.

| Value | Ordered fields passed to `H` |
| --- | --- |
| Currency limit | currency, exponent, minimum, maximum |
| Combination | `provider-execution-combination-1`, method, credential format, action-set hash, purpose-set hash, preparation-effect enum name, capability-set hash, followed by currency-limit hashes sorted lexicographically |
| Offer | `provider-execution-1`, profile, provider, implementation revision, followed by combination hashes sorted lexicographically |
| Binding | `provider-execution-binding-1`, provider, account reference, region, configuration reference, configuration version, credential reference, credential version, owner |
| Selection | contract, profile, implementation revision, offer digest, combination digest, binding digest |
| Operation | `provider-execution-operation-1`, tenant, merchant, run, intent, attempt, operation ID, caller key, binding digest, selection digest, currency, exponent, minor units, purpose enum name |
| Submission | `provider-execution-submission-1`, operation fingerprint, `present` or `absent`, resource reference or the empty string |

The SDK recomputes the operation fingerprint at construction. Implementations MUST reject mismatches and MUST preserve the original operation across retries/reconstruction. Domain-separated digests and full-value comparison are both required.

## Preparation, resource association and financial uncertainty

`PreparationResult` binds the exact operation and declared effect, status, optional prepared-resource reference and optional trusted action. Status is `READY`, `ACTION_REQUIRED`, `UNKNOWN` or `REFUSED`.

- An action descriptor MUST be present if and only if status is `ACTION_REQUIRED`.
- An `EFFECT_FREE` result MUST NOT carry an external-resource reference.
- Resource-creating `READY` MUST carry the original resource reference.
- `UNKNOWN` MUST NOT fabricate readiness or reconstruct a new resource from current absence. It MAY retain an already known original reference.
- Preparation refusal is not proof that no prior financial submission exists.

A resource-creation request identity MUST be persisted before preparation. If acceptance is followed by response loss, callers MUST retain original identity/resource uncertainty and MUST NOT create a replacement resource simply because the response was lost. Preparation MUST NOT hide financial confirmation/capture behind a nonfinancial label.

`SubmissionRequest` separately fingerprints and atomically binds the exact absent/present resource association under the same execution namespace before the first financial call. Resource-creating combinations require the original resolved prepared resource; effect-free combinations require absence. Resource swaps, cross-operation resource reuse and absent/present changes MUST reject before activity. An unresolved preparation is not admissible.

Durable caller idempotency MUST outlive a PSP's shorter key-retention period for the deployment's recovery/audit horizon. Once external acceptance may have occurred, an implementation MUST retain the original claim, reconcile or return uncertainty. Lookup absence, a 404, expired PSP key or transient error MUST NOT authorize another charge, replacement identity, account or region. A failure saying `NOT_STARTED` means only that invocation did not start; it does not prove all earlier/parallel senders had no effect.

## Evidence and safe errors

`ExecutionEvidence` binds the exact operation, evidence ID, optional provider-resource reference, source, observation time, state and optional action. Observation time/event delivery order is not a universal provider sequence.

| State | Financial interpretation |
| --- | --- |
| `UNKNOWN` | Acceptance/effect is unresolved. Preserve exposure. |
| `PENDING` | Processing accepted; financial completion unresolved. |
| `ACTION_REQUIRED` | A trusted action is required; no financial success is inferred. |
| `AUTHORIZED` | Exact full amount authorized for `AUTHORIZE`; not captured/settled. |
| `CAPTURED` | Exact full amount captured for `CHARGE`; not an independent bank-settlement claim. |
| `DEFINITIVELY_REFUSED` | Trustworthy definitive refusal with no financial acceptance for this operation. A transport failure/absence is insufficient. |
| `NO_EFFECT_FENCED` | A declared atomic no-future-send fence won before acceptance. |

An action MUST appear only with `ACTION_REQUIRED`, and MUST match the operation fingerprint, binding digest and declared action type. Authorization MUST NOT map to capture. Partial or unsupported financial results MUST NOT be coerced to success.

Captured/authorized/refused/fenced terminal facts MUST replay under identical resubmission without new financial acceptance. Conflicting financial evidence MUST remain reportable as a conflict. Same evidence ID with different content is `EVIDENCE_CONFLICT`. Unknown/pending observations MUST NOT erase established financial facts. Atomic application conflict/monotonicity rules require separate composed financial evidence; this suite does not test that application layer.

Sources are `SUBMIT`, `RECONCILIATION`, `VERIFIED_EVENT` and `FENCE`. A source enum is a trusted-port assertion, not an authenticity token. Applications MUST accept evidence only through configured responsibility implementations and exact binding checks. Caller-constructed `VERIFIED_EVENT` values MUST NOT bypass verification and normalization.

`ExecutionFailure` has a fixed safe code and certainty (`NOT_STARTED` or `MAY_HAVE_OCCURRED`), with no arbitrary message, provider payload, cause or stack trace. Codes are `UNSUPPORTED_CONTRACT`, `UNSUPPORTED_PROFILE`, `UNSUPPORTED_COMBINATION`, `INVALID_SELECTION`, `IDENTITY_CONFLICT`, `CONFIGURATION_UNAVAILABLE`, `PREPARATION_UNRESOLVED`, `TEMPORARILY_UNAVAILABLE`, `INVALID_EVENT`, `EVIDENCE_CONFLICT` and `CONTRACT_VIOLATION`. Raw exceptions MUST be sanitized at infrastructure boundaries. A timeout after possible acceptance MUST yield `UNKNOWN` or `MAY_HAVE_OCCURRED`, never definitive refusal. Retry advice does not itself authorize a resend.

## Events and trusted action

`EventVerificationRequest` contains an operation and opaque envelope reference. Trusted infrastructure retains raw bytes/headers, signature keys and secret resolution. Verification MUST check authenticity, configured account/configuration, event identity, operation, resource and money under the selected profile. Normalization MUST resolve an authentic verified reference from a trusted resolver; Java construction alone is insufficient. Forged, altered, wrong-account, unbound and conflicting duplicate events MUST fail safely; identical verified duplicates MUST be stable. Out-of-order normalization MUST preserve each event's original financial meaning.

`TrustedActionDescriptor` contains only action type, opaque reference, operation fingerprint, binding digest and expiry. It MUST NOT contain a URL, HTML, client secret, OTP, credential or arbitrary map. The SDK checks binding and expiry against a caller-supplied trusted clock; at the expiry instant an action is unusable. Trusted presentation/completion, current-state checking and allowed-origin enforcement belong to separate infrastructure. Redirect or user-interface completion MUST NOT become capture evidence or start a replacement charge. Actual trusted UI completion remains unevaluated here.

## Optional fencing and synthetic compatibility

Advertising `ATOMIC_NO_EFFECT_FENCE` requires an implemented `NoEffectFencer` with durable atomic exclusion against every delayed/future financial sender. If acceptance already won, it MUST return known accepted evidence or uncertainty. A winning fence MUST replay and prevent sends after reconstruction. Providers without that guarantee MAY pass the base contract with fence explicitly unsupported; ordinary absence/cancellation MUST NOT be promoted to fencing.

`compat.LocalFakeExecutionAdapter` wraps the original synthetic `PaymentAdapter` without widening the original SDK or changing current financial callers. It supports only `local-fake-execution-1`, synthetic method/reference/action, EUR/USD exponent two with existing local limits, `CHARGE` and effect-free preparation. It maps existing success to capture, pending to pending and local journal-backed no effect to the explicit fence guarantee. It offers no event-verification implementation.

The bridge requires injected `CompatibilityBindingJournal`: atomic durable binding of full new operation plus original legacy identity/key/scenario; complete submission-resource association; at-most-once initial dispatch claim; terminal-evidence preservation/conflict detection. Publishing a claim before a delegate failure MUST preserve uncertainty. Concurrent calls MUST NOT choose different mappings. No production journal backend is supplied here. Retained-memory test journals demonstrate in-process adapter reconstruction only, not OS/process crash durability. Before financial wiring, a durable backend and separately reviewed integration evidence are required.

## Reusable behavioral suite

Suite revision: `provider-execution-tck-1`. `tck.ExecutionTck` chooses every pinned case; an adapter cannot select scenarios, omit cases or declare its own pass. Fixture controls and observations are trusted independent test infrastructure. Missing controls/observations and unexpected exceptions produce fixed safe `INCOMPLETE` evidence; assertion violations produce `FAIL`. Optional undeclared events/fencing are `UNSUPPORTED`; advertised capabilities without executable evidence cannot pass.

| Case | Executable boundary evidence |
| --- | --- |
| `PE-CAPABILITY` | Exact selection; unknown version/profile/method/credential/action and unsupported money reject before external effects. |
| `PE-IDENTITY` | One acceptance on duplicate/reconstruction; bound identity/configuration/credential/money/purpose/selection mutations reject; resource swap/reuse rejects; unauthorized scope mutation differs from separately authenticated scoped key reuse. |
| `PE-PREPARE` | Effect classification, no financial send, exact operation/effect and independently observed original resource association, lost resource-creation response and reconstruction. |
| `PE-UNCERTAINTY` | Accepted response loss, original durable claim after in-process reconstruction, observed provider-key expiry, absent/unavailable lookup, no repeated financial send. |
| `PE-RECONCILE` | Read-only absence, pending, definitive refusal/fence replay, capture and authorization when supported. |
| `PE-EVENT` | Conditional synthetic verification/normalization, wrong/forged/altered/unbound/conflicting duplicate rejection, stable duplicates and original weaker-event meaning. |
| `PE-ACTION` | Opaque descriptor structure, exact binding and expiry checks, no financial effect; excludes actual UI completion. |
| `PE-SAFE-FAILURE` | Injected sensitive canary exceptions cannot escape into results/reports; effect uncertainty remains explicit. |
| `PE-FENCE` | Conditional admitted sender paused immediately before financial send, winning fence, resumed sender with zero observed sends, replay/reconstruction, accepted-first result and simultaneous submit/fence exclusivity. |

The included tests run independently authored map-peer and append-list-peer adapters, an original-SDK compatibility fixture and a forwarding decorator. Independent peer ledgers/counters expose extra financial effects; a broken delayed sender fails even when the peer refuses its forbidden transmission. A non-first, authorization-only selected tuple is exercised without assuming every implementation supports charging. Broken decorators and missing evidence must fail or remain incomplete. Test controls live outside public execution operations. Reports pin the exact offer/selected-combination digest and distinguish `IN_PROCESS_RECONSTRUCTION` from separately supplied `PROCESS_PERSISTENCE` evidence. The supplied fixtures claim only the former.

This is adapter-only synthetic evidence. Provider wire versions/sandbox accounts, durable process-crash storage, application admission/outbox atomicity, event transport, trusted human consent and whole-platform conformance remain separate obligations. Existing `0.1.0-draft` schemas, input locks and SDK/simulator behavior are unchanged.
