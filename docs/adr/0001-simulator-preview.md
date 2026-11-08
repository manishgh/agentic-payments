# ADR 0001: Bound the first preview to one synthetic tender

Status: accepted for the bounded reference profile described below.
Original decision: 2026-11-06. This records the initial synthetic profile; subsequent ADRs extend protocol and integration support.
Scope: internal `local-fake-single-tender-0.1` profile against `0.1.0-draft` project requirements.

## Context

The product will compose an enclosing AP2-based payment intent with embedded OpenAI ACP checkout and provider authentication.
External integrations require explicit signed-byte, trust, continuation and execution-owner contracts.
Current inspected upstream contracts do not provide a proven general bridge for all internal methods/customer actions.
Implementing a real connector before resolving those points would turn assumptions into financial and compatibility behavior.

A simulator can validate local authority/reservation/execution invariants without pretending to verify AP2 signatures or change ACP schemas.
The reference uses Java core/SDK, Python LangGraph, TypeScript trusted UI and PostgreSQL.
The first implementation must remain small enough for independent review and failure-driven tests.

## Recommended decision

Use the reference stack with versions and dependency locks documented in the [toolchain guide](../toolchain.md).
Use one modular Java core with domain/application/ports/infrastructure boundaries and one authoritative PostgreSQL store.
Python LangGraph calls authenticated internal commands and polls status; TypeScript is the trusted local approval/action surface.
Core/adapter modules have no dependency on graph state or UI. Domain types depend on neither HTTP nor SQL/provider libraries.
The first slice is local-only, synthetic, single provider, single tender, no external HTTP adapter or live endpoint configuration.
Implement [API contracts](../api-contracts.md) and [transaction model](../data-model.md) after G2-S acceptance.

