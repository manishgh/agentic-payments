# ADR 0022: Durable automatic event delivery and webhook transport

Status: Accepted for the bounded local synthetic reference.

## Decision

Add explicit automatic acceptance alongside manual retained-reference replay. Authenticate and strictly bind the exact body first, then commit the protected envelope and delivery job together. A webhook acknowledges durable acceptance, never financial success. Existing envelopes are not backfilled or silently enrolled by migration.

Separate caller authentication, acceptance, queue ownership, delivery, original-operation resolution, dispute verification and host lifecycle through versioned responsibility interfaces. Implementations may be direct, adapted or decorated. The PostgreSQL queue, HMAC profile and scheduled Java host are replaceable references.

Delivery uses storage-clock leases, token-fenced acknowledgement, bounded retries and durable terminal failures. Expired final attempts become terminal even if the worker crashed before acknowledgement. Delivery verifies and projects evidence against the original admitted operation; it never obtains financial dispatch ownership or sends another payment. Inbox, provider journal and core retain separate transaction ownership and idempotent recovery.

Authenticated event-ID conflicts publish quarantine jobs for both original owners when either operation participates in automatic delivery. This includes an owner whose event job already completed and an automatically accepted duplicate of an originally manual event. Acceptance and conflict publication serialize their short per-operation decisions. Manual-only history stays manual. A quarantine DTO cannot authorize a freeze: the handler resolves retained authenticated dispute provenance and rechecks the original admitted core/provider binding. It can do so without substituting a current signing key or provider configuration. Journal-only conflicts freeze their current core owner before terminal delivery acknowledgement.

## Consequences

The local composition permits one configured delivery provider/policy per tenant, merchant and run. Multiple consumers may share that same configuration. A multi-provider or historical-policy registry must route jobs by trusted retained bindings through these interfaces; competing differently configured consumers must not share the reference scope. Missing original configuration is retryable and ultimately requires operator attention, never fallback to newer configuration.

The loopback synthetic transport is bounded and separately authenticated. Public ingress, vendor signature profiles, distributed queue implementations, policy registries, retention management and an operator redrive service remain separate work. The host owns scheduling and bounded shutdown; LangGraph continues coordinating the payment workflow rather than treating its checkpoints as payment or delivery truth.

See [provider events](../advanced/provider-events.md) for composition, failure handling and operational limits.
