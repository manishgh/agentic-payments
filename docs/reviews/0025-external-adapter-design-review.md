# Review 0025: External checkout transport design

Status: independent design review completed, 2026-11-08. Recommend integration-owner acceptance of ADR 0009 at the exact identity below. No blocking design finding remains. Provider evolution is reviewed as a proposed roadmap only; implementation/source acceptance and real-peer evidence are separate gates.

Reviewer: independent architecture/protocol reviewer, distinct from the ADR author. Baseline: `7b5d695eb83646a8bef540f2d0ba551001e1fd50`. Assessment covered repository/workspace instructions, README/status/architecture, ADR 0008 and durable composition, current composition and executable payment SDK contracts, JDBC transaction/native call sites, the pinned ACP OpenAPI/JSON Schema and the provider evolution guide. The reviewer owns this report only and has changed no production source or tests.

## Scope and architecture decision

The accepted slice is a standalone, responsibility-based checkout lifecycle contract with an outbound ACP `2026-04-17` HTTP implementation for create/retrieve/update/cancel. An independently authored loopback peer exercises actual wire traffic. There is no completion method, arbitrary-path escape hatch, provider send, AP2 issuance or financial integration. The same contract can support alternative/decorated implementations without introducing vendor-specific domain ports.

This separation is necessary: existing native freeze/validate calls execute inside `JdbcPaymentStore` while the scoped run row is locked. Adding HTTP there would couple external availability to financial serialization and misrepresent remote checkout as locally signed authority. The adapter must remain outside native registration, advertise neither `local-freeze` nor a financial execution owner, and leave default launchers unchanged. Later application wiring requires a durable staged-command/outside-lock dispatch/recheck design with immutable identities and current revision/scope validation.

The design delivers useful transport behavior without claiming a complete external purchasing workflow. Raw checkout documents are protected evidence, not AP2 signed artifacts, verified authority or provider facts. Local synthetic keys cannot stand in for an external merchant's signed checkout. Merchant-owned completion and platform-owned PSP execution require distinct, explicit sole-owner profiles before either is enabled.

## Independent contract checks

The reviewer independently recomputed byte length, SHA-256 and Git blob SHA-1 for the cached locked ACP OpenAPI and checkout JSON Schema; all six comparisons matched the existing source lock. The selected source is commit `7fdd78df677a94dce04c770644b0fbbb1401272b`, dated profile `2026-04-17`.

The reviewed schema requires create `line_items`, `currency` and `capabilities`. Its closed `Item` permits `id`, `name` and `unit_amount`, with no `quantity`; obsolete `items`/`product_id` examples cannot override that schema. Checkout `totals` is an array despite an object-shaped example. Required session envelope fields, lifecycle enum, optional protocol metadata, POST key requirements, paths/statuses and cancel's optional body were checked. The bounded item-reference requests and absent cancel body are consistent with this selected profile. This does not establish that an actual hosted peer accepts that version.

Runtime validation deliberately covers the strict bounded envelope/projection, not the complete nested schema. This limit is acceptable only while unvalidated nested amounts, handlers, URLs, action metadata and extensions remain opaque and non-actionable. Independent Python validation must inspect the actual positive loopback captures against the full hash-verified schema without remote reference resolution. Current-stage execution identity, exact four operations/methods/paths/statuses and missing/stale capture rejection bind this evidence to a fresh run. Manually retyped fixtures cannot substitute for wire capture.

## Findings resolved and implementation constraints

| Concern | Accepted disposition |
| --- | --- |
| A response-header allowlist could copy arbitrary peer-supplied secrets into safe diagnostics. | Echoed request/idempotency IDs are exposed only if equal to the trusted sent IDs; missing, foreign, duplicate/conflicting or malformed values use fixed indicators or remain protected. Hostile-header canary tests are required. |
| A request timeout can cover headers while leaving an unbounded body read alive. | The deadline covers connection, headers, body and processing; body limits cancel/close outstanding work. Tests separately stall before headers and after flushed headers, including trickle behavior. |
| Absence of an application retry loop does not establish one HTTP attempt. | Peer request counts must demonstrate no underlying JDK resend after response loss/stall. Explicit caller retry preserves the original command/body/key. |
| Local peer schema success could be overstated as vendor or financial interoperability. | Claims are limited to the exact standalone lifecycle profile and independently authored peer; actual partner/version agreement, durable platform recovery and sandbox exchanges remain separate evidence. |

