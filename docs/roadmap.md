# Delivery roadmap

Status: tested local-fake reference 314bd2f and descriptor/TCK checkpoint 0f60d93 are published. Recorded replay and the offline artifact laboratory now have independent source acceptance and a passing fresh-database aggregate. See [implementation evidence](implementation-evidence.md) and [gate decisions](reviews/gates.md) for exact scope; no full-platform certification, real integration or production release is complete.
Kickoff: 2026-11-06, Europe/Berlin. Day numbers mean relative working days, not calendar-day promises.
The initial horizon is approximately 12 working weeks, subject to review findings, access and available work sessions.
No unattended work or automatic future execution is scheduled by this plan.
The user authorized continued platform implementation, with reviewed designs and tested bounded slices. Day/week labels estimate effort and ordering, not waiting periods; gates advance as soon as their evidence is accepted.

## Actual milestone position

| Milestone | Evidence-backed position | Remaining boundary |
| --- | --- | --- |
| Documentation and initial design | G1 and scoped G2-S accepted; documentation/design branches published. | Later slices require their own design/source acceptance. |
| Local execution reference | 314bd2f published on codex/reference-platform with recorded passing local and hosted checks; Java/PostgreSQL core, HTTP, UI and LangGraph implemented. | Synthetic single tender only; this source milestone is not a released SDK/service or full conformance result. |
| Extensible SDK/TCK | Checkpoint 0f60d93 published after review 0008 and unchanged-source aggregate validation: 67 Java, eight HTTP, three UI and 12 workflow tests, upstream checks and 21-schema validation; SDK bytecode dependency check reports java.base only. | Hosted reference CI 42307004050 and documentation CI 42307004092 passed for this checkpoint. This is tested source/classifier delivery, not an SDK package release, full conformance or real connector. |
| Recorded replay and artifact laboratory | Reviews 0010..0012 accepted; all 11 aggregate stages passed against unchanged source, including 99 Java and 26 lab tests. | Two finite replay recipes and selected crash/clock cases only. Conflict normalization, full adversarial/crash coverage, native AP2 verification and bridge interoperability remain open. |
| Real integrations and managed platform | Versioned source pins and bridge candidate/probes exist; product scope includes consumer/B2B workflows and extensible providers, methods, credentials, actions and stored value. | Real trust/continuation/ownership mapping, approved provider access, method-specific execution, managed operations and live gates remain open. |

The original twelve-week horizon is a planning baseline, not a report that twelve weeks have elapsed. Current work advances by evidence. A milestone branch push records reviewed source delivery; it does not close G3-G5 or publish an SDK package.

## Sequence and accountable roles

Documentation and requirements -> reviewed design -> tested implementation slices -> developer preview -> separately gated managed/live pilots.
Do not skip a failed gate to preserve a target week; replan the affected downstream work.

| Role | Responsibility |
| --- | --- |
| Product owner (user) | Priorities, repository/visibility/license decisions, partner access and commercial pilot decisions. |
| Integration owner | Own the integrated result, review patches/evidence, accept engineering gates and report unresolved risk. |
| GPT-6.1 Sol, high reasoning | Bounded documentation, design proposals, implementation, tests and maintainability work. |
| GPT-6 Astra, normally high reasoning | Complex protocol composition, authority/cryptographic boundaries, concurrency/recovery and independent design reviews. |
| Qualified external specialists, when needed | Assess legal, compliance, payment-provider and production-security obligations; not presumed available. |

Agent assignments are task roles, not staffed capacity or a delivery guarantee. Give each active task disjoint file ownership.
The integration owner records gate evidence and reviews cross-document consistency before advancing.
Plan, design, and code reviews are separate assignments from the respective authors. A plan review does not approve an implementation design or substitute for later independent code review.

## Initial ten-workday sequence

This is the original documentation/design order. Those baseline activities have progressed into implementation; it is not the current next-task queue. Scoped follow-up design remains necessary for new behavior.

