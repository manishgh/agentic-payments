# agentic-payments product brief

Status: documentation baseline under review; detailed design in progress. Kickoff: 2026-11-06 (Europe/Berlin).
No SDK, simulator, provider integration, managed service or certification is implemented yet.

## Purpose

Build a shared payment coordination foundation that lets developers compose agentic purchasing with explicit authority, customer authentication and recoverable execution.
The product consists of a versioned extension specification, adapter SDK, deterministic simulator, project conformance suite and reference implementation.
A later managed offering would provide transaction coordination, visibility and operational support using the same contracts.

The defining composition is an enclosing AP2-based platform payment-intent workflow with an embedded OpenAI ACP checkout workflow.
Payment authentication, including 3DS or provider 2FA when required, belongs inside execution and can suspend it for trusted customer action.
This composition is a proposed project extension; it does not assert upstream AP2 or ACP endorsement, interoperability or equivalence of their objects.
The local-fake simulator design (G2-S) must define its own versioned contracts and authority boundaries. Exact upstream revisions, extension boundaries and real authority mappings must be reviewed at the separate external-integration gate (G2-P).
In particular, the current ACP card-only method enumeration and absence of a pending/challenge completion status must not be extended implicitly.
The external-integration design must identify a permitted mapping, an explicitly negotiated project extension, or unsupported behavior for each asynchronous/customer-action flow. A local-fake continuation may be designed and tested without claiming that ACP accepts it.
See the [source register](references.md) and [decision register](decisions.md) before accepting interoperability claims.

## Users and outcomes

| User | Initial outcome | Later expansion |
| --- | --- | --- |
| Consumer application team | Demonstrate an authorized purchase with explicit customer action and visible outcome in the simulator. | Provider-backed consumer checkout after applicable access and pilot gates. |
| B2B platform team | Model delegated budgets, approval policy and auditable purchase attempts. | Organization policy, tenant operations and approved business payment methods. |
| Agent developer | Integrate one documented workflow and test failure behavior locally. | Publish compatible adapters against explicit project profiles. |
| Merchant or provider integrator | Implement a port against synthetic fixtures and inspect conformance evidence. | Approved sandbox and partner integrations. |
| Platform operations team | Inspect sanitized intent history and unresolved execution. | Run a managed service with reconciliation, escalation and support ownership. |

Consumer and B2B journeys share execution contracts; policy differences must be explicit profiles rather than separate payment engines.
An AI agent can propose and orchestrate permitted actions. Trusted application services enforce authorization and own payment truth.

## Product surfaces

| Surface | Initial developer preview deliverable | Publication boundary |
| --- | --- | --- |
| Extension specification | Normative draft contracts, state transitions, capability rules and threat model. | A draft is not a released standard; stable identifiers and compatibility rules precede a versioned release. |
| Java adapter SDK (proposed) | Provider-neutral ports, value types and reference adapter examples. | Language/runtime/dependency ADRs remain proposed until design review. |
| Deterministic simulator | Synthetic payment, checkout and customer-action scenarios with controllable time/events. | Synthetic success does not establish provider support. |
| Project conformance suite | Reproducible profile checks and evidence reports tied to exact versions. | Passing means only the named project profile passed the named suite. |
| Reference implementation | Java core, Python LangGraph workflow, TypeScript trusted UI and PostgreSQL storage, all proposed. | Reference quality and limitations are documented; preview is not production readiness. |
| Managed platform (later) | Design for tenant coordination, traceability, unresolved-outcome handling and support. | A pilot requires separate operational, commercial, legal and security gates. |

## Initial scope

- One complete simulated purchase: intent, policy/authority check, embedded checkout, customer action, execution, result and audit trail.
- Exact monetary values; policy reservations; scoped idempotency; verified event handling; recovery after restart.
- An explicit single execution owner per payment attempt, including timeout and ambiguous-provider outcomes.
- A capability model separating provider, payment method, credential and customer-action support; unknown required capabilities fail closed.
- Deterministic failures: duplicate request/event, timeout, stale action, denial, cancellation, restart and unresolved outcome.
- A repeatable local developer journey and small conformance profiles, including an independent negative reference fixture.
- A versioned documentation and evidence structure with change control, licensing decisions and a release checklist.

## Integration coverage strategy

The design supports extensibility; the preview does not promise all integrations or methods.
Coverage is recorded per provider × method × operation × region × credential × customer-action profile.
A provider name alone never implies method, refund, recurring-payment or regional coverage.

