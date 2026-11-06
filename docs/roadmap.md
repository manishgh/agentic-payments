# Delivery roadmap

Status: documentation baseline accepted; first local-fake slice design independently reviewed with limited G2-S acceptance. No payment implementation is complete. See [gate decisions](reviews/gates.md) for exact scope and mandatory bootstrap; later design/implementation gates remain open.
Kickoff: 2026-11-06, Europe/Berlin. Day numbers mean relative working days, not calendar-day promises.
The initial horizon is approximately 12 working weeks, subject to review findings, access and available work sessions.
No unattended work or automatic future execution is scheduled by this plan.
The user authorized continued design in the current session. Day/week labels estimate effort and ordering, not waiting periods; gates advance as soon as their evidence is accepted.

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

## First ten workdays

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

## Twelve-week initial preview plan

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

Weeks 3-12 are estimates for future work, not current implementation status.
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
The user supplied and authorized incremental pushes to `https://github.com/manishgh/agentic-payments.git`; the integration owner verified that remote as empty. Do not create another repository, force-push or overwrite history.
Before a push, recheck destination/branch/history as appropriate and ensure no workspace-private material or secrets are included. Record visibility for the publication scope; remote delivery of reviewed drafts is distinct from selecting a license or publishing a release.
Push reviewed branches at meaningful gates; capture the commit and validation evidence in the task report.
Push authorization does not authorize merging, deployment, package publication, license choice or live payment activity.

## Replanning and next work

At each gate, update actual status and estimates in the [backlog](backlog.md), retaining stable task IDs.
Carry unfinished findings forward explicitly; do not mark a task complete merely because its target week elapsed.
Current next bounded work: finish **DOC-006** baseline review and **DES-001..007** local-fake design, then independent **DES-008** review for G2-S. Track exact real protocol pins and mapping under **DES-009/G2-P** rather than blocking unrelated simulator design.
See the [product brief](product-brief.md) for scope and exclusions.