| Day | Bounded task and expected evidence | Owner | Dependency / acceptance |
| --- | --- | --- | --- |
| 1 | Establish brief, scope, source register, risk list, roadmap and backlog; label all future artifacts as planned. | Sol / integration owner | Repository instructions read; no secrets or implementation claims. |
| 2 | Validate current AP2/ACP terminology and composition assumptions; record missing immutable pins. | Sol; Astra reviews ambiguity | Cite primary sources; distinguish local-fake behavior from unresolved ACP method/continuation mapping; track real integration work under G2-P. |
| 3 | Specify consumer, B2B and developer journeys, exclusions and acceptance scenarios. | Sol / product owner | Initial scope traceable to stable requirement IDs. |
| 4 | Document provider/method capability matrix and access/licensing blockers; choose candidate first slice. | Sol / integration owner | No inferred vendor or partner access; unsupported combinations explicit. |
| 5 | Review requirements and failure scenarios; publish documentation review findings and resolve baseline blockers. | Independent plan reviewer / integration owner | Gate G1; scope exclusions and later integration blockers explicit; reviewed documentation delivery to the verified remote. |
| 6 | Draft modular architecture, component ownership and payment-vs-checkpoint truth boundaries. | Sol; Astra boundary review | G1 passed; stack remains proposed until ADR review. |
| 7 | Draft state machine, command/event/API contracts, exact-money and idempotency semantics. | Sol; Astra concurrency review | One execution owner; ambiguous outcome cannot trigger duplicate execution. |
| 8 | Draft authentication/customer-action lifecycle and threat model; enumerate trusted surfaces and replay controls. | Astra with Sol documentation | No credential/OTP exposure; expired/denied/returned actions defined. |
| 9 | Draft persistence/recovery model, simulator fixtures and conformance profiles; propose runtime/dependency ADRs. | Sol; Astra recovery review | Atomic reservations, verified events, restart tests and negative fixtures specified. |
| 10 | Resolve independent local-fake design review findings; record gate decision and split first implementation slice. | Integration owner / independent design reviewer | G2-S; no critical local authority/concurrency/recovery gaps; G2-P remains separate. |

Days 1-5 and 6-10 are documentation and design effort estimates, not minimum elapsed durations. Local-fake implementation starts only after G2-S.
Unresolved local authority/concurrency/recovery findings block that implementation. Missing real provider access or complete AP2/ACP wire pins block G2-P and affected external integrations, while independent local-fake work proceeds.

## Twelve-week preview baseline

| Week | Planned milestone and thin slice | Exit evidence | Dependencies |
| --- | --- | --- | --- |
| 1 | Documentation foundation and source-backed requirements. | G1 record; reviewed brief, scope, scenarios, risks and backlog. | Product interpretation, primary-source access. |
| 2 | Local-fake design contracts and independent review. | G2-S record; architecture/API/data model, threat model, runtime ADRs and test plan; G2-P gaps tracked separately. | G1; local authority and execution-ownership decisions. |
| 3 | Small local Java core slice: create intent, validate money/authority and persist a synthetic attempt. | Runnable build; domain and PostgreSQL integration evidence; documented migration/rollback. | G2-S; runtime/toolchain decisions. |
| 4 | Deterministic simulator through first checkout and required customer action. | Happy-path and denied/expired-action evidence; no real credentials. | Core slice and simulator contract. |
| 5 | Recovery slice: reservations, idempotent commands, duplicate events and ambiguous execution. | Concurrent-request, crash/restart and timeout tests; one execution owner proved. | Durable transaction design; event verification model. |
| 6 | Adapter SDK and versioned capability negotiation; synthetic second adapter. | Contract tests reject unknown required capabilities and incompatible adapters. | Stable draft ports; capability matrix. |
| 7 | Python LangGraph reference workflow integrates core and simulator. | Restart/resume end-to-end evidence; graph cannot override payment truth. | Core API and recovery slice. |
| 8 | TypeScript trusted UI and sanitized transaction visibility. | End-to-end action handoff, access control, expiry and redaction evidence. | Action contracts, identity/tenant policy. |
| 9 | Project conformance profiles and reproducible evidence packaging. | G3; passing reference and failing negative fixtures; exact version/profile reports. | SDK, simulator and requirement traceability. |
| 10 | Optional first external sandbox adapter, or deepen simulator if access/design evidence is unavailable. | Explicit sandbox matrix and documented deviations; verified event/reconciliation tests if connected. | Applicable G2-P mapping/pins and provider design; account/terms, region/method availability; never blocks simulator preview by default. |
| 11 | Clean-environment onboarding, compatibility review, security/dependency review and release candidate. | G4 evidence pack; known limitations; rollback/versioning guidance. | Documentation consistency; license/publication decision. |
| 12 | Initial open developer preview when release gates and public licensing/visibility decisions permit. | Tagged versioned artifacts and preview limitations; G5 decision or documented deferral. | All release gates; exact GitHub remote verified; user publication decisions. |