Recommended core baseline is Temurin Java 21 LTS language/API target, Maven 3.9.16 and PostgreSQL 17.11; a supported baseline, not a latest-version claim.
Runtime support references: official [Maven maintenance history](https://maven.apache.org/docs/history.html) and [PostgreSQL support table](https://www.postgresql.org/support/versioning/).
The official [Adoptium support policy](https://adoptium.net/support) lists Java 21 support through at least December 2029; pin an actual available binary/checksum during bootstrap.
The repository contains verified toolchain bootstrap and component dependency locks. See the [toolchain guide](../toolchain.md) for exact versions and setup, and the [verification guide](../requirement-evidence.md) for source-bound checks.

## Selected profile

| Axis | First-preview scope | Explicitly unsupported |
| --- | --- | --- |
| Version/profile | `0.1.0-draft` / `local-fake-single-tender-0.1` | Automatic version or security downgrade. |
| Provider/method | `simulator` / `synthetic-card` | Real Stripe/Adyen/wallet/bank/voucher/Uber Pay calls. |
| Money | Full positive EUR/USD integer-minor-unit total, exponent 2 | FX, partial amount, fees, mutable quote after approval. |
| Tender | One allocation equal to total | Split tender, voucher-plus-card, partial funding. |
| Authority | Server fixture grant and bound `local-fake` approval | Real AP2 cryptographic/signature/trust verification. |
| Customer action | Synthetic trusted approve/deny challenge before admission | Real 3DS, OTP, wallet login, bank redirect. |
| Execution | One synthetic full-charge operation, immutable owner/key | Separate auth/capture, recurring payment, owner transfer. |
| Recovery | Adapter journal/query/seal and verified internal events | Treating timeout/NOT_FOUND as no effect. |
| Remediation | Reconciliation and inspectable unresolved state | Refund, reversal, provider cancel, automatic compensation. |
| Commerce | Server-owned synthetic checkout snapshots/order references | Live ACP merchant checkout, fulfillment or production order promises. |

The UI and reports must visibly say `local-fake`; synthetic authentication is not a real provider authentication claim.
Unknown required capabilities reject before reservation/execution; optional unsupported profiles are declared, never implied (EXT-001/002).

## Execution and authority decisions

1. Approval binds immutable checkout/total/merchant/method/credential/subject and creates the sole attempt with owner `simulator-core`.
2. Approval locks grant, budget and intent in one transaction, reserves the full total, and records synthetic authority binding.
3. Execute creates a stable operation/key and queues effect-free preparation, including optional trusted customer action.
4. A separate worker transaction rechecks authority/capability/action and commits dispatch admission before the adapter send.
5. Dispatch admission is the durable ordering point versus revocation. Revocation blocks later admissions, not guaranteed effects of earlier admissions.
6. Post-admission timeout/crash produces UNKNOWN with held exposure; no new attempt, owner, route or key is generated.
7. The synthetic adapter journal deduplicates keys and can atomically seal a not-accepted operation as definitive no-effect.
8. Success consumes the reservation; sealed definitive no-effect releases it. NOT_FOUND alone cannot release it.
9. Late matching success resolves uncertainty even after revocation; contradictory final facts freeze the budget and fail conformance.
10. Trusted action completion is separately authenticated and bound; graph checkpoints contain only authorized safe references.

The adapter's atomic journal/sealing capability is an explicit local assumption, not an advertised real-provider guarantee.
No database transaction is held across an adapter call; recovery crosses the core/journal commit gap with the same identity.
Leases fence core worker mutations but cannot unsend an external request; stable operation idempotency remains necessary.
No universal exactly-once delivery or atomic distributed rollback claim is made.

## G2-S: bounded simulator design gate

The bounded simulator contract requires the following behaviors.
Passing G2-S permits only a reviewed design for create/quote/approve/reserve/read; it does not pass G2-P or certify an adapter.
Dispatch, customer-action execution and recovery remain proposed designs requiring a separate senior review before their implementation slices.

| Criterion | Required design evidence |
| --- | --- |
| Scope/version | Exact local-fake profile, money bounds, capability matrix and explicit unsupported split/refund/real-protocol paths. |
| Contracts | Reviewed request/response/error schemas, idempotency rules, tenant roles and command-state preconditions. |
| Binding | Exact API field rules and 402-byte canonical fixture/digest, immutable checkout/authority/owner bindings, negative mutation cases. |
| Ownership | One attempt/charge identity; no graph/merchant/browser duplicate owner or implicit reroute. |
| State model | Allowed transitions, unknown exposure, terminal evidence, late callbacks and conflict handling. |
| Atomicity | Run-clock row serializes all expiry/clock mutations; tenant+run scopes, grant/budget/admission/revocation order and restart windows defined. |
| Adapter assumptions | Independent durable journal, duplicate handling, query/atomic seal semantics and race traces. |
| Trusted action | Session/subject/tenant/expiry/single-use binding; no action secret or credential in graph state. |
| Tests/evidence | CT mapping, deliberate negative fixtures and deterministic clock/schedule/replay plan. |
| Toolchain | Supported core targets selected; mandatory IMP-001 bootstrap pins/verifies distributions, checksums and dependency/license inventory before any application source. |
| Review discipline | Independent reviewer findings tracked; critical findings closed; acceptance names exact document/commit revisions. |

G2-S has not passed merely because this ADR is written. Exact field/canonical-byte rules and core toolchain targets are documented; independent acceptance is pending.
DR-004 is separated into mandatory IMP-001 bootstrap evidence: verify actual tool availability, distribution/checksum pins and dependency/license inventory before application source.
No local runtime/build is claimed. Missing installation does not imply incomplete design, but it blocks source scaffolding until bootstrap checks succeed.
The first implementation slice should create/quote/approve/reserve/read using one isolated fixture, before adding dispatch or actions.
Each later side-effect/recovery slice requires its own design/code review and tests; G2-S is not blanket approval for unreviewed changes.

## G2-P: real protocol/provider gate

G2-P remains open and is separate from G2-S. No AP2/ACP/provider compatibility claim follows from simulator success.

1. Pin exact AP2/ACP normative revisions/schema artifacts and hashes, not only URLs or retrieval dates.
2. Map real roles/organizations, enrollment, signer identities, credential responsibilities and trust/key-discovery/revocation policy.
3. Define exact native signed bytes, serialization/hash binding, mandate/receipt references and cross-language positive/negative fixtures.
4. Map every internal field/state to permitted upstream fields or an explicit negotiated extension/authenticated side channel.
5. Resolve current ACP provider/method restrictions and missing general pending/challenge completion representation without invented enum values.
6. Assign merchant/core/external execution ownership and prevent independent PSP execution on checkout timeout.
7. Verify actual provider idempotency/lookup/event/cancel behavior and retention limits; never assume simulator sealing exists.
8. Design real trusted customer-action origin/session/callback verification and secrets/vault boundaries.
9. Confirm authorized sandbox/partner access, terms, supported region/method and dependency redistribution rights.
10. Obtain independent protocol/security/concurrency review and scoped interoperability evidence before a compatibility assertion.

If an upstream mapping is absent, keep the combination unsupported. Experimental bridge work remains isolated from advertised profiles.
Managed/live operation additionally requires M1-M5 operational/legal/security/live-authorization gates; G2-P is not live authorization.

## Requirement and test traceability

| Requirements / cases | Design response |
| --- | --- |
| EXT-001/002; CT-001/002 | Exact profile and capability intersection; required unknown combinations reject. |
| EXT-003; CT-003 | Immutable sole attempt/owner/operation; scoped command replay and stable adapter journal key. |
| EXT-004; CT-004 | UNKNOWN preserves exposure; same-operation query/reconciliation; no route switch or NOT_FOUND release. |
| EXT-005; CT-005 | Synthetic trusted challenge with tenant/subject/attempt/operation/digest/expiry binding; no real action-profile claim. |
| EXT-006; CT-006 | Authenticated internal facts, inbox deduplication, sequence guards, late success and conflict quarantine. |
| EXT-007; CT-007 | Local-fake immutable approval bindings; real signed-byte/trust verification reserved for G2-P. |
| EXT-008; CT-008 | Partial/voucher profile not claimed; baseline rejects split/underfunded tender before reservation. |
| EXT-009; CT-009 | Grant lock linearizes revoke and dispatch admission; previously admitted exposure remains held. |
| EXT-010; CT-010 | Graph-safe status, isolated fixture credential/session storage and synthetic canary leak tests. |
| EXT-011; CT-011 | Injected clock, seeded scenario plus explicit ordered schedule, isolated run and durable replay identities. |
| EXT-012; CT-012 | Exact version/profile/evidence labels; unsupported tests distinguished from passed tests; no external accreditation. |

## Alternatives considered

| Alternative | Reason not selected for the first slice |
| --- | --- |
| Implement live AP2/ACP and PSP integration immediately | Unresolved trust/state mapping and external dependencies obscure local safety invariants. |
| Implement split tender first | Independent legs require partial-success accounting and compensation; reject until separately designed. |
| Put payment state in LangGraph checkpoints | Replay/checkpoint ownership cannot replace atomic reservations or durable payment evidence. |
| Switch route after timeout | Earlier operation may have charged; duplicates financial exposure. |
| Build all provider adapters before a vertical slice | Broad scaffolding lacks tested behavior and increases maintenance/review scope. |
| Use an in-memory-only adapter journal | Cannot exercise provider-success/core-crash recovery reliably across restart. |

## Consequences and limitations

The preview can test local safety/recovery and developer contracts without provider access or money movement.
Its synthetic journal gives stronger capabilities than some real providers; future adapters may remain UNKNOWN and need manual investigation.
Single tender simplifies accounting but excludes major planned method combinations; extensibility is contractual, not implemented coverage.
The initial Java/Python/TypeScript/PostgreSQL split adds toolchain work; implement clients incrementally after the core slice is verified.
Runtime versions and dependencies must be checked against official maintained documentation rather than invented “latest” versions.
Public licensing remains unresolved; no package/spec release or open-source license is silently authorized.
The confirmed GitHub remote permits reviewed branch pushes; it does not authorize merges, deployment or live execution.