| Integration family | Planning treatment | Required dependency before a real adapter claim |
| --- | --- | --- |
| Stripe and Adyen | Candidate provider adapters; select a first sandbox vertical slice after capability review. | Authorized sandbox account, applicable APIs/terms, supported region/method and test authentication path. |
| Cards | First simulated method candidate, including required customer-action challenges. | Provider tokenization boundary, supported authentication flow and compliance assessment. |
| Bank methods / iDEAL | Model asynchronous completion and redirect/customer action separately. | Provider and regional availability, test access, webhook semantics and settlement/reconciliation design. |
| Amazon Pay, PayPal, PayPay | Distinct wallet integrations; no generic wallet adapter promises interoperability. | Each vendor's onboarding, documented interface, permitted use and test environment. |
| Vouchers | Model issuer, redemption, partial use and expiry capabilities explicitly where relevant. | Issuer agreements and documented accounting/redemption semantics. |
| Uber Pay | Special partner integration track, outside the default public-provider assumption. | Partner sponsorship, interface documentation, access and contractual permission; otherwise remain simulated. |

Access, licensing, commercial contracts, geography and vendor review can block live coverage independently of engineering progress.
Unsupported combinations produce a documented capability refusal before execution.

## Trust and operating boundaries

- Payment truth belongs to the core transaction store; graph checkpoints record orchestration progress only.
- Payment credentials, OTPs, private keys and sensitive payment payloads never enter model context or logs.
- Trusted customer surfaces and provider-hosted flows handle authentication; the agent receives only sanitized status/action references.
- Recovery consults durable state and provider evidence. A timeout cannot authorize a second charge.
- Development uses synthetic fixtures; external sandboxes require explicit configuration. No live money movement is authorized for development.
- Project conformance or project certification does not confer PCI, regulatory, payment-network or vendor certification.
- A managed service's custody, merchant-of-record, payment-service and compliance roles must be determined before pilot commitments.

## Success evidence

The preview succeeds when a new developer can reproduce a documented simulated purchase and failure/recovery cases from a clean environment.
Every terminal outcome and unresolved attempt must have an inspectable, sanitized evidence trail.
The conformance suite must reject deliberately incompatible adapters and identify exact failing requirements.
Released artifacts must identify the extension revision, suite/profile version, supported capability matrix and known limitations.
Provider-backed success and operational readiness are separate later acceptance gates, not substitutes for simulator correctness.

## Decisions still required

| Decision | Accountable owner | Deadline / gate |
| --- | --- | --- |
| GitHub destination `https://github.com/manishgh/agentic-payments.git` | Confirmed by user; integration owner verified empty remote | Destination resolved; verify branch/history and visibility before each applicable publication step. |
| Public license and dependency redistribution policy | User / product owner, legal input as needed | Before public package/spec release or external distribution. |
| Local-fake profile, execution ownership and authorization boundaries | Integration owner with independent design review | G2-S, before simulator implementation. |
| Pinned real protocol revisions, composition and cryptographic trust mapping | Integration owner with GPT-6 Astra review | G2-P, before real AP2/ACP integration or compatibility claims. |
| Runtime/framework choices and support baseline | Integration owner with GPT-6.1 Sol proposal | Design acceptance. |
| First provider, region and method | Product/integration owner | Before sandbox implementation. |
| Project certification wording and evidence governance | Product/integration owner | Before any certification claim. |
| Managed service role, support coverage and pilot economics | Product owner with qualified specialists | Before live pilot. |

## Delivery approach

Documentation precedes reviewed design; implementation follows in small tested vertical slices.
Use GPT-6.1 Sol at high reasoning for bounded execution and GPT-6 Astra for complex protocol/security/recovery decisions and independent reviews.
The integration owner consolidates evidence and decides whether a gate is met; model output is not approval by itself.
The [roadmap](roadmap.md) estimates the first 12 weeks and later gates; the [backlog](backlog.md) records stable task IDs and the next bounded task.
Work advances during requested work sessions. Dates and estimates do not imply unattended execution, scheduled work or guaranteed completion.
The user has authorized continued design work in the current session. Estimates are effort/order guides, not required waiting periods: proceed when prerequisite evidence is accepted. Local-fake progress does not depend on provider onboarding or full upstream wire pins; those remain mandatory for the real integration scope they govern.
