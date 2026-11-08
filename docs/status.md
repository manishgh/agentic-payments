# Platform status

The repository provides a runnable synthetic purchasing platform: a Java payment core and SDK, PostgreSQL payment storage, Python LangGraph workflows and a trusted TypeScript approval UI. Execution supports explicit local profiles only. No live merchant or payment provider is enabled.

## Current capabilities

| Area | Available behavior |
| --- | --- |
| Payment coordination | Exact money, scoped command replay, atomic budget reservations, one execution owner, trusted synthetic challenges and retained unknown outcomes. |
| Application transaction boundary | Approval and execute-command policy run through typed views of one atomic owner, with direct/adapted/wrapped application tests and PostgreSQL rollback/concurrency evidence. Native transaction integration and workers remain in the reference store. |
| AP2 and embedded ACP | Independently authored direct TAP authority, exact signed checkout binding, current key/grant verification and atomic replay/admission checks. |
| Replaceable components | Versioned checkout, authority, crypto, key, artifact, admission, provider and workflow responsibilities; implementations can be direct, adapted or delegated. |
| Service composition and diagnostics | Explicit constructor DI, validated lifetimes, ordered service wrappers, typed application failures and optional bounded logging/metrics. No-op telemetry is the default; JSON console is a local opt-in. |
| Durable workflows | Payment workflows use SQLite or Temporal. The separate commerce workflow uses LangGraph/SQLite and explicit restart/resume. |
| Durable source handoff | External payment workflows register the exact purchase/revision/response tuple before core work, preserving old workflow fingerprints and Temporal history compatibility. |
| Workflow debugging | Bounded invocation journal, safe failure classification, read-only export and offline recorded-step/decision replay for new modern-runtime registrations. |
| External commerce | ACP create, retrieve, update and cancel; committed dispatch claims, protected outcomes and a permanent uncertain-mutation barrier. |
| Provider SDK | Separate preparation, submission, reconciliation and event contracts, optional true fencing, a synthetic bridge and a PostgreSQL binding journal. This SDK is separate from financial-core admission. |
| Local verification | PostgreSQL, HTTP, schema, UI, workflow, replacement-adapter and process-recovery checks, with versioned requirement reports. |

The opt-in `local-external-ap2-acp-single-tender-0.1` implementation adds exact external-response binding, a permanent purchase seal, trusted approval and synthetic AP2 execution. Its signature is a local synthetic attestation, not an external merchant signature. See the [external checkout authority guide](external-checkout-authority.md).

## Limits

- Real PSPs, live instruments, real 3DS, delegated/User Credential AP2 profiles and production merchant trust are unsupported.
- External checkout completion is not sent. The external financial handoff uses authenticated HTTP with immutable SQLite/Temporal registration; the older standalone payment graph remains closed to this profile.
- Offline replay validates recorded steps, not current payment truth or which late retry result a scheduler consumed. Historical missing coverage and unresolved invocations remain explicit.
- Uncertain commerce mutations remain blocked, even after a later observation. Financial seals have no automatic release or requote path.
- Additional methods, wallets, split tender, refunds, managed identity and operations require separate implementations and explicit profiles.
- Selected conformance checks exist, but the complete adversarial/privacy/crash matrix and certification policy remain unfinished. The aggregate verdict remains `NOT_CERTIFIED`.
- Public licensing, package distribution and managed-service operation remain open decisions. Synthetic results establish no provider, upstream protocol, PCI, regulatory or network certification.

## Next work

The [service-composition reference](service-composition.md), immutable external workflow handoff and [bounded debugging/replay](workflow-debugging.md) are available. Remote telemetry collectors, distributed tracing and production retention remain deferred. Continue application/transaction separation, complete missing conformance scenarios and prepare clean developer onboarding. Provider sandbox work requires an explicitly selected account, method, region and verified recovery behavior.

Use the [README](../README.md) for setup and checks, the [roadmap](roadmap.md) for sequencing and the [backlog](backlog.md) for stable work identifiers.