These week labels are effort estimates. Weeks 3-8 already have bounded local implementation evidence; generic SDK work has a focused source review. Weeks 9-12 contain open conformance/readiness/release obligations. The actual position above and current backlog take precedence over the original target order.
External sandbox work may move later without broadening synthetic support claims.
“Open developer preview” describes the intended developer-facing milestone; it is not a selected open-source license or authorization to publish packages.

## Acceptance gates

| Gate | Required evidence | Blocks |
| --- | --- | --- |
| G1: documentation baseline accepted | Reviewed scope/requirements, source register, journeys, capability/access matrix, risks and acceptance scenarios; local-fake versus real integration boundaries explicit; external pins/mapping gaps tracked with owners. No unresolved critical ambiguity in the baseline's scope. | Acceptance of the design baseline; design drafting may proceed during review. |
| G2-S: local-fake simulator design accepted | Independently reviewed local contracts/state machine, authority simulation, sole execution owner, exact money, reservations, idempotency, unknown outcomes, revocation ordering, trusted actions, recovery, privacy, runtime ADRs and test plan. Local-fake mandate/event verification is visibly distinct from real protocol verification. No critical finding in the implementable local scope. | Local-fake implementation and reference slices. Does not authorize real integrations or compatibility claims. |
| G2-P: real protocol/integration design accepted | Pinned upstream contracts/artifacts; permitted field-level AP2/ACP mapping and continuation/unsupported cases; real role/trust/credential/signature/receipt verification; provider assumptions and applicable negative fixtures independently reviewed. Provider-specific scope/access is required only for the integration being attempted. No unresolved critical finding for that scope. | Real AP2/ACP integration, affected external adapter work and external compatibility claims; does not block G2-S. |
| G3: integrated simulator conformant | Deterministic reference flow and failure matrix pass; negative adapters fail correctly; requirement/profile traceability and reproducible reports exist. | Conformance claims and release candidate. |
| G4: preview ready | Clean setup reproduced; relevant builds/tests/security checks pass; docs, compatibility/version policy, dependency/license inventory and known limits reviewed. | Preview release. |
| G5: publication authorized and completed | Exact remote/visibility verified, user license/distribution decisions recorded, reviewed artifacts versioned/tagged, evidence and limitations available. | Public preview announcement/artifact distribution. |

Gate evidence names the reviewed or tested commit/artifacts, relevant contract/suite versions, checks or test commands, environment, outcomes and unresolved limitations. Documentation gates record review evidence; they do not invent executable test results.
Current gate position: G1 accepted; G2-S accepted for the recorded bounded local reference and scoped later authored designs, with new source acceptance still required. G2-P and G3-G5 remain open. Pinning upstream sources or passing an adapter-only TCK does not close those gates.
G3 permits a project-profile statement only; it does not establish vendor, network, PCI or regulatory certification.
Draft documents can be reviewed and pushed as drafts without representing a released specification.

