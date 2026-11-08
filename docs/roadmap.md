# Platform roadmap

The local synthetic reference is available. The next steps expand safe composition and reproducibility before introducing real provider execution. This is an ordering of work, not a calendar commitment.

| Stage | Current position | Work remaining |
| --- | --- | --- |
| Synthetic payment foundation | Java/PostgreSQL core, AP2 direct TAP authority, embedded ACP checkout, trusted UI, simulator, recovery, explicit service DI and bounded diagnostics are implemented. | Further application/transaction separation and operational visibility. |
| Durable composition | SQLite/Temporal payment workflows include immutable external source handoff and bounded recorded-step diagnostics/replay; durable external commerce and a synthetic provider binding journal are implemented. | Keep provider-journal admission separate until explicitly designed. Full cross-runtime scheduler-consumption replay and production retention remain future work. |
| Conformance and developer preview | SDK/TCK, negative fixtures, recorded replay and requirement reports exist. | Finish conflict normalization and the adversarial/privacy/crash matrix; reproduce clean onboarding and decide licensing/distribution. |
| Provider sandbox integration | Stable provider responsibilities and capability contracts are available. | Select an authorized account, method and region; verify authentication, idempotency, events, reconciliation and unknown outcomes for that exact profile. |
| Broader payment methods | Extension boundaries separate method, credential, operation and customer action. | Add bank/wallet profiles, vouchers, split tender and refunds with explicit reservation, partial-outcome and compensation semantics. |
| Managed service | Product scope includes tenant coordination, visibility and support. | Managed identity, tenant lifecycle, retention, reconciliation tooling, service roles, SLOs and incident ownership. |

## Near-term priorities

1. Extend conformance coverage for the implemented [external checkout authority handoff](external-checkout-authority.md) and [offline workflow replay](workflow-debugging.md), preserving source immutability, current trust and permanent uncertainty barriers.
2. Build on the implemented [service composition and observability](service-composition.md) while extracting further application policy behind replaceable transaction responsibilities. Remote collectors, tracing and production retention remain deferred. Define timed work ownership and stale-worker behavior before adding lease recovery.
3. Close the outstanding conformance scenarios and retain explicit unsupported/partial results. Passing selected checks does not complete the certification matrix.
4. Improve clean-environment setup, package boundaries, compatibility documentation and sanitized transaction visibility.
5. Introduce a first provider sandbox only after its scope and access are established. Local SDK, simulator and contract work can continue independently.

## Release and operating requirements

A developer preview needs reproducible setup, relevant passing checks, a documented capability matrix, known limitations and an explicit license/distribution decision. Source availability alone is not a package release or production service.

A managed or live deployment additionally needs provider approval, tenant and secret-management controls, reconciliation and incident procedures, exposure limits, appropriate legal/security assessment and explicit authorization for the intended live scope. A successful sandbox transaction does not establish that readiness.

See [current capabilities](status.md), [stable backlog items](backlog.md) and the [product brief](product-brief.md).
