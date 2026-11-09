# Adapter SDK design brief

[Documentation](../README.md) / [Reference](README.md)
Status: broader SDK design target. A bounded Java local-fake SDK is implemented in [contracts and SDK implementation](contracts-implementation.md); generic provider profiles and certification remain incomplete. Requirements refer to [extension 0.1.0-draft](../../specification/0.1.0-draft/README.md); conformance cases are in the [test brief](../simulator-and-conformance.md). Python LangGraph and TypeScript clients use service contracts without duplicating payment authority.

## Responsibility boundary

The payment core owns route selection, authorization, reservations, attempt identity, execution eligibility, idempotency scope, durable state, and reconciliation policy. An adapter translates a permitted operation to one provider, validates that provider's replies/events, and returns normalized facts. An adapter must not independently reroute, allocate a second execution owner, change an amount, or declare an uncertain outcome failed.

The SDK should expose ports and immutable domain values without depending on a provider library, PostgreSQL, LangGraph, or an HTTP framework. Provider implementations sit outside the domain. The design review must choose packaging, Java/runtime versions, dependency/license policy, and public API stability before publishing artifacts.

## Conceptual operations to design

These are responsibilities, not approved method names or signatures.

| Responsibility | Required design behavior |
| --- | --- |
| Describe capabilities | Return versioned provider/method/credential/action combinations, operation support, constraints, and freshness information. Distinguish supported, configured, and currently available. |
| Prepare an attempt | Validate permitted inputs and return opaque provider references; explicitly declare whether the operation can create financial side effects. |
| Execute an authorized operation | Accept the core's immutable attempt identity, authorization reference, exact money, and stable idempotency scope. Preserve provider request identity across retries. |
| Continue a customer action | Verify server-side action evidence, correlation, expiry, and single-use rules; reject stale or replayed completion. Never accept model assertions of authentication. |
| Query/reconcile | Retrieve authoritative provider facts for the same attempt after a timeout, missing webhook, or contradictory response. Unsupported lookup must leave an explicit unresolved state and escalation path. |
| Verify and normalize events | Authenticate raw callbacks at the boundary, check freshness/replay policy, correlate tenant/account and attempt, and emit safe facts with stable event identity. |
| Cancel, release, refund | Advertise separately. Define applicable states and idempotency for each; a cancellation request is not evidence that an in-flight charge never happened. |

A result model must distinguish confirmed success, definitive rejection with no financial effect, customer action required, accepted/pending, and unknown outcome. “Retryable transport error” and “safe to initiate again” are separate concepts. Provider raw statuses must be mapped with documented evidence and without dropping partial amounts, reversals, settlement distinctions, or uncertainty.

## Capability and authentication profiles

The baseline should require capability rejection, one execution owner, exact money, idempotency, unknown-outcome reconciliation, verified events, mandate binding, privacy, and truthful evidence. Optional profiles may add cards/3DS, provider 2FA, bank redirects such as iDEAL, vouchers/partial funding, and provider-specific credentials. An adapter may omit an optional profile; it cannot claim the profile while omitting its required behavior. Region/account configuration and action availability must be evaluated at selection time (EXT-001/002).

Customer actions are typed descriptors with opaque handles for trusted UI use. The design must settle allowlisted origins, return/callback authentication, session binding, expiry, abandonment, accessibility, and replay prevention. Credentials and OTPs travel directly through trusted provider/vault/UI channels, never the agent or graph state (EXT-005/010). A 3DS challenge or provider 2FA does not substitute for AP2 mandate verification.

The OpenAI checkout projection requires separate review. Current external method/provider restrictions and completion responses must not be expanded by inventing enum values, pending statuses, or challenge fields. Extension-side continuations and any supported external projection need an explicit negotiated route; unsupported states must not be flattened to success/failure. Internal adapter support alone proves no external compatibility.

## Retry, concurrency, and money rules

Every side-effecting operation needs a documented idempotency scope including tenant/merchant, intent/attempt, operation, and relevant immutable parameters. A reused key with different inputs rejects. Record enough provider correlation before dispatch to support recovery; if a crash can fall between dispatch and persistence, reconciliation must handle that window. Never generate a new key merely because a response is lost (EXT-003/004).

Money uses exact currency-aware units with explicit currency precision and no floating-point arithmetic. Multi-part voucher funding requires atomic reservations and a durable allocation ledger; only the remaining authorized balance may be attempted. Partial success, ambiguous redemption, expiry, compensation, and concurrent redemption require explicit outcomes (EXT-008). Dispatch versus revocation ordering must be designed in durable state, including the unavoidable uncertainty of an external operation already sent (EXT-009).

## SDK review exit criteria

Before implementation, approve the ports/results, capability registry and version negotiation, operation error/retry table, event trust model, provider-state mappings, credential handling, migration rules, and CT-001 through CT-012 fixtures. Start with a local fake adapter. Provider sandbox adapters require explicit configuration and separate evidence; production connectivity is outside the current milestone.
