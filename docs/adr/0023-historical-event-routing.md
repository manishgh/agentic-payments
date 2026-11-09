# ADR 0023: Multi-provider event routing and historical configuration

Status: Accepted for the bounded reference implementation.

## Decision

Keep one durable delivery queue per tenant, merchant and run. Route each EVENT through `ProviderEventDeliveryRoutes` using its retained scope, complete `ExecutionBinding` and exact event policy/key references and versions. Select a complete historical handler, including its original transaction registration and verification dependencies. Switching only a signing key is insufficient because the core independently checks its original provider association. The router authorizes scope and checks the resolver's echoed identity before calling the handler.

The immutable reference catalog rejects duplicate route owners and has no default, latest-version or provider-only fallback. External configuration services can implement the same resolver or wrap it. Every consumer of a scope must use a coherent catalog; distributing mutually incomplete one-provider catalogs among competing workers is unsupported. Missing or mismatched configuration consumes only that job's bounded retries; other due routes remain eligible.

V016 stores an immutable nonsecret configuration commitment under the complete route identity. Enrollment is explicit and atomically inserts or compares; verification never enrolls or overwrites. The synthetic descriptor binds the offer, profile, mapping/scenario policies and original registration times. The application checks the commitment before acceptance or event processing. Keys and executable delegates remain in trusted host configuration. The host must preserve the meaning of each key reference/version; the authenticator contract cannot attest that opaque key material or arbitrary replacement code stayed unchanged.

Resolve QUARANTINE separately by tenant, merchant, run, provider and account. It needs neither a historical event configuration nor a signing secret. The keyless reader must resolve exact protected authenticated dispute provenance; the core then validates the original admitted interaction route, full provider/core binding and credential before freezing exposure. Normal financial admission and worker validation are unchanged.

## Consequences

Historical configurations are explicitly reconstructed from trusted host configuration on restart. A persisted commitment is a consistency guard, not an automatically discovered secret or runnable adapter. V016 does not adopt old envelopes, enroll old routes or reset delivery attempts. Generic multi-provider routing is demonstrated with distinct synthetic provider identities; financial end-to-end evidence uses multiple accounts and historical versions of the existing synthetic provider. No live vendor is enabled.

Webhook acceptance remains explicitly wired to a trusted configuration. This slice routes accepted durable work; it does not choose credentials from webhook bodies, change payment admission selection or implement an operator redrive API. See [provider events](../provider-events.md#multi-provider-and-historical-routing) for composition and operational obligations.
