# Architecture decisions

[Documentation](../README.md)

Historical rationale for design choices. Start with [core concepts](../concepts/README.md) for the current user-facing explanation.

- [ADR 0001: Bound the first preview to one synthetic tender](0001-simulator-preview.md)
- [ADR 0002: Durable execution for the local simulator](0002-simulator-execution.md)
- [ADR 0003: Deterministic replay and reusable adapter conformance](0003-replay-and-adapter-conformance.md)
- [ADR 0004: Requirement-level evidence for the local reference](0004-requirement-evidence.md)
- [ADR 0005: Independently authored AP2 direct protocol engine](0005-independent-ap2-engine.md)
- [ADR 0006: Durable native AP2 authority for embedded ACP admission](0006-durable-ap2-admission.md)
- [ADR 0007: Replaceable durable workflow runtimes](0007-durable-workflow-runtime.md)
- [ADR 0008: Extract native application artifact and verification coordination](0008-native-application-coordination.md)
- [ADR 0009: Add a bounded external checkout lifecycle adapter](0009-external-checkout-adapters.md)
- [ADR 0010: Extract execution-command policy from the transaction adapter](0010-payment-transaction-boundary.md)
- [ADR 0011: Durable external checkout lifecycle integration](0011-durable-external-checkout.md)
- [ADR 0012: Provider-neutral execution contract](0012-provider-execution-contract.md)
- [ADR 0013: Durable synthetic provider execution journal](0013-durable-provider-journal.md)
- [ADR 0014: Bind external checkout observations to synthetic AP2 authority](0014-external-checkout-authority-binding.md)
- [ADR 0015: Service composition, observability and application failures](0015-service-composition-observability-errors.md)
- [ADR 0016: Register external checkout sources before durable payment orchestration](0016-external-checkout-workflow-handoff.md)
- [ADR 0017: Bounded workflow diagnostics and read-only recorded-step replay](0017-workflow-debugging-replay.md)
- [ADR 0018: Extract approval policy through the atomic payment transaction](0018-approval-application-transaction.md)
- [ADR 0019: Worker transaction ownership and stale-result fencing](0019-worker-transaction-ownership.md)
- [ADR 0020: Bind the synthetic provider journal to financial admission](0020-provider-journal-admission.md)
- [ADR 0021: Durable provider authentication and verified events](0021-provider-authentication-events.md)
- [ADR 0022: Durable automatic event delivery and webhook transport](0022-automatic-provider-event-delivery.md)
- [ADR 0023: Multi-provider event routing and historical configuration](0023-historical-event-routing.md)
