# Platform roadmap

[Documentation](../README.md) / [Project](README.md)
The local synthetic reference is available. The next steps expand safe composition and reproducibility before introducing real provider execution. This is an ordering of work, not a calendar commitment.

| Stage | Current position | Work remaining |
| --- | --- | --- |
| Synthetic payment foundation | Java/PostgreSQL core, AP2 direct TAP authority, embedded ACP checkout, trusted UI, simulator, recovery, explicit service DI and bounded diagnostics are implemented. | Further application/transaction separation and operational visibility. |
| Durable composition | SQLite/Temporal payment workflows include immutable external source handoff and bounded recorded-step diagnostics/replay; durable external commerce, opt-in synthetic provider-journal admission, provider authentication and signed event replay are implemented. | Real provider authentication, event transports and resource-creating preparation remain separate profiles. Full cross-runtime scheduler-consumption replay and production retention remain future work. |
| Conformance and developer preview | SDK/TCK, negative fixtures, recorded replay and requirement reports exist. | Finish conflict normalization and the adversarial/privacy/crash matrix; reproduce clean onboarding and decide licensing/distribution. |
| Provider sandbox integration | Stable provider responsibilities and capability contracts are available. | Select an authorized account, method and region; verify authentication, idempotency, events, reconciliation and unknown outcomes for that exact profile. |
| Broader payment methods | Extension boundaries separate method, credential, operation and customer action. | Add bank/wallet profiles, vouchers, split tender and refunds with explicit reservation, partial-outcome and compensation semantics. |
| Managed service | Product scope includes tenant coordination, visibility and support. | Managed identity, tenant lifecycle, retention, reconciliation tooling, service roles, SLOs and incident ownership. |

## Near-term priorities

1. Extend conformance coverage for the implemented [external checkout authority handoff](../advanced/external-checkout-authority.md) and [offline workflow replay](../how-to/workflow-debugging.md), preserving source immutability, current trust and permanent uncertainty barriers.
2. Build on the implemented [approval and execution transaction views](../concepts/durable-composition.md#application-coordination-and-transaction-ownership) and [service composition](../advanced/service-composition.md). Review remaining expiry/evidence/native transaction phases and broader provider execution profiles as separate slices. Worker control now uses claim generations and monotonic uncertainty; define timed ownership, renewal and takeover before adding lease recovery. Remote collectors, tracing and production retention remain deferred.
3. Close the outstanding conformance scenarios and retain explicit unsupported/partial results. Passing selected checks does not complete the certification matrix.
4. Improve clean-environment setup, package boundaries, compatibility documentation and sanitized transaction visibility.
5. Introduce a first provider sandbox only after its scope and access are established. Local SDK, simulator and contract work can continue independently.

## Release and operating requirements

A developer preview needs reproducible setup, relevant passing checks, a documented capability matrix, known limitations and an explicit license/distribution decision. Source availability alone is not a package release or production service.

A managed or live deployment additionally needs provider approval, tenant and secret-management controls, reconciliation and incident procedures, exposure limits, appropriate legal/security assessment and explicit authorization for the intended live scope. A successful sandbox transaction does not establish that readiness.

See [current capabilities](../introduction/README.md#current-support), [stable backlog items](backlog.md) and the [product brief](../introduction/README.md#what-is-agentic-payments).