Strict UTF-8, duplicate/trailing JSON rejection, structural/body bounds, exact successful status, session/currency/version binding and fixed error mapping remain mandatory. Invalid/error bodies must not erase an observed numeric HTTP status. Safe metadata cannot include raw peer messages, credentials, cookies or authentication challenges. HTTPS/configuration binding, explicit synthetic loopback mode, fixed origin/path handling, no redirects/proxy surprises or returned-link fetches and zero-request scope mismatch tests are required.

Create/update/cancel response loss, 5xx and malformed post-send response retain commerce uncertainty. Retrieve 404 and cancel 405 do not prove financial no-effect. No error permits another owner, credential, endpoint, version or replacement key. The adapter has no durable journal; same-command peer replay tests prove wire behavior only. Eventual application integration must retain protected reconstructible request bytes and durable identity beyond unknown provider retention windows.

## Provider roadmap assessment

The current `ChargeOperation` scenario catalog, `CapabilityOffer` combinations and mandatory simulator `sealNoEffect` semantics prevent an honest real-provider implementation without reviewed executable-contract evolution. The guide identifies that engineering prerequisite rather than treating missing credentials as a blocker to local design and wire tests. Keep one versioned responsibility contract across provider implementations, optional no-effect fencing only when an adapter actually provides it, explicit action/method/account/region negotiation and durable admission/outbox/reconciliation.

The reviewer independently checked the linked primary provider pages. Stripe API v1 documents persisted first execution results and possible key pruning after 24 hours, supporting the need for durable platform identity beyond provider retention. Adyen documents company-account scope, lack of cross-region deduplication and its `transient-error` guidance. [Stripe idempotency](https://docs.stripe.com/api/idempotent_requests), [Adyen idempotency](https://docs.adyen.com/development-resources/api-idempotency/). These contextual observations do not pin a provider execution profile or authorize sandbox calls. The guide's staged contract/TCK, independent peer, explicitly configured sandbox and separately reviewed financial wiring sequence is accepted as proposed planning guidance.

## Evidence and decision

Source review must confirm one responsibility-based API, actual independent peer assertions, redacted values, no native/payment-store wiring, body cancellation, one-attempt counts and failure/uncertainty semantics. Root owns Java tests, fresh full-schema capture verification, documentation checks and complete isolated-PostgreSQL conformance. This reviewer ran source hash/blob checks and `git diff --check`; no Maven, application or conformance tests were run for this design assessment.

| Reviewed artifact | SHA-256 |
| --- | --- |
| [ADR 0009](../adr/0009-external-checkout-adapters.md) | `47c5ccf2fbb57479d4686580f00a8b33865e7d3cd9960aa1fcad1f2fc1bcda17` |
| [Provider evolution roadmap](../provider-adapter-evolution.md) | `b4c2b8da564cbb0cdfd992f90c00009b0bef5684ed5f91765b0900b3dd91dd1b` |

Accept ADR 0009 for bounded implementation followed by independent source review. Preserve the reviewed identities; changed designs require review. No external merchant/provider, financial integration or certification claim is accepted by this report.

## Scoped transport feasibility amendment, 2026-11-08

The first accepted text required a universal one-physical-request property that the selected JDK client cannot provide through a public per-client switch. Before transport implementation, the author identified this limitation; the integration owner authorized a bounded amendment and this reviewer independently inspected the pinned local JDK `MultiExchange`, `ExchangeImpl`, `Http1Exchange` and `Http1AsyncReceiver` sources. They permit safe-method/connection retries and classify some HTTP/2 responses as unprocessed, while caching process-level retry settings.

The amended decision is accepted: one application `sendAsync` invocation, no application automatic retries, HTTP/1.1 for the supported surface, and any permitted library attempts constrained to the identical origin, credential, method, path, body and idempotency identity within one total deadline. There is no universal one-physical-request claim. The supported launch configuration excludes all-method retries; construction rejects a currently enabled flag without mutating global state or pretending to inspect a previously cached JDK value. This is acceptable for the standalone, idempotency-keyed, nonfinancial lifecycle adapter. It grants no financial retry/no-effect authority.

Evidence must record one peer-observed POST after accepted-response loss before an explicit caller replay in the supported configuration. A separate dropped-GET case may observe library repeats and must verify identical request values. Header/body stall tests still prove bounded cancellation and preserved uncertainty. These revised obligations supersede this report's earlier universal no-underlying-resend/one-attempt wording; all other boundary, privacy, schema and source-review requirements remain.

| Amended reviewed artifact | SHA-256 |
| --- | --- |
| [ADR 0009](../adr/0009-external-checkout-adapters.md) | `c6dc291503bfb72ad37fef991e898add1f95a2a0a98b459758a8b7a3aa6837cc` |

The original hash above remains the historical initial review identity. The amended hash is the accepted implementation design.
