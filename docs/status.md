# Platform status

The repository provides a runnable synthetic purchasing platform: a Java payment core and SDK, PostgreSQL payment storage, Python LangGraph workflows and a trusted TypeScript approval UI. Execution supports explicit local profiles only. No live merchant or payment provider is enabled.

## Current capabilities

| Area | Available behavior |
| --- | --- |
| Payment coordination | Exact money, scoped command replay, atomic budget reservations, one execution owner, trusted synthetic challenges and retained unknown outcomes. |
| AP2 and embedded ACP | Independently authored direct TAP authority, exact signed checkout binding, current key/grant verification and atomic replay/admission checks. |
| Replaceable components | Versioned checkout, authority, crypto, key, artifact, admission, provider and workflow responsibilities; implementations can be direct, adapted or delegated. |
| Service composition and diagnostics | Explicit constructor DI, validated lifetimes, ordered service wrappers, typed application failures and optional bounded logging/metrics. No-op telemetry is the default; JSON console is a local opt-in. |
| Durable workflows | Payment workflows use SQLite or Temporal. The separate commerce workflow uses LangGraph/SQLite and explicit restart/resume. |
| External commerce | ACP create, retrieve, update and cancel; committed dispatch claims, protected outcomes and a permanent uncertain-mutation barrier. |
| Provider SDK | Separate preparation, submission, reconciliation and event contracts, optional true fencing, a synthetic bridge and a PostgreSQL binding journal. This SDK is separate from financial-core admission. |
| Local verification | PostgreSQL, HTTP, schema, UI, workflow, replacement-adapter and process-recovery checks, with versioned requirement reports. |

The opt-in `local-external-ap2-acp-single-tender-0.1` implementation adds exact external-response binding, a permanent purchase seal, trusted approval and synthetic AP2 execution. Its signature is a local synthetic attestation, not an external merchant signature. See the [external checkout authority guide](external-checkout-authority.md).

## Limits

- Real PSPs, live instruments, real 3DS, delegated/User Credential AP2 profiles and production merchant trust are unsupported.
- External checkout completion is not sent. The external financial handoff currently uses authenticated HTTP and the existing UI; the payment workflow template does not yet accept that profile.
- Uncertain commerce mutations remain blocked, even after a later observation. Financial seals have no automatic release or requote path.
- Additional methods, wallets, split tender, refunds, managed identity and operations require separate implementations and explicit profiles.
- Selected conformance checks exist, but the complete adversarial/privacy/crash matrix and certification policy remain unfinished. The aggregate verdict remains `NOT_CERTIFIED`.
- Public licensing, package distribution and managed-service operation remain open decisions. Synthetic results establish no provider, upstream protocol, PCI, regulatory or network certification.

## Next work

The [service-composition reference](service-composition.md) is implemented; remote telemetry collectors, tracing and production retention remain deferred. The external profile needs a durable workflow handoff with immutable source registration. Continue application/transaction separation, complete missing conformance scenarios and prepare clean developer onboarding. Provider sandbox work requires an explicitly selected account, method, region and verified recovery behavior.

Use the [README](../README.md) for setup and checks, the [roadmap](roadmap.md) for sequencing and the [backlog](backlog.md) for stable work identifiers.
