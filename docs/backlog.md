# Platform backlog

Stable identifiers are retained for platform capabilities and unfinished work. Implemented means the stated bounded reference behavior exists; it does not mean production readiness or complete conformance. External checkout authority binding is implemented for the opt-in synthetic profile.

## Current foundations

| IDs | Capability | Current scope |
| --- | --- | --- |
| DOC-001, DOC-002, DOC-003, DOC-004, DOC-005 | Product scope, protocol references, requirements, capability matrix and risk model | Maintained platform documentation; real integration claims require their own exact profile and evidence. |
| DES-001, DES-002, DES-003, DES-004, DES-005, DES-007 | Architecture, contracts, authority, exact money, recovery, capabilities and stack | Defined for the local reference. Java, Python LangGraph, TypeScript and PostgreSQL are the selected stack. |
| IMP-001, IMP-002, IMP-003, IMP-004 | Persisted payment, checkout, actions, reservations, idempotency and recovery | Implemented for synthetic single tender. Full crash coverage remains under CON-005. |
| IMP-005 | Adapter SDK and capability negotiation | Local SDK, descriptors and reusable TCK; no generic real-provider execution claim. |
| IMP-006, IMP-007 | Durable reference workflow and trusted UI | SQLite/Temporal payment orchestration and authenticated synthetic approval/challenges. |
| IMP-014 | Independent AP2 direct protocol engine | Native mandates, role-bound verification, receipts and independent vectors for the named synthetic direct TAP profile. |
| LAB-001 | Offline artifact/content-binding laboratory | Isolated signature, content, schema and handler checks; its outputs cannot authorize core dispatch. |
| INT-007 | External ACP lifecycle adapter | Create/retrieve/update/cancel over bounded HTTP; no remote completion. |
| INT-008 | Provider execution contract and TCK | Versioned preparation/submission/reconciliation/event responsibilities, uncertainty and optional true fencing. |
| INT-009 | Durable external commerce wiring | Scoped immutable commands, committed claims, protected outcomes, uncertainty barrier and separate LangGraph/SQLite workflow. |
| INT-010 | Durable synthetic provider binding journal | PostgreSQL ownership, strict persisted identity, retained conflicting evidence and process recovery behind the existing journal interface. |

## Composition and runtime work

| ID | Work | Status and completion criteria |
| --- | --- | --- |
| INT-011 | External checkout authority binding | Implemented for the synthetic profile: exact retained response bytes bind to one permanently sealed purchase/intent, trusted approval and synthetic AP2 execution. Add immutable durable graph handoff separately; no merchant completion or production trust claim. |
| IMP-015 | Service DI, logging, metrics and application failures | Design accepted in [ADR 0015](adr/0015-service-composition-observability-errors.md); implementation outstanding. Constructor injection, explicit lifetimes, ordered wrappers, generic replaceable telemetry providers and transport-independent typed failures must preserve payment results and recovery. |
| IMP-010 | Application/domain coordination | Partial. Native artifact coordination and execute-command policy use application responsibilities. Extract remaining approval, worker dispatch and admission policy while preserving atomic replay/grant/reservation and historical command behavior. |
| IMP-011 | Timed work ownership and stale-worker recovery | Planned. Define leases/fences and same-operation reconciliation; prove that process loss or a delayed worker cannot authorize a second effect. Current claims are not timed leases. |
| IMP-008 | Sanitized transaction visibility | Partial. Local status/action/checkout views exist; full tenant timelines, unresolved exposure and access/redaction coverage remain. |
| IMP-012 | Managed identity and tenant lifecycle | Planned. Replace static local sessions through an explicit profile with tenant/subject/grant scope, revocation, retention and secret handling. |
| IMP-013 | Reconciliation and operations console | Planned. Provide tenant-scoped unresolved-exposure queues, safe evidence access and escalation; never permit manual success assertions or unsafe exposure release. |
| DES-009 | External AP2/ACP trust and interoperability | Partial. Pinned specifications and synthetic native/external composition exist. Real peer trust, consent, wire/continuation mapping and compatibility evidence remain open. |
| DES-010 | Additional tender/action and refund semantics | Planned. Distinguish vouchers from discounts; define per-leg reservations, partial/unknown outcomes, bank/wallet actions, original-tender refunds and compensation failures before execution support. |

