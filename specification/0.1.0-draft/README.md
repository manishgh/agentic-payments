# Extension 0.1.0-draft: proposed requirements

Status: reviewed project requirements baseline. Normative terms have the scope defined in the [version policy](../README.md). The adjacent schemas and fixtures implement the bounded local-fake wire profile; this requirements register also includes obligations not yet implemented or proven. See the profile descriptor and current evidence for support claims.

## Composition and vocabulary

The platform owns an outer payment intent and embeds an OpenAI checkout session as a commerce step. AP2 authorization and provider customer authentication remain separate checks. A checkout result, graph checkpoint, browser redirect, or language-model conclusion alone cannot establish payment success.

| Concept | Meaning in this project | Examples, without support claims |
| --- | --- | --- |
| Provider | Integration or payment service selected for execution | Stripe, Adyen, Amazon Pay, PayPal, PayPay, a separately contracted Uber Pay partner |
| Method | Payment instrument or rail behavior | Card, bank redirect such as iDEAL, voucher |
| Credential | Opaque reference to authorized payment material | Provider token or vault reference; never raw card data in agent context |
| Customer action | A bounded interaction needed to progress | 3DS challenge, provider 2FA, redirect, voucher presentation |

These dimensions are not interchangeable and support is not a cross-product. PayPal and PayPay are distinct integrations. Provider availability depends on merchant account, method, currency, country, and approved integration. Names here are design targets rather than partnerships or vendor certification.

The inspected [OpenAI checkout specification](https://developers.openai.com/commerce/specs/checkout) lists `stripe`, `adyen`, and `braintree` for its payment-provider enum and places charge initiation with the merchant. A platform adapter registry must not inject other provider names or arbitrary extension fields into that contract. A separate agreed mapping or externally supported route is required; unsupported combinations fail closed.

The inspected [AP2 v0.2 specification](https://ap2-protocol.org/ap2/specification/) defines Checkout and Payment Mandates and binds payment authorization to checkout content cryptographically. Local extension fields and fake mandates do not inherit AP2 validity. Exact upstream revisions, formats, signature/trust verification, role allocation, and checkout mapping remain to be pinned at the design gate.

## Capability negotiation

A proposed capability offer describes extension versions, profiles, provider/method combinations, credential-reference types, action types, supported operations, and constraints. The core intersects it with trusted server-side merchant configuration, caller authorization, and intent requirements before selecting a route. A client advertisement cannot enable a capability. Record the selected offer revision and constraints with the attempt; revalidate before an irreversible operation. If capability state changes, halt or obtain a new authorized selection rather than silently changing the route.

Unknown optional capabilities may be ignored only when they have no effect on the selected route. Unknown or unavailable required capabilities reject that route before reservation or execution. Optional method profiles may be omitted, but claiming one makes all its applicable requirements mandatory. New provider-specific data stays in a validated internal adapter boundary until an upstream contract explicitly supports a mapping.

## Stable requirement register

| ID | Proposed obligation |
| --- | --- |
| EXT-001 | The core MUST select a mutually supported explicit version/profile and validate required capabilities; unsupported security requirements and implicit downgrades MUST be rejected before execution. |
| EXT-002 | Provider, method, credential, and customer-action capabilities MUST be modeled independently with allowed combinations and constraints; the core MUST reject an invalid combination. |
| EXT-003 | Each intent MUST have one execution owner and durably scoped idempotency. Concurrent requests, retries, and graph replay MUST NOT create another charge or exceed the authorized amount. |
| EXT-004 | An ambiguous external outcome MUST remain unresolved until authoritative reconciliation. Timeouts MUST NOT be treated as failure, release spendable reservations prematurely, or trigger a new charge through another route. |
| EXT-005 | Customer actions MUST be bound to the attempt, intended operation, subject, and expiry, completed through a trusted surface, and verified server-side. Browser return or replay alone MUST NOT authorize progress. |
| EXT-006 | External events MUST be authenticated, correlated, deduplicated, and applied using monotonic transition rules. Late or reordered events MUST NOT erase settled financial facts; contradictory evidence requires reconciliation. |
| EXT-007 | Authorization MUST bind merchant/payee, checkout version/content, exact amount/currency, permitted method/credential scope, expiry, and execution identity as applicable to the approved profile. A material change MUST require renewed authorization. Real AP2 verification MUST use the pinned upstream contract, not a local fake. |
| EXT-008 | A voucher/partial-payment profile MUST reserve and account for exact amounts atomically, prevent double redemption, and keep the remaining balance explicit. A lost response MUST NOT make a voucher reusable; overall success requires the full authorized obligation to be satisfied. |
| EXT-009 | Expiry/revocation checks and execution eligibility MUST have a defined durable ordering. In the local-fake profile, dispatch means the committed dispatch-admission decision: revocation ordered before admission blocks it. Physical sending may happen after admission and after a later revocation; that admitted exposure requires reconciliation and cannot be reported as cancelled solely from revocation. Other profiles MUST state their ordering point explicitly. |
| EXT-010 | Credentials, OTPs, private keys, and sensitive provider payloads MUST stay outside model context, logs, traces, checkpoints, and conformance evidence. Use opaque references and synthetic fixtures. |
| EXT-011 | Simulator runs MUST be reproducible from recorded versions, seed, virtual-clock inputs, scenario, and event ordering, without live money or production credentials. |
| EXT-012 | Conformance claims MUST identify exact versions/profiles, applicable tests, exclusions, and retained evidence. Local project certification MUST NOT imply vendor, AP2/OpenAI, PCI, regulatory, or network accreditation. |

## Review questions before design acceptance

Resolve the execution-owner boundary for merchant-initiated checkout; the durable dispatch/revocation ordering and reconciliation authority; partial voucher compensation and expiry; action origins and callback trust; AP2 profile and signer trust; and the upstream extension/mapping path for each proposed provider. Define error categories and schemas only after these choices are reviewed. No adapter or method is certified today.
