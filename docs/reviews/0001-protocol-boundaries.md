# Review 0001: Protocol composition and execution boundaries

Status: preliminary concept review; design gate remains open. Reviewed 2026-11-06. No implementation, compatibility certification, security certification, or production readiness is established by this review.

## Scope and evidence

The intended composition is an AP2-based platform payment-intent workflow containing an OpenAI Agentic Commerce Protocol (ACP) checkout, with provider authentication inside checkout execution. The Java core/adapter SDK, Python LangGraph reference workflow, simulator, and conformance suite are planned deliverables. Managed coordination is a later operational offering.

The repository contained `AGENTS.md` when this review began; no executable implementation or finalized wire contract was available. Findings below are requirements and unresolved design decisions, not defects proven in running software. The term **platform payment intent** denotes a project object, not a native AP2 object or a provider-specific PaymentIntent.

Primary-source facts checked for this review:

- AP2 v0.2 defines Checkout and Payment Mandates, five roles, and deterministic verification. The Trusted Surface is non-agentic. Its checkout binding uses a merchant-signed JWT and a cryptographic hash; adapting another checkout object requires a defined extension. [AP2 specification](https://ap2-protocol.org/ap2/specification/)
- The inspected OpenAI checkout schema names `stripe`, `adyen`, and `braintree` as payment providers and `card` as the supported payment-method enum. Checkout completion is OpenAI-to-merchant; the merchant processes payment through its PSP. The completion response states do not represent a general challenge or pending-payment lifecycle. [OpenAI Agentic Checkout Spec](https://developers.openai.com/commerce/specs/checkout)
- Uber Pay is an invite-only PSP/payment-partner integration for the Uber ecosystem. Its partner-facing payment APIs include endpoints implemented by partners for Uber to call. It is not evidence of an unrestricted general merchant payment rail. [Uber Pay API](https://uberpay.uber.com/)

These facts do not prove interoperability of this proposed composition. Pin source revisions and schema artifacts at design approval; a retrieval date alone is insufficient for repeatable conformance.

## Findings and required decisions

### 1. Define the actual AP2-to-ACP wire binding

**Gate blocker.** An architecture diagram with AP2 surrounding ACP leaves the security-critical mapping unspecified. Produce a versioned profile naming the checkout snapshot, signer, exact serialized bytes, hash algorithm, mandate references, receipt references, transport locations, and verification rules. Identify which fields belong to unmodified ACP and which require a negotiated extension or separate authenticated channel. Do not silently add project fields to an upstream contract and call it native compatibility.

Bind consent to merchant identity, checkout revision, items, total, currency, fulfillment, and relevant payment constraints. Define changes that invalidate consent and require a new mandate. Prevent an agent from substituting another checkout after approval. Publish positive and negative fixtures demonstrating the same binding in Java and Python. Treat signatures as verification inputs, not proof of trust until signer identity and authority are established.

### 2. Assign real roles, trust anchors, and credential responsibilities

**Gate blocker.** Map Shopping Agent, Credential Provider, Merchant, Merchant Payment Processor, and Trusted Surface to actual components and accountable organizations. A component taking multiple roles must implement each role's responsibilities. A generic PSP adapter does not automatically provide a Credential Provider or AP2-compatible payment credentials.

Specify user enrollment, signer-key association, merchant-key trust, credential issuance, audience and merchant scoping, expiry, rotation, replay prevention, delegation-chain verification, and revocation lookup behavior. Define which participant checks each property and which evidence is retained. Separate authentication of an API caller from authority to spend. Fail closed when required trust material or constraints cannot be verified; a simulator must label synthetic keys and trust roots explicitly.

### 3. Give each charge exactly one execution owner

**Gate blocker.** Decide whether the ACP merchant service calls the adapter core or whether an authenticated external execution owner performs the payment. In the former case, the core is the merchant's payment execution service. In the latter, the platform observes and reconciles the external owner; it must not independently call a PSP after checkout completion times out.

Record owner identity and attempt identity before dispatch. Define transfer of ownership, if permitted, with fencing that prevents the previous owner from executing. Keep checkout completion, order creation, provider authorization, capture, settlement, and refund as distinct facts. A completed checkout must not be interpreted as every possible downstream financial milestone. Provider evidence establishes payment status; merchant evidence establishes order status. Resolve disagreement explicitly.

### 4. Keep user authority separate from payment authentication

**Gate blocker.** A valid mandate authorizes bounded agent activity; it does not make an issuer approve payment. Successful 3DS, wallet login, or provider 2FA does not authorize a changed cart or broader spending scope.

Model customer action as a bound, expiring attempt continuation executed on a trusted provider or application surface. Define origin validation, return-state binding, replay handling, cancellation, expiry, and authenticated server-side status confirmation. A browser redirect is not sufficient payment evidence. Revalidate authority at the defined execution boundary after an action resumes. Never let an LLM collect OTPs or operate as the trusted consent verifier.

The desired embedded checkout experience still needs a transport and UI design for these actions. Demonstrate it against the selected ACP profile; otherwise mark that combination unsupported rather than inventing native ACP statuses.

### 5. Scope idempotency and preserve unknown outcomes

**Gate blocker.** Define identity across tenant, merchant/provider account, logical payment, operation, tender leg, and attempt. Bind a key to an immutable request digest; reject reuse with different material parameters. Authorize, capture, refund, and cancel require separate operation identities. Record the mapping to provider keys and provider retention windows.

A timeout after dispatch is an unknown outcome. Persist it, reconcile using authenticated events or status queries, and retain associated exposure. Do not allocate a new attempt or switch providers while an earlier attempt could have charged. Specify what happens after provider idempotency expires or queries cannot establish the result; manual investigation may be necessary.

Design a durable dispatch/outbox and event/inbox boundary, deduplication, stale-event handling, and recovery after crashes on either side of the provider call. The defensible guarantee is prevention of duplicate effects under stated provider assumptions and recovery rules; do not claim universal exactly-once delivery.

### 6. Linearize budgets, revocation, and concurrent execution

**Gate blocker.** A separate check-then-charge sequence permits two agents to spend the same budget. Define atomic reservations against an authoritative ledger with exact money, currency rules, rounding rules, and consistent tenant/principal scope. Unknown or outstanding authorizations continue to consume exposure according to a documented policy.

Choose and document the ordering point between admission of a payment and revocation. Prevent newly admitted work after revocation while explaining which already-dispatched payments may still complete. A local transaction cannot atomically revoke a request already accepted by a provider. Capture after revocation needs an explicit policy for preexisting authorizations. Do not describe cancellation or revocation as undoing a charge.

Test conflicting reservations, stale workers, lease expiry, revocation during a customer challenge, dispatch races, late capture events, and process restart. Define whether refunds replenish spending authority; refund accounting and renewed permission are separate decisions.

### 7. Make split tender and refunds an explicit profile decision

**Gate blocker for split-tender support.** Voucher plus card combines independent systems without a shared transaction. Choose either an initial single-tender profile that rejects split requests or a documented coordinator with per-leg reservations, authorizations, execution ordering, compensation, and unresolved-state handling. A voucher discount and a stored-value payment are different concepts.

For supported split payments, define partial success, non-reversible voucher consumption, partial capture, amount allocation, over-refund prevention, original-tender refund routing, expired instruments, and reconciliation. Compensation is another operation that can fail; it is not atomic rollback. Refunding one leg must not accidentally release budget for another unresolved leg. State which party owns customer remediation.

### 8. Keep secrets and payment truth outside graph checkpoints

**Gate blocker.** LangGraph orchestrates typed commands and waits on authoritative operation status; graph replay must not create another financial effect. A checkpoint stores references and safe summaries, never provider credentials, reusable payment tokens, OTPs, private keys, or sensitive authentication payloads. Opaque references still need authorization and tenant scoping.

Specify credential-vault access, redaction for traces and exception objects, tokenized UI handoffs, encrypted storage, retention, and audit access. Test secrets escaping through failed adapter calls and graph debugging tools. Keep signed evidence in an access-controlled evidence store when necessary; do not copy complete payment or identity payloads into model context.

### 9. Publish capability claims at the right boundary

**Gate blocker for compatibility claims.** Maintain separate capability dimensions for provider, method, credential format, customer-action mechanism, currency/region, and operations such as capture/refund. Declare profile versions and required capability negotiation. Unknown required capabilities must fail closed.

Stripe or Adyen adapters do not imply every method those vendors offer works in the inspected ACP contract. Amazon Pay, PayPal, PayPay, iDEAL, vouchers, and Uber Pay need individual researched contracts and evidence. PayPal and PayPay are distinct integrations. Reserve a restricted Uber Pay partner profile with explicit onboarding prerequisites and call direction; do not market it as generally available.

Distinguish core SDK capability, project extension conformance, upstream protocol compatibility, vendor integration acceptance, and availability in ChatGPT. Each claim needs its own evidence. Project certification is not regulatory, PCI, card-network, or vendor certification.

## Criteria to pass the design gate

Before production adapter implementation, reviewers should approve:

1. Pinned upstream contracts and a versioned extension profile with field-level mappings, unsupported cases, schema fixtures, and negotiation rules.
2. A role/trust matrix and threat model covering malicious agent output, checkout substitution, cross-tenant access, credential replay, and compromised or stale workers.
3. A state-transition and ownership model covering order/payment disagreement, authentication continuation, unknown outcomes, reconciliation, and terminal-state evidence.
4. Transaction-level budget and revocation semantics, idempotency scopes, provider assumptions, and crash-recovery sequences.
5. An explicit initial tender scope, with refunds and split-tender behavior either specified or rejected by contract.
6. A synthetic conformance matrix including tampered mandates, unsupported capabilities, concurrent reservations, revocation races, duplicate/out-of-order events, expired idempotency, and secret-leak checks.
7. A thin reference sequence showing Java core commands and Python orchestration using the same authoritative operation IDs, with no live funds or production credentials.

Recommended next slice: finalize a single-provider, single-tender synthetic checkout profile and its failure/recovery traces before growing the adapter surface. Keep managed operations deferred while defining the observability and reconciliation interfaces they will eventually consume. Passing this design gate authorizes a bounded implementation slice; certification requires later executable evidence against the named profile.
