# Architecture concept

[Documentation](../README.md) / [Concepts](README.md)
Status: platform composition brief, updated 2026-11-08. The broader real-provider and managed-platform design gates in the [roadmap](../project/README.md) remain open.

## Composition

The platform's PaymentIntent is the aggregate for an AP2-based authorized purchase workflow. It embeds an OpenAI ACP checkout integration. It is a platform object, not a claim that AP2 defines this aggregate or that it is a Stripe PaymentIntent.

```mermaid
flowchart TD
  Clients[Consumer, business and agent applications] --> API[Platform API]
  API --> Flow[LangGraph workflow]
  Flow --> Authority[Mandate and authority service]
  Flow --> Checkout[Embedded ACP checkout module]
  Checkout --> Extension[Capability and checkout extension layer]
  Extension --> Execution[Payment coordinator]
  Authority --> Execution
  Execution --> Adapters[Provider adapters]
  Execution --> Action[Trusted customer action UI]
  Adapters --> Events[Verified events and reconciliation]
  Events --> Store[Transaction records and evidence]
  Store --> Flow
```

## Component ownership

| Component | Owns | Does not own |
| --- | --- | --- |
| Intent application service | Intent lifecycle, tenant scope, command admission | Provider-authoritative settlement outcome |
| Checkout module | Merchant checkout snapshot/version, order linkage | Invented merchant prices or duplicate charges |
| Authority service | Verified grant/mandate references and checks | Issuer authentication decisions |
| Payment coordinator | Attempts, idempotency, dispatch, reconciliation, allocations | Unconditional retry after uncertain execution |
| Adapter registry | Versioned capabilities per account, region and channel | Assumed portability of tokens |
| Action service | Expiring provider interaction bound to an attempt | Model collection of OTPs or authentication secrets |
| Transaction persistence | State versions, reservations, events and evidence references | Shared writable graph memory |
| LangGraph | Task sequencing and human-intervention coordination | Authority to override transaction rules |

Use a modular core with domain/application/ports/infrastructure boundaries. Provider SDKs, HTTP and SQL are infrastructure concerns. A separate LangGraph process calls authenticated commands. Split into additional services only for a documented operational reason.

The independently authored [Java AP2 engine](../reference/ap2-engine.md) supplies a bounded synthetic direct TAP mandate/receipt verifier and role exchange. The explicit [native composition](durable-composition.md) binds its verified authority and exact embedded ACP checkout to durable admission, with atomic replay consumption and reservation. LangGraph does not acquire signing keys or native authority artifacts. It runs bounded decisions through a replaceable [durable runtime](../advanced/durable-runtime.md); PostgreSQL remains the reference authority for payment state.

For native composition, `NativeApplicationCoordinator` centralizes exact artifact handling and verification policy using the existing ports. `JdbcPaymentStore` retains transaction ownership and the explicit provisioning, trust-snapshot and financial-admission ordering. This is a bounded application-layer extraction; overall financial-use-case coordination still needs further separation. See [ADR 0008](../adr/0008-native-application-coordination.md).

`ApprovePayment` and `ExecutePayment` own their command authorization, accepted-command replay and financial policy through typed views of one `PaymentCommandTransactions` boundary. Approval coordinates checkout/grant/budget checks and the staged attempt/native-verification/reservation sequence; execution binds and schedules one operation. JDBC retains indivisible writes, native transaction integration, scoped locking and existing retry rules. `RunPaymentWork` owns preparation/admission policy and provider orchestration through `PaymentWorkerTransactions`, with injected fact verification and explicit synthetic progression. Provider calls follow committed claims; stale uncertainty cannot overwrite known acceptance. JDBC retains expiry, native transaction integration and evidence settlement. Further phase extraction and full admission-backend replacement remain separate work; see [ADR 0010](../adr/0010-payment-transaction-boundary.md), [ADR 0018](../adr/0018-approval-application-transaction.md) and [ADR 0019](../adr/0019-worker-transaction-ownership.md).

The [external checkout client](../reference/external-checkout.md) implements create/retrieve/update/cancel through replaceable contracts. A separate [durable commerce application](../how-to/durable-external-checkout.md) now stages exact requests, commits an exclusive claim, sends outside the lock and retains correlated outcomes. `CheckoutLifecycleTransactions`, `CheckoutCommandTransport` and `CheckoutConfigurationResolver` separate atomic storage, network implementation and trusted configuration. LangGraph calls the application through `CheckoutPort`, with independently injected registration and checkpoint storage. This does not inject HTTP into native freeze/validate or bind external responses to financial admission.

The additive [provider execution contract](../../specification/provider-execution-1/README.md) separates nonfinancial preparation, financial submission, read-only reconciliation, event verification/normalization and optional true no-effect fencing. Its compatibility bridge injects an atomic binding journal. The opt-in `local-provider-journal-admission-1` route retains a complete immutable association with the core operation, then uses existing atomic admission before provider submission. Historical operations keep their legacy route; see [ADR 0020](../adr/0020-provider-journal-admission.md). Adapter-only synthetic TCK evidence does not establish production persistence or application admission/outbox guarantees. See the [provider roadmap](../project/provider-adapter-evolution.md) for remaining external and composed integration work.

The [durable provider journal](../advanced/durable-provider-journal.md) now implements that existing binding responsibility in PostgreSQL, independently of financial tables. It publishes one initial dispatch owner, preserves the full legacy mapping and retains conflicting evidence before returning a quarantined result. Actual child-JVM tests cover its separate commit gaps with the durable synthetic peer. The opt-in route preserves core spending authority and admission as the financial owner; neither a journal claim nor a constructed evidence value supplies that authority. Canonical synthetic projections prevent repeated observations from inventing financial progress; journal quarantine propagates to core freeze/disputed exposure.

