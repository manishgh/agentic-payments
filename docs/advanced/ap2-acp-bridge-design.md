# Proposed AP2 authority around ACP checkout

[Documentation](../README.md) / [Advanced](README.md)
Status: source-pinned design candidate, 2026-11-06. The bridge is disabled and has no signature, provider, interoperability or hosted-product acceptance evidence. Production protocol/security review remains required. The platform PaymentIntent remains the enclosing project aggregate: AP2 authority governs the purchase, ACP supplies the embedded merchant checkout, and provider authentication remains part of execution. Neither upstream standard defines this project's aggregate or an official AP2-to-ACP relationship.

## Exact source baseline

The [source lock](../../integrations/upstream/source-lock.json) identifies 53 inspected artifacts by repository, immutable commit/path URL, Git blob SHA-1, decoded-byte SHA-256, length, version and upstream license. Every downloaded artifact's Git blob identity matched locally. The repository retains metadata and self-authored synthetic probes; inspected upstream source bytes live only in ignored `.tools/upstream-research`.

| Source | Selected revision | Important SHA-256 |
| --- | --- | --- |
| [AP2 v0.2.0](https://github.com/google-agentic-commerce/AP2/tree/b4587ac1d055888a73b4b21750973cffba961793) | Tag `v0.2.0` resolves to `b4587ac1d055888a73b4b21750973cffba961793` | Specification `32c3be5011f481d2760e56e7b9935b0842c3da0d5f7d7b8a68402a599f1e6aa3` |
| [AP2 Checkout Mandate schema](https://github.com/google-agentic-commerce/AP2/blob/b4587ac1d055888a73b4b21750973cffba961793/code/sdk/schemas/ap2/checkout_mandate.json) | Same tag target; `mandate.checkout.1` | `10c0341edfeaa9084d3704ef8e94869de20499c8e357068d65f8d622bf79483a` |
| [AP2 Payment Mandate schema](https://github.com/google-agentic-commerce/AP2/blob/b4587ac1d055888a73b4b21750973cffba961793/code/sdk/schemas/ap2/payment_mandate.json) | Same tag target; `mandate.payment.1` | `94c4af64ed29825cb956705ae763d42f3c04d22feb60b8d838dae2bb1eea1fb1` |
| [ACP checkout schema](https://github.com/agentic-commerce-protocol/agentic-commerce-protocol/blob/7fdd78df677a94dce04c770644b0fbbb1401272b/spec/2026-04-17/json-schema/schema.agentic_checkout.json) | Released directory `2026-04-17` at commit `7fdd78df677a94dce04c770644b0fbbb1401272b` | `d0e4290617d66bf05d002b8ace388732be2b3eb9a92a1003db7a2daa1e0436f2` |
| [ACP delegated authentication schema](https://github.com/agentic-commerce-protocol/agentic-commerce-protocol/blob/7fdd78df677a94dce04c770644b0fbbb1401272b/spec/2026-04-17/json-schema/schema.delegate_authentication.json) | Same released directory/commit | `e550e881594748cb9f69a68e1c5710efa326ed6d4ee5ec8a19330ef86eaa5b52` |
| [ACP delegated payment schema](https://github.com/agentic-commerce-protocol/agentic-commerce-protocol/blob/7fdd78df677a94dce04c770644b0fbbb1401272b/spec/2026-04-17/json-schema/schema.delegate_payment.json) | Same released directory/commit | `307739ae400e7368eaa25c6024d30751348a4af8a516d071565fcd8aa328cf4d` |

The ACP [README](https://github.com/agentic-commerce-protocol/agentic-commerce-protocol/blob/7fdd78df677a94dce04c770644b0fbbb1401272b/README.md) identifies OpenAI and Stripe as maintainers and `2026-04-17` as its latest stable snapshot. This is a dated directory at the observed commit, not an invented release tag. `spec/unreleased` is excluded. AP2 main was observed at `e1ea56db72a6385bce3e5c1112b3a56ce60acb43`; the selected dependency is its tagged release instead.

The mutable [OpenAI checkout page](https://developers.openai.com/commerce/specs/checkout) still describes a narrower provider/method/status surface than these newer repository schemas. The latter include handler declarations, `authentication_required`, authentication results and continuation fields. Therefore the earlier repository-wide statement that ACP only represents three PSP names/card/no continuation is too broad for `2026-04-17`. Conversely, a public repository feature does not establish that a particular ChatGPT partner deployment negotiates it. Require the actual peer's version and integration acceptance evidence. The page states that the open protocol is available to builders while Instant Checkout in ChatGPT requires approved participation.

Both inspected repository licenses are Apache-2.0, pinned as separate license artifacts. This records upstream terms; it does not select this project's public license, grant trademarks, or establish commercial access. Any later vendoring must retain applicable upstream license/notice obligations.

## Proposed field and byte binding

The following is a proposed negotiated profile, not a native upstream mapping. Its [disabled constraints descriptor](../../integrations/upstream/bridge-profile.constraints.json) advertises no supported real combination. Start with human-present consent, one full tender, EUR/USD and no FX. Autonomous delegation, split funding and refunds require separate designs.

| Project concept | Upstream field or artifact | Required bridge rule |
| --- | --- | --- |
| Intent, attempt, operation and execution owner | No equivalent native AP2 aggregate | Keep project IDs and owner in durable internal records. Do not place an intent UUID in AP2 `transaction_id`. |
| Merchant checkout identity | ACP `CheckoutSession.id`, `protocol.version`, authenticated merchant origin | Bind seller onboarding/key identity and exact version. A session ID alone is insufficient because checkout updates can change its contents. |
| Checkout revision | Internal immutable revision plus exact signed JWT reference | Record every merchant-approved snapshot; any material item, total, currency, fulfillment, handler or payee change invalidates earlier consent. Re-signing changes the signed-artifact hash even if payload values match. |
| Commerce content | Merchant-signed `checkout_jwt` containing the pinned ACP session | Negotiate AP2's Checkout Object extension. Candidate payload is the unchanged ACP object; the base ACP model does not gain invented AP2 fields. Preserve its original signed representation. |
| Checkout authorization | Closed AP2 `vct=mandate.checkout.1`, `checkout_jwt`, `checkout_hash` | Verify trust and mandate chain, then compare the approved checkout artifact to the merchant's current frozen artifact. |
| Payment authorization | Closed AP2 `vct=mandate.payment.1`, `transaction_id`, `payee`, `payment_amount`, `payment_instrument` | `transaction_id` binds the checkout-JWT hash. Verify exact payee, amount/currency and instrument, independently of API identity and customer authentication. |
| Money | ACP `totals[]` entry of type `total`; AP2 `payment_amount.amount/currency` | Validate precisely one authoritative final total, exact integer units and exponent. Convert project decimal strings to bounded integers without floating point; apply an explicit case conversion only after currency validation. Reject FX and ambiguous totals. |
| Payment route | ACP `capabilities.payment.handlers[]`; complete `payment_data.handler_id` and `instrument` | Freeze the selected handler instance, its version, PSP/account/configuration and allowed instrument schema. An open PSP string does not authorize an unconfigured connector or arbitrary schema retrieval. |
| Delegated credential | ACP allowance `merchant_id,checkout_session_id,max_amount,currency,expires_at`; returned token | Credential Provider must also enforce the verified AP2 binding. Session/allowance fields alone do not encode the complete AP2 checkout hash. Keep that association behind an authenticated sidecar/vault boundary. |
| Authentication | Session `authentication_metadata`; completion `authentication_result`; separate authentication session | Bind the trusted authentication provider/session, acquirer, amount, merchant, instrument and attempt. Verify the server result, not an agent-supplied outcome string or browser return. |
| Order outcome | ACP completed session plus `order.id` and merchant events | Preserve merchant order evidence independently from payment authorization/capture/settlement facts. |
| AP2 receipts | Signed Checkout/Payment Receipt `reference` over the relevant closed mandate | Retain exact compact mandates, disclosures and receipts in protected evidence storage. Verify signer, reference, status and correlation; graph sees opaque references and safe summaries. |

AP2 hashes the value of the serialized `checkout_jwt`, using the SD-JWT hash algorithm or SHA-256 as specified; it does not hash the project's 402-byte local checkout fixture or a reserialized ACP map. The proposed profile requires SHA-256 consistently, exact bytes and a base64url result without a `sha256:` prefix. The selected AP2 [specification](https://github.com/google-agentic-commerce/AP2/blob/b4587ac1d055888a73b4b21750973cffba961793/docs/ap2/specification.md) also requires a nondeterministic merchant checkout signature and excludes deterministic Ed25519 for that artifact. The precise JOSE/SD-JWT/delegation revisions, representation of the `checkout_jwt` string, algorithms, test vectors and signing-library behavior need separate cryptographic review before code. No cryptographic implementation is authorized by the constraints file.

There is no native AP2 mandate member in the selected ACP complete request. The proposed transport is a separate authenticated sidecar keyed by merchant/session/revision/signed-artifact digest, checked atomically by the merchant completion path. An alternative negotiated ACP extension must define its name, schema composition, signed bytes and response behavior explicitly. Arbitrary metadata is not a substitute for authenticated mandate transport. If the peer supports neither path, this composition remains unsupported.

## Trust and execution responsibilities

| AP2 role | Proposed accountable component | Evidence required before enabling |
| --- | --- | --- |
| Shopping Agent | LangGraph orchestration plus deterministic authority client | The model may suggest purchases; deterministic code validates constraints, creates bounded requests and prevents overlapping authorization use. |
| Trusted Surface | Enrolled user wallet or separately trusted deterministic consent UI | User authentication, informed display of both mandates, signing authority and a verifier-recognized user credential or trusted Agent Provider. A local-fake UI is insufficient. |
| Merchant | ACP merchant backend | Registered merchant/signing keys, authoritative checkout revision, verified Checkout Mandate and signed Checkout Receipt. |
| Credential Provider | Enrolled vault/wallet or delegated service | Payment Mandate verification, instrument ownership, transaction-scoped credential release and receipt handling. A token vault alone is not proof of the AP2 role. |
| Merchant Payment Processor | Merchant's execution service/provider or an explicitly delegated core | Credential-to-checkout verification, authenticated payment facts and signed Payment Receipt responsibility. |

The [AP2 agent-authorization rules](https://github.com/google-agentic-commerce/AP2/blob/b4587ac1d055888a73b4b21750973cffba961793/docs/ap2/agent_authorization.md) require cryptographic chain processing and rejection of unknown constraints. A future autonomous profile also needs open-mandate `cnf` proof-of-possession, unchanged inherited claims, all disclosed constraints, receipt-controlled reuse and durable budget accounting. Neither valid JSON nor a locally generated signing key establishes trust. Record merchant/user/agent-provider anchors, acceptable audiences, verifier nonces, key rotation, revocation sources, expiry, replay policy, retention and operator accountability. These are onboarding/profile decisions still open here.

Exactly one party owns the financial send. If the merchant delegates payment execution to this core, its ACP completion handler invokes the existing admitted operation; the graph never independently calls a PSP. If an external merchant owns execution, the platform delegates and observes the same merchant operation. A lost completion response preserves unknown exposure and triggers status/event/provider reconciliation; it never creates a second platform charge. ACP HTTP idempotency, project command idempotency and PSP financial keys remain separate persisted mappings with immutable request digests.

## Authentication and state projection

The released [delegated-authentication contract](https://github.com/agentic-commerce-protocol/agentic-commerce-protocol/blob/7fdd78df677a94dce04c770644b0fbbb1401272b/spec/2026-04-17/openapi/openapi.delegate_authentication.yaml) defines create, authenticate and retrieve for 3DS2 browser/native handling. It does not establish generic wallet 2FA, bank redirects or payment authorization. Raw card material, browser fingerprints, cryptograms, challenge URLs and OTPs belong only to trusted PCI/provider/UI channels, never model context, graph checkpoints or logs. AP2 spending authority and 3DS authentication are separate admission predicates.

| Observed ACP state | Safe project interpretation |
| --- | --- |
| `not_ready_for_payment`, `incomplete` | Checkout needs correction; no financial success. |
| `ready_for_payment` | Merchant checkout readiness; AP2 authority and dispatch admission still required. |
| `authentication_required` | Possible bound trusted action only when the negotiated metadata/provider flow is supported. |
| `complete_in_progress`, `in_progress` | Preserve existing operation and uncertainty; the state alone is not authoritative evidence of financial acceptance or no effect. |
| `completed` | Merchant reports completion/order creation; require separately correlated financial evidence for the claimed payment milestone. |
| `requires_escalation`, `pending_approval` | Explicit external intervention/approval; no implicit approval or fallback. |
| `canceled`, `expired` | Checkout lifecycle fact; previously admitted financial exposure still requires evidence before release. |

Prefer completing required authentication before the merchant's financial admission when the negotiated route permits it. If authentication changes material checkout data, obtain new bound authority. If a PSP requires a further action during completion, the peer must define the permitted response, retry identity and authoritative status path; the presence of a broad state enum alone does not resolve that lifecycle.

## Reproducible source contradictions and blockers

The [12 structural probes](../../integrations/upstream/fixtures/structural-probes.json) exercise the actual pinned schema bytes with SHA-256, Git blob and length verification. They retain expected failures. They are schema evidence only: no signatures, credentials, provider calls or interoperability tests occur.

| Blocker | Reproduced fact and required resolution |
| --- | --- |
| BR-001: peer/version agreement | Official hosted documentation and released repository surfaces differ. Obtain the actual partner's supported version, handlers, interventions and conformance target. Do not downgrade silently. |
| BR-002: authentication result composition | `DelegateAuthenticationSessionWithResult` uses `allOf` with a closed base that excludes `authentication_result`. A documented-looking result fails `additionalProperties`. Obtain an upstream correction or explicitly versioned, reviewed deviation; preserve the failing probe. |
| BR-003: completion continuation | HTTP 200 completion references `CheckoutSessionWithOrder` and describes completed-on-success. A bare `authentication_required` session without an order fails that response schema. Agree the post-completion challenge/pending behavior rather than invent a 200 shape. |
| BR-004: delegated method scope | Handler PSP names are open strings, but released `DelegatePaymentRequest` accepts card details and lacks `handler_id`; seller-backed RFC gift-card examples do not validate there. Pin a real handler-specific credential flow before enabling wallets, vouchers or saved-method examples. |
| BR-005: AP2 receipt conventions | Successful Payment Receipt requires both PSP and network confirmation IDs. Networkless behavior needs clarification; do not fabricate a network ID. Generic authorization prose also uses `result: success/error` while AP2-specific receipt schemas use `status: Success/Error`; select and review the type-specific mapping. |
| BR-006: reference resolution | AP2 Payment Receipt references `types/receipt_status.json`; that file declares `$id=.../receipt-status.json`. The probe registers the exact file-location alias explicitly and reports it; no remote resolution or content patching occurs. |
| BR-007: cryptographic/trust profile | JOSE, SD-JWT/Delegate SD-JWT and related normative references, exact signed-string hashing, trust anchors, receipt verification, replay and revocation need pins, synthetic-key vectors and independent security review. JSON Schema cannot perform these checks. |
| BR-008: execution and access | Merchant/vault/authentication/provider enrollment, authenticated sidecar acceptance, supported account/method/region and PSP idempotency/lookup/event semantics are absent. Real combinations remain disabled. |

The narrower [payment-handler RFC](https://github.com/agentic-commerce-protocol/agentic-commerce-protocol/blob/7fdd78df677a94dce04c770644b0fbbb1401272b/rfcs/rfc.payment_handlers.md) provides extension structure, not a working provider implementation. A definition named `SplitPayment` or an RFC wallet example does not supply a reviewed atomic allocation/compensation protocol. The current single-tender restriction remains an intentional project safety boundary.

## Concrete external access and next implementation slice

| Combination | External prerequisite and missing evidence |
| --- | --- |
| AP2 plus a cooperative ACP merchant | Merchant accepts the exact checkout-object/sidecar profile; recognized signer/user trust and an AP2-capable credential/receipt path. Test exchanged artifacts with synthetic keys first. |
| Card plus 3DS2 | Merchant PSP sandbox account, supported tokenization/vault route, authentication-provider sandbox, acquirer metadata, trusted browser challenge integration and verified backend results. The released-schema blockers above must be resolved. |
| Wallet/provider 2FA, bank redirect including iDEAL | Separate provider enrollment, handler/instrument schema pins, trusted origin/return/session binding, and authenticated query/event outcome contract. The card-only delegated-authentication API cannot be reused by assertion. |
| Amazon Pay, PayPal, PayPay, Uber Pay or other named providers | Provider-specific commercial/sandbox access, permitted merchant region/account/channel, credentials/keys and reviewed handler/token contracts. No account access, generic acceptance, partnership or portability is inferred from ACP's open `psp` string. |
| Voucher, points, store credit or split tender | Merchant/provider balance authority and redemption semantics plus a separately reviewed per-leg reservation, unknown outcome, compensation and refund design. Seller-backed RFC examples alone are insufficient. |
| Hosted ChatGPT checkout | Approved participation and confirmation of the deployed ACP version/security/handler surface. Open source access alone does not grant hosted acceptance. |

Work can proceed without live access: lock remaining cryptographic standards, define synthetic merchant/user trust fixtures, close the schema/profile decisions with independent review, then implement one offline signed-artifact verifier/merchant-sidecar exchange. Keep that profile distinct from `local-fake-single-tender-0.1`. Only after synthetic positive/negative cross-language evidence and the real-wire design gate should a named, explicitly configured provider sandbox connector be enabled. No real money, deployment or external accreditation follows from this design.
