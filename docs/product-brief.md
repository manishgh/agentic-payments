# Product brief

Agentic Payments is a payment coordination foundation for applications that combine agent-driven purchasing, explicit spending authority, customer authentication and recoverable execution. It provides versioned contracts, a Java adapter SDK, a deterministic simulator, a conformance suite and a runnable reference platform.

The core composition is an AP2-based platform payment intent enclosing an ACP commerce checkout. Payment authentication belongs inside execution and may pause work for a trusted customer action. The platform keeps those responsibilities distinct: a checkout response, graph checkpoint, redirect or model decision cannot establish financial authority or payment success.

## Users and outcomes

| User | Value |
| --- | --- |
| Agent application developer | Integrate a documented purchase workflow and reproduce failures locally before connecting payment services. |
| Consumer application team | Bind approval to exact checkout terms, pause for customer authentication and resume the original payment safely. |
| B2B platform team | Coordinate spending grants, budgets, approval policy and auditable purchase attempts. |
| Merchant or provider integrator | Implement, adapt or wrap stable responsibilities and test the exact supported capability profile. |
| Platform operations team | Inspect sanitized status and unresolved exposure, reconcile original operations and escalate uncertain outcomes. |

Consumer and B2B flows share execution contracts. Their policy differences belong in explicit profiles. Agents propose and orchestrate permitted work; trusted services enforce authorization and retain payment truth.

## Platform surfaces

| Surface | Current scope |
| --- | --- |
| Specification | Versioned payment extension requirements, schemas, state transitions and capability rules. |
| Java SDK | Local execution interfaces, provider/method/credential/action descriptors, a separate provider-execution contract and reusable behavioral tests. |
| Reference platform | Java payment core, PostgreSQL storage, Python LangGraph workflows and a trusted TypeScript UI. |
| Protocol composition | Independently authored AP2 direct TAP authority and exact embedded ACP checkout binding using synthetic identities. |
| External checkout | Durable create/retrieve/update/cancel, protected observations and conservative uncertainty handling. An opt-in local-attestation financial binding implementation is undergoing validation. |
| Durable orchestration | SQLite and Temporal payment adapters; a separate LangGraph/SQLite commerce workflow. |
| Simulator and conformance | Synthetic scenarios, controlled time/events, negative adapters, restart/recovery checks and versioned requirement reports. |

A later managed offering would add tenant lifecycle, operational visibility and support using the same contracts. It is not currently an operated payment service.

## Integration and method boundaries

Coverage is defined per provider × method × operation × region × credential × customer action. A provider name never implies all methods, refunds, recurring payments or regional coverage. Unknown required capabilities fail before execution; configuration and authority cannot silently switch to another route.

Each responsibility has one replaceable interface. A component can implement it directly, adapt another service or delegate through an existing implementation. A new storage backend or vendor adapter does not require a vendor-specific interface. Replacements must preserve atomic admission, exact identity, current trust and recovery semantics, rather than merely matching method signatures.

| Integration family | Required behavior before activation |
| --- | --- |
| Cards and PSPs such as Stripe or Adyen | Authorized account/API access, tokenization boundary, exact idempotency and event semantics, supported authentication and reconciliation. |
| Bank methods and iDEAL | Explicit asynchronous settlement, redirect/action, expiry and unknown-outcome handling for the supported region and provider. |
| Amazon Pay, PayPal and PayPay | Separate wallet contracts, onboarding, credentials and customer-action profiles. |
| Vouchers and split tender | Issuer/redemption semantics, expiry, partial use, per-leg reservations, ambiguous outcomes and compensation. Discounts remain separate from payment tenders. |
| Refunds | Original-tender identity, authorization, accounting and recovery rules; no inferred inverse of a charge. |
| Uber Pay | A permitted partner interface, sponsorship, documentation and contractual access. |

These are extension targets. Execution currently enables synthetic profiles only; naming a provider or method does not implement it. ACP transport fields and statuses must conform to their selected version rather than inherit the platform's broader internal model.

## Trust and operating model

Payment truth belongs to the authoritative transaction store. Graph checkpoints record progress and safe references. Exact money, scoped idempotency, atomic exposure reservations and one execution owner protect every attempt. Timeouts retain uncertainty and never authorize a replacement charge.

Credentials, OTPs and private keys remain outside model context and logs. Trusted customer surfaces handle approval and authentication. Synthetic AP2 enrollment and local external-checkout attestation do not prove real merchant delegation, customer consent or provider acceptance.

The external authority profile permanently seals one source purchase to one financial intent. It does not send merchant checkout completion, make remote state immutable or connect the separate provider journal to admission. See the [external authority guide](external-checkout-authority.md) for the bounded profile.

## Success criteria and remaining scope

A developer should be able to reproduce a documented synthetic purchase, customer-action pause and original-operation recovery from a clean environment. Incompatible adapters must fail explicitly, and reports must identify their exact profile, tested behaviors and gaps.

The complete adversarial/privacy/crash matrix and certification policy remain unfinished. Real providers, broader AP2 authority profiles, additional methods, managed identity and operations require their own implementations and evidence. Project checks establish no upstream, vendor, PCI, regulatory or network certification.

Public license and distribution terms remain open. Provider selection needs authorized sandbox access; live operation additionally needs explicit scope, operating roles, security/compliance assessment and support ownership. See [status](status.md), [roadmap](roadmap.md) and [backlog](backlog.md).