## Conformance and preview

| ID | Work | Status and completion criteria |
| --- | --- | --- |
| DES-006 | Simulator and conformance profiles | Partial. Expand deterministic scenarios and requirement mappings with explicit unsupported cases. |
| CON-001 | Executable project conformance profiles | Partial. Named observations and recomputable reports exist; complete the remaining requirement inventory and profile coverage. |
| CON-002 | Negative adapters and certification policy | Partial. Incompatible adapters and evidence-forgery negatives exist; broader executable profiles and certification policy remain unfinished. |
| CON-003 | Recorded multi-intent replay | Partial. Durable schedules, invariants and normalized digests exist. Complete conflict normalization; conflicted/frozen runs currently refuse a digest. |
| CON-004 | Adversarial event/privacy matrix | Partial. Selected negatives exist; finish concurrent delivery and telemetry/redaction coverage across all relevant surfaces. |
| CON-005 | Concurrent-clock and fresh-JVM crash matrix | Partial. Selected ordered-lock, SQL rollback and actual process-loss cases exist; cover the remaining boundaries without substituting object reconstruction for process durability. |
| REL-004 | License and distribution | Decision required before package/spec release; include dependency redistribution policy. |
| REL-005 | Clean-environment preview readiness | Planned. Reproduce setup and relevant checks; verify package contents, compatibility policy, dependencies and documented limits. |
| REL-006 | Versioned preview publication | Planned after readiness and distribution decisions. Publish exact authorized versions/artifacts and limitations. |
| REL-007 | Preview feedback and compatibility | Planned. Reproduce reported failures, assess compatibility impact and prioritize fixes. |

The aggregate verdict remains `NOT_CERTIFIED`. Project conformance does not confer upstream protocol, provider, PCI, regulatory or network certification.

## Providers, methods and managed operation

| ID | Work | Status and dependencies |
| --- | --- | --- |
| INT-001 | First provider/method/region scope | Planned. Select an authorized account, applicable APIs/terms and test authentication path. |
| INT-002 | First optional sandbox vertical slice | Planned. Verify exact request/event/reconciliation behavior and capability limits. Depends on INT-001 and the applicable trust/execution design. |
| INT-003 | Additional provider/card/bank/iDEAL profiles | Planned. Design each provider × method × region × credential × action combination; real connectors require access and specific evidence. |
| INT-004 | Amazon Pay, PayPal and PayPay wallets | Planned as distinct integrations with their own contracts, onboarding and test environments. |
| INT-005 | Voucher, split-tender and refund execution | Planned after DES-010. Implement synthetic per-leg/partial-outcome and compensation controls before connecting issuer/PSP interfaces. |
| INT-006 | Uber Pay partner integration | Access dependent. Requires sponsorship, permitted documentation and contractual access before connection. |
| MNG-001 | Managed platform/support design | Planned. Tenant isolation, identity, data lifecycle, visibility, SLOs, cost and incident/reconciliation ownership. |
| MNG-002 | Sandbox pilot and operational drills | Planned after service design and provider approval; name the precise scope and recovery/support obligations. |
| MNG-003 | Live operating authorization | Planned. Establish legal/compliance/security roles, production provider approval and explicit live scope/limits. |
| MNG-004 | Controlled live pilot | Planned after MNG-003; staffed monitoring/support, stop conditions and exposure controls required. |
| MNG-005 | Wider managed availability | Planned after pilot findings are resolved and operating capacity, commercial/support and privacy commitments are established. |

Provider access and licensing constrain the work they govern; they do not prevent independent local contract, SDK and simulator development. See the [roadmap](roadmap.md) for sequencing and [README](../README.md) for setup and verification commands.
