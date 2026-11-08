# Review 0030: Provider execution contract design

Status: independent design review completed, 2026-11-08. Recommend integration-owner acceptance of the exact ADR identity below for bounded implementation. No blocking design finding remains. This is not source acceptance, financial integration approval or provider interoperability evidence.

Reviewer: independent architecture/protocol reviewer, distinct from the author. Baseline: `b3544493cd6dd81ce861e73dc38a82054747618a`. The assessment covered repository/workspace instructions, README/status/architecture, ADR 0009/0010, the provider evolution roadmap, existing executable SDK/descriptor/TCK contracts and the proposed ADR 0012. The reviewer owns this report and review 0029 only; no production source or tests were changed and no Maven or conformance tests were run.

## Scope and architecture decision

The additive `io.agenticpayments.sdk.execution.v1` contract is an appropriate boundary for providers whose semantics cannot honestly satisfy the existing synthetic `PaymentAdapter`. Preparation, financial submission, reconciliation, event verification, event normalization and optional atomic no-effect fencing have separate responsibilities. Concrete implementations and forwarding wrappers use the same interfaces. The existing synthetic SDK, core execution path, scenarios, persisted JSON and original TCK remain unchanged.

The slice includes portable Java values and selectors, a reusable executable behavioral TCK, two independent synthetic fixture implementations, a forwarding decorator and an explicit local-fake compatibility bridge. A new `specification/provider-execution-1/README.md` records the same versioned contract/profile/TCK semantics without changing the existing 0.1 specification or schemas. Its `java.base` dependency restriction preserves SDK portability. It adds no network provider, database admission path, sandbox activation or public package release. The parallel checkout slice cannot become an indirect financial execution path through this contract.

## Findings resolved before acceptance

| Concern | Accepted disposition |
| --- | --- |
| A caller-key rule without an exact namespace could either conflate tenants or allow account changes to evade conflict checks. | The namespace is tenant/merchant/run/key; account, region, intent, attempt, operation and configuration remain immutable values. Trusted scope validation occurs before journal access. Legitimate separately authenticated scope reuse is distinct from unauthorized scope mutation. |
| Optional prepared-resource input was not covered by the operation fingerprint. | A separately fingerprinted immutable submission request durably binds the exact absent/present resource association before activity. Resource swaps and cross-operation reuse reject. The operation identity remains stable through preparation. |
| Preparation status, action and resource fields could imply inconsistent readiness. | Cross-field invariants require an action exactly for ACTION_REQUIRED, no resource for effect-free preparation, and the original resource for resource-creating READY. UNKNOWN cannot invent readiness or an unknown resource. |
| An action TCK could claim to exercise UI completion although no such port exists. | The executable obligation is descriptor shape, binding and expiry checking. Trusted UI completion, redirect provenance and actual presentation remain named composed exclusions. |
| Retained in-memory test journals could be represented as durable restart evidence. | Reports distinguish in-process reconstruction from process/storage persistence. This slice supplies only reconstruction evidence; financial wiring requires real durable atomic-journal and process-crash evidence. |
| A constructed event/evidence value could be mistaken for verified provenance. | Event normalization requires trusted reference resolution. Evidence source is a trusted-port assertion, not an authentication token or a caller-supplied admission proof. |

Reference-bearing values must redact diagnostics even when their grammar is bounded: account and credential aliases can still be sensitive. Definitive refusal and winning-fence facts are terminal for submission of the immutable key; identical replay must not later initiate acceptance, while contradictory financial evidence remains visible as conflict.

## Required implementation invariants

Canonical operation identity must cover every declared identity, binding, selection, exact-money and purpose field using the pinned framed encoding. The offer digest must cover every executable declaration. Constructor/factory checks recompute fingerprints; duplicate handling compares complete immutable values, not only supplied digests. Selection validates complete supported tuples, exact profile/revision, configured account/region and money limits before activity. Unknown combinations fail closed and cannot acquire support through metadata alone.

Preparation may create a declared nonfinancial resource but never authorizes, confirms or captures. Resource creation has its own retained uncertainty and identity. A financial invocation requires separately durable caller admission of the exact operation and submission binding. Neither the SDK values nor successful negotiation provide that admission. Configuration or credential retirement cannot redirect an in-flight operation to current defaults.

Financial evidence preserves UNKNOWN, PENDING, ACTION_REQUIRED, AUTHORIZED, CAPTURED, definitive refusal and a genuine winning fence as distinct meanings. Authorization is not capture or settlement. Lookup absence, expired deduplication, missing events and transport failures cannot release exposure or justify a new send. NOT_STARTED describes one invocation, not all prior or concurrent activity. Raw exceptions and provider payloads cannot escape through failures, diagnostics or TCK reports.

The optional fencer must race against every delayed/future sender for that operation; a cancellation response, absence query or local claim token is insufficient. Implementations without the guarantee remain usable through the base interfaces and report the extension unsupported. Event authentication and status interpretation stay separate; normalization preserves the meaning of out-of-order facts rather than inventing a universal status ordering. Atomic application conflict handling remains outside this adapter TCK.

The compatibility bridge requires an injected atomic binding journal covering the complete new operation, submission association and original legacy identity/key/scenario before delegation. It cannot silently discard new account/configuration/credential fields because the legacy operation omits them. Concurrent calls must select one legacy mapping. Failure after journal publication retains uncertainty. The bridge advertises only its explicit synthetic profile and cannot fabricate vendor event authenticity.

## Evidence and acceptance boundary

The shared TCK must choose mandatory cases and mutations itself, use independent observations/fault controls, and report missing controls as incomplete rather than skipping them. Tests must establish fixture independence rather than run two wrappers over the same implementation and call them independent providers. Negative implementations that rebind accounts/resources, replay uncertain sends, overclaim fences or leak exception text must fail the suite. Conditional capabilities require executable evidence when advertised.

Source review will inspect the actual canonicalization, immutable collections, submission-resource provenance, compatibility-journal races, result validation, safe failure behavior and case coverage. Focused tests and SDK bytecode inspection are necessary but do not replace the complete unchanged-source PostgreSQL aggregate owned by the integration owner. Passing this TCK does not certify a provider, AP2/ACP integration, the platform, PCI compliance or settlement behavior.

| Reviewed artifact | SHA-256 |
| --- | --- |
| [ADR 0012](../adr/0012-provider-execution-contract.md) | `dcd1880adbbb4b376598506638aa91f246c801bcd6bae2b87596e6b78d186180` |

Accept this exact bounded design for implementation followed by independent source review. Changed design identities require further review. Durable production journals, provider-specific profiles, sandbox evidence and financial wiring remain separate prerequisites.