## Later managed and live pilot gates

These are sequenced after the preview and intentionally have no promised dates.

| Gate | Required conditions | Expected output |
| --- | --- | --- |
| M1: managed-service design | Tenant isolation, identity/authorization, data lifecycle, support ownership, SLOs, incident handling, costs and service/payment roles reviewed. | Reviewed service design and operational backlog. |
| M2: sandbox pilot | Approved provider/partner access and terms; named merchant/region/method scope; authentication, reconciliation, refunds where in scope and failure drills verified. | Scoped sandbox evidence with coverage and exclusions. |
| M3: production/live authorization | Explicit user live-pilot authorization; applicable legal/compliance/security assessment; production provider/partner approval; secrets/operations/incident/rollback readiness. | Written go/no-go for limited live scope and participants. |
| M4: controlled live pilot | Exposure limits, monitoring, reconciliation, escalation and stop conditions operating; agreed support coverage staffed. | Pilot evidence and expansion/stop decision. |
| M5: wider managed availability | Pilot findings resolved, commercial/support/privacy terms and capacity accepted; recurring controls and ownership assigned. | Separately approved availability decision. |

Provider availability, payment-method geography, onboarding, licensing and partner agreements are external critical-path dependencies.
Uber Pay remains a partner-access track; substitute simulated coverage until a permitted interface and sponsorship exist.
Live pilot readiness cannot be inferred from simulator conformance or a successful sandbox charge.

## Incremental GitHub delivery

Prepare focused local milestone commits after review: documentation, design, then one tested vertical slice at a time.
The user supplied and authorized incremental pushes to `https://github.com/manishgh/agentic-payments.git`; that remote was initially empty and now contains published milestone history. Do not create another repository, force-push or overwrite history.
Before a push, recheck destination/branch/history as appropriate and ensure no workspace-private material or secrets are included. Record visibility for the publication scope; remote delivery of reviewed drafts is distinct from selecting a license or publishing a release.
Push reviewed branches at meaningful gates; capture the commit and validation evidence in the task report.
Push authorization does not authorize merging, deployment, package publication, license choice or live payment activity.

## Replanning and next work

At each gate, update actual status and estimates in the [backlog](backlog.md), retaining stable task IDs.
Carry unfinished findings forward explicitly; do not mark a task complete merely because its target week elapsed.
Prioritized local work:

1. Deliver the accepted replay/lab checkpoint (IMP-009), then complete conflict-normalization replay, the adversarial/privacy matrix and remaining process-crash boundaries (CON-003..005). The SDK checkpoint's passing hosted CI is historical; the replay/lab checkpoint has its own unchanged-source aggregate evidence and requires its own hosted checks.
2. Complete the composed adversarial/privacy, concurrent-clock and fresh-JVM crash evidence and trace every remaining requirement to its actual result (CON-004/005, CON-001/002). Preserve partial/unsupported results and NOT_CERTIFIED until the applicable gate is met.
3. Extract transaction coordination behind application/domain ports, add reviewed timed lease/recovery behavior, and expand local identity/visibility/operations surfaces in tested slices (IMP-010..013). Existing core safety invariants remain mandatory during refactoring.
4. Complete real protocol design separately, and design additional tender/action/refund profiles with synthetic contract fixtures before any external activation (DES-009/010, INT-003..006). Pins and structural probes are research evidence, not interoperability.
5. Reproduce clean onboarding/package contents and prepare a versioned developer preview once the conformance/readiness and explicit licensing/distribution gates permit it (REL-005/006).

Provider accounts, regional permissions, vendor/partner contracts and public licensing are external dependencies for the steps they govern. They do not block independent local SDK, simulator, contract or managed-design work. The full platform intent remains; the synthetic reference is an intermediate implementation scope, not a reduction of that objective.
See the [product brief](product-brief.md) for scope and exclusions.