The opt-in `local-provider-interactions-1` route additionally carries full preparation action bindings into the core. A durable completion request precedes external authentication; a separately resolved result and current authority precede READY/DISPATCH. Signed event ingress retains protected raw bytes and verified provenance, then applies the existing journal conflict rules and core projection. Authentication, event parsing/verification, inbox storage and health gates are independently injected. Inbox, provider journal and core commit separately; explicit replay or opt-in durable delivery closes those gaps. Automatic acceptance atomically publishes the protected envelope and a leased delivery job; separate quarantine jobs propagate authenticated conflicts to both owners. Scheduling and webhook authentication are replaceable. See [ADR 0022](../adr/0022-automatic-provider-event-delivery.md) and [ADR 0021](../adr/0021-provider-authentication-events.md).

## Execution flow

1. An authenticated principal requests a platform intent. Parse suggestions from the agent but validate all binding data deterministically.
2. Create/update the embedded checkout and discover eligible payment capabilities.
3. Freeze the merchant-authoritative checkout version, payment allocations and execution owner for approval.
4. Obtain and verify authority bound to the required snapshot; reserve budget atomically.
5. Request checkout completion. If the merchant uses our payment coordinator, the merchant completion path invokes it. If an external merchant executes payments, delegate and observe; never also charge independently.
6. Perform provider preparation/authorization and required customer actions in the order supported by that integration.
7. Verify callbacks or query provider status; distinguish authentication, authorization, capture, settlement and order fulfilment.
8. Reconcile unknown outcomes before retry or route changes. Store actual evidence with provenance.

## Extensibility

Replaceability and composition apply to every component interface, not only payment-provider adapters. Protocol engines, checkout integrations, authority verification, signing and key services, artifact storage, grant/admission coordination, authentication, events, identity and orchestration need explicit behavioral contracts. A caller depends on the contract; implementations may be native, external adapters, decorators or delegated compositions. Reference implementations demonstrate the contracts without making their backend mandatory.

Define each interface once around its responsibility, not around a vendor. For example, an immutable signed-artifact store may have Azure Storage, DynamoDB, PostgreSQL or payment-grant-service adapters, all implementing the same contract. A future backend adds a concrete implementation and configuration/registration; it does not require a new interface or workflow/domain changes. The same rule governs every other component boundary.

In particular, this platform must support its AP2 implementation paired with an external ACP implementation, or its AP2 and ACP implementations with the ACP layer adapting/delegating to an external implementation. The same boundary applies to cryptographic implementations and storage. Actual algorithm support remains constrained by negotiated protocol profiles and trusted policy. Signed-artifact storage, such as an immutable object store, is separate from the service that atomically consumes a grant and admits a payment. Replacement tests must establish the guarantees callers rely on, including failures and recovery.

Provider, method, credential format, customer action and checkout allocation are separate extension axes. Capabilities are intersected with the merchant account, currency, country, channel, authorization profile and provider support. Mandatory unknown capabilities reject; no silent downgrade.

The ACP boundary remains pinned to the selected external contract. Current documentation has restricted provider/method/status enums. Broader internal capabilities need a separately negotiated extension/hosted continuation contract; do not invent accepted ACP fields or collapse pending into success. See [sources](../reference/references.md).

## Trust and persistence

- Money uses exact currency-aware minor units; conversions and rounding are explicit.
- Aggregate budget reservation and intent transition use database concurrency control. Define revocation versus dispatch ordering in detailed design.
- Persist commands before external side effects, stable provider idempotency identities and reconciliation work. Checkpoints cannot close the provider-success/local-crash gap.
- Customer actions are bound to tenant, principal, attempt, checkout version and expiry. Browser redirects do not establish payment success.
- Keep raw credentials, provider action secrets and signing keys outside model context/checkpoints/logs. Use managed keys and established verification libraries.
- Split tender is a saga with declared compensation limits. Discounts modify the price; stored-value vouchers fund allocations.
- Keep native signed payload bytes and their digests for verification. Normalized models are not substitutes for signed source material.

## Design gate

Resolve signed-byte mapping, AP2 actor/trust matrix, external ACP continuation, execution ownership, unknown-outcome state transitions, revocation linearization and split-tender compensation before implementing payment execution. The initial review identifies evidence required to close each issue.

## External checkout authority boundary

The optional [external authority profile](../advanced/external-checkout-authority.md) connects retained ACP observations to the existing AP2 coordinator and financial admission. A trusted local signer attests exact retained bytes; a remote HTTP response alone grants no financial authority. The same transaction permanently seals the purchase and publishes its quote. The original principal, configuration, expiry and authority are revalidated before admission, while already admitted uncertainty remains attached to its original operation for reconciliation.

Checkout, authority, signing, artifact storage and admission remain independent replaceable interfaces. The reference financial and lifecycle stores share a database and opaque coordination identity through trusted composition. Replacements must preserve atomicity and required capabilities. LangGraph can produce a durable commerce observation; automatic financial handoff for this external profile remains future work.

Delivery routing selects complete historical handler compositions from an exact scoped execution-binding/event-policy catalog. Immutable nonsecret commitments prevent silent metadata rebinding on restart; a separate keyless namespace route projects authenticated disputes after signing configurations retire. Normal admission validation stays unchanged. See [ADR 0023](../adr/0023-historical-event-routing.md).
