# Delivery backlog

Status snapshot: synthetic reference, standalone ACP lifecycle transport and execution-command extraction independently reviewed and aggregate-tested, 2026-11-08 (Europe/Berlin).
This register distinguishes implemented/tested source, accepted milestones, branch delivery and actual releases. See [current status](status.md), [implementation evidence](implementation-evidence.md) and [gate records](reviews/gates.md) for exact observations. No full-platform certification, real integration or production release is complete.
Stable IDs are retained across replanning; add findings as new rows rather than renumbering existing work.
The integration owner updates status only when the stated evidence exists.

Current follow-on: native AP2/ACP durable admission and shared SQLite/Temporal orchestration are implemented, independently reviewed and verified in a fresh 317-test aggregate. See [composition](durable-composition.md), [runtime](durable-runtime.md) and [status](status.md). These are bounded synthetic profiles; broader AP2 authority, external ACP/provider adapters, Cadence/Azure runtimes and production operation remain separately scoped work.

## Status and ownership

- **In progress**: authorized work being assembled or independently reviewed now.
- **Ready**: a bounded task with prerequisites met; proceed within the current authorized work without waiting for an estimated day/week.
- **Planned**: future task; prerequisites or gate acceptance still outstanding.
- **Blocked**: named external decision/access prevents that task; unrelated work can proceed.
- **Done**: acceptance evidence reviewed and linked in the completion record.
- **Partial**: an explicitly named bounded subset is implemented/accepted; remaining scope is stated in the row or linked evidence.
- **Implemented; acceptance pending**: source and focused tests exist, but independent/final integrated acceptance or delivery evidence is still open. This is not Done.

**Sol** means GPT-6.1 Sol at high reasoning; **Astra** means GPT-6 Astra, normally high reasoning.
**IO** means integration owner; **PO** means user/product owner. These are responsibility labels, not guaranteed staffing.
Latest bounded work: native AP2/ACP application coordination is extracted from JDBC under IMP-010, preserving the transaction boundary and existing adapter contracts. Independent reviews and a fresh 326-test aggregate accept this slice; broader financial-use-case decomposition remains open. The preceding durable composition/runtime checkpoint `5e9d632` is published and passed local and hosted checks. The remaining composed conformance matrix and G2-P stay open; licensing and provider access constrain their applicable releases/integrations, not independent local engineering. User authorization covers continued implementation within reviewed slices, without waiting for estimated weeks.

Bounded follow-on completed 2026-11-08: standalone ACP create/retrieve/update/cancel transport under ADR 0009, and execution-command application/transaction extraction under ADR 0010. Reviews 0025..0028 and the fresh 368-test aggregate accept this slice. Completion/payment dispatch is excluded from the transport. The [provider adapter roadmap](provider-adapter-evolution.md) records the synthetic execution SDK restrictions and required provider-neutral contract evolution before real PSP wiring.

## Documentation foundation — Week 1 / workdays 1-5

| ID | Task / owner | Status | Dependencies | Acceptance evidence |
| --- | --- | --- | --- | --- |
| DOC-001 | Brief, scope, roadmap and stable backlog — Sol / IO | Done | Repository instructions | Reviewed scope, assumptions, planned-vs-implemented labels and estimates. |
| DOC-002 | Verify primary-source terminology and composition assumptions — Sol, Astra review | Done | Source access | Source register identifies current upstream constraints and remaining immutable-pin/mapping gaps; local-fake boundaries explicit. Exact integration pins and real mapping tracked in DES-009/G2-P. |
| DOC-003 | User journeys, requirements and failure scenarios — Sol / PO | Done | DOC-001/002 | Stable requirements cover consumer/B2B/developer, embedded checkout, customer authentication and ambiguous execution. |
| DOC-004 | Provider/method capability and access matrix — Sol / IO | Done | DOC-001/002 | Provider/method/region/action distinctions; sandbox/partner/licensing blockers; first-slice recommendation. |
| DOC-005 | Security, operational and release risk register — Sol, Astra review | Done | DOC-002/003/004 | Named owner/mitigation for authority, duplicate execution, credential handling and release-claim risks. |
| DOC-006 | Independent documentation/plan review and G1 decision — independent reviewer / IO | Done | DOC-001..005 | Baseline scope/scenario findings resolved; review 0002 and integration-owner G1 outcome recorded. Not a design approval. |
| REL-001 | Confirm exact GitHub remote, visibility and history — PO / IO | Done | User destination supplied | Destination `https://github.com/manishgh/agentic-payments.git` supplied and verified empty; record visibility/publication context and pre-push checks in delivery evidence. No user-destination blocker remains. |
| REL-002 | Focused reviewed documentation commit and push — IO | Done | DOC-006; REL-001 for push | Secret/private-file and whitespace/link checks; local commit; non-force branch push when destination verified. |

## Reviewed design — Week 2 / target workdays 6-10

| ID | Task / owner | Status | Dependencies | Acceptance evidence |
| --- | --- | --- | --- | --- |
| DES-001 | Modular architecture and ownership — Sol, independent design review | Done (local profile) | G1 for acceptance; drafting authorized | Core domain/application/ports/infrastructure boundaries; trusted UI and graph/payment truth separated. |
| DES-002 | Versioned local-fake extension contracts and state model — Sol, independent review | Done (local profile) | DES-001 | IDs, transitions, errors and negotiation; project-only continuation explicit; unsupported real ACP mappings tracked in DES-009. |
| DES-003 | Authority, customer action and threat model — design author, Astra review | Done (local profile) | DES-001/002 | Scoped simulated authority, trust boundaries, expiry/replay, 3DS/2FA-style action lifecycle, credential isolation and refusal rules; no real cryptographic claim. |
| DES-004 | Exact money, reservations and recovery design — Sol, Astra review | Done (local profile) | DES-002/003 | Atomic policy reservation, scoped idempotency, local event verification and one execution owner through crashes/timeouts; revocation order defined. |
| DES-005 | Capability and adapter contract design — Sol, independent review | Done (bounded metadata/local profile) | DOC-004; DES-002 | Local ports plus ADR 0003 descriptor/availability/selection and optional-seal TCK contracts; no generic real-execution approval. |
| DES-006 | Simulator and conformance profile design — Sol, independent review | In progress | DES-002..005 | Deterministic scenario catalog, requirement mapping, clock/event controls and incompatible reference fixtures; local-fake labels. |
| DES-007 | Runtime/framework/dependency ADRs — Sol, independent review / IO | Done (local profile) | DES-001..006 for acceptance | Java/Python LangGraph/TypeScript/PostgreSQL proposals reviewed; versions, licenses and dependency rationale recorded for the selected slice. |
| DES-008 | Independent local-fake design review and G2-S decision — Astra / IO | Done (bounded execution) | DES-001..007; G1 | Independent of design author and plan reviewer; no critical local authority/concurrency/recovery findings; implementable first slice and test plan accepted. |
| DES-009 | Real AP2/ACP profile, pins and integration review — design author, Astra review / IO | In progress; bounded native composition implemented | DOC-002; DES-001..005; applicable partner/API evidence | ADR 0005 defines independently authored direct TAP mandates/receipts; ADR 0006 adds synthetic durable admission with embedded ACP. G2-P still requires accepted external wire/continuation/ownership mapping, real trust/consent and scoped partner evidence. The bounded native composition does not close G2-P. |
| DES-010 | Additional tender/action and refund design — Sol, Astra review / IO | Planned | DES-002..005; capability research | Separate voucher payment versus discount; split reservations/per-leg outcomes, bank/wallet actions, original-tender refunds, compensation failure and budget effects specified with negative fixtures before implementation. |
| REL-003 | Reviewed design milestone commit/push — IO | Done | DES-008 for local-fake scope; REL-001 for push | Focused design diff, G2-S scope and remaining G2-P exclusions, validation evidence and recorded branch/commit. |

## Implementation in vertical slices — Weeks 3-8

| ID | Task / owner | Status | Dependencies | Acceptance evidence |
| --- | --- | --- | --- | --- |
| IMP-001 | Local build/CI and first persisted intent slice — Sol / IO | Done (local reference) | G2-S | Reproducible documented build; domain logic independent; exact money validation; PostgreSQL integration and migration evidence; local-fake only. |
| IMP-002 | Simulated embedded checkout and action lifecycle — Sol / IO | Done (local profile) | IMP-001; DES-003/006 | Purchase reaches result; required action can suspend/resume; denied/expired/invalid actions tested. |
| IMP-003 | Idempotent execution and event processing — Sol, Astra review | Done (local profile) | IMP-001/002 | Duplicate commands/events cause no duplicate charge; event verification and scope conflicts tested. |
| IMP-004 | Concurrent reservations and ambiguous-outcome recovery — Sol, Astra review | Done (bounded local reference) | IMP-003 | Published contention, timeout and reconstruction evidence; one execution owner and unresolved exposure maintained. Complete fresh-process/concurrent-clock matrix remains CON-005. |
| IMP-005 | Adapter SDK and capability negotiation slice — Sol / IO | Done (bounded metadata/local TCK) | IMP-004; DES-005 | Checkpoint 0f60d93: local SDK, generic descriptors/schema, independent no-seal fixture, optional-seal TCK and classifier reviewed and aggregate-tested. SDK bytecode depends only on java.base; no package release or generic real execution is implied. Hosted reference CI 42307004050 and documentation CI 42307004092 passed. |
| IMP-006 | LangGraph reference integration — Sol / IO | Done (local profile) | IMP-004/005 | Workflow resumes safely from checkpoints and queries payment truth; no autonomous duplicate execution. |
| IMP-007 | Trusted TypeScript customer action UI — Sol, Astra review | Done (local profile) | IMP-002/006 | Authorized action handoff, expiry/replay/access checks; no OTP/credential passage through model context. |
| IMP-008 | Sanitized transaction visibility — Sol / IO | Partial (local status UI) | IMP-004/007 | Authenticated status/action/checkout views exist; full tenant timeline, operational visibility and access/redaction coverage remain IMP-013/CON-004. |
| IMP-009 | Incremental slice documentation and GitHub delivery — Sol / IO | In progress; incremental checkpoints | Each reviewed slice; REL-001 for push | 69fe8d7/314bd2f/0f60d93/9fd7dbb published with hosted checks. Requirement-evidence delivery has its own source review and aggregate run. Each commit requires its own hosted checks. No package release implied. |
| IMP-010 | Extract application/domain coordination — Sol, Astra review | Partial: native composition and execution-command slices | Published core and ports; regression baseline | ADR 0008/reviews 0023/0024 cover native coordination. ADR 0010/reviews 0026/0028 extract actual execution-command policy through one typed atomic transaction responsibility, preserving historical replay, native ordering and operation/outbox/state/journal atomicity. Approval, worker dispatch and broader admission-backend extraction remain separate. See current evidence for the exact tested source. |
| IMP-011 | Timed work leases and stale-worker recovery — Sol, Astra review | Planned | Reviewed lease design; IMP-004/010 | Current claim fences are not timed leases. Define expiry, ownership/fencing and same-operation reconciliation; test process loss and stale workers without duplicate effects. |
| IMP-012 | Managed identity and tenant lifecycle slice — Sol, Astra review / IO | Planned | Reviewed service/identity design | Replace static local sessions only in a separately gated profile; tenant/subject/grant scope, session revocation, audit access, retention and secret handling tested. |
| IMP-013 | Operational visibility and reconciliation console — Sol / IO | Planned | IMP-008/012; reviewed access/operations design | Tenant-scoped timeline, unresolved exposure queue, safe evidence access, escalation and support ownership; no manual success assertion or unsafe budget release. |
| IMP-014 | Independently authored AP2 direct protocol engine — Sol/root, Astra review / IO | Implemented bounded profile; final evidence recorded separately | ADR 0005; review 0016 | Native SD-JWT VC mandates, role-bound trust, exact references, signed success/error exchange, registered replay guards, reusable Java classifier and independent cross-language vectors. No Google SDK/code reuse, real consent, financial admission or external peer claim. |

Implemented statuses apply only to the named source scope and observed test cases. Generic metadata/TCK extensibility exists; real generic execution profiles, complete certification evidence and managed operation remain unfinished. The Java/Python/TypeScript/PostgreSQL stack is accepted for this reference.
No development task authorizes production credentials, paid services, deployment or live money movement.

## Preview, compatibility and evidence — Weeks 9-12

| ID | Task / owner | Status | Dependencies | Acceptance evidence |
| --- | --- | --- | --- | --- |
| CON-001 | Executable project conformance profiles — Sol / IO | Partial; named evidence implemented | IMP-001..008 | Selected named tests map to CT/EXT IDs under versioned schemas and independently recomputed evidence. Explicit gaps remain; no G3 acceptance. |
| CON-002 | Negative adapter fixtures and independent evidence review — Astra / IO | Partial; bounded fixtures reviewed | CON-001 | Incompatible descriptor/TCK fixtures and evidence-forgery negatives exist. Broader executable adapter profiles and certification policy remain unfinished. |
| CON-003 | Recorded multi-intent replay and independent acceptance — Sol, Astra review / IO | Done (bounded); conflict projection remains | ADR 0003; DES-006 | Durable schedules, ownership, raw invariants, normalized digests, reconstruction and two finite recipes accepted in review 0010 and aggregate-tested. Conflicted/frozen runs fail without digests; broader conflict projection remains open. |
| CON-004 | Complete adversarial event/privacy matrix — Sol, Astra review / IO | Partial; selected event/privacy negatives accepted | Current reference; accepted test design | Review 0014 accepts eight durable event/core privacy methods and one isolated HTTP privacy method. Requirement evidence preserves this subset and named gaps; concurrent delivery and full telemetry coverage remain open. |
| CON-005 | Concurrent-clock and fresh-JVM crash matrix — Sol, Astra review / IO | Partial; selected boundaries accepted | ADR 0002/0003; CON-003 | Six ordered clock cases and one actual JVM journal-commit crash boundary with fresh-process recovery passed and were reviewed. Full crash matrix remains; reconstruction alone retains its narrower label. |
| LAB-001 | Offline signed-artifact/content-binding laboratory — Sol, Astra review / IO | Done (isolated offline scope) | Accepted isolated lab design; pinned sources | Review 0011 accepted merchant JWS/content/schema/handler and synthetic sidecar behavior; 26 lab tests and integrated aggregate passed. Outputs cannot authorize core dispatch or claim native AP2 mandate/interoperability verification. |
| INT-001 | Select first provider/method/region sandbox scope — PO / IO | Planned | DOC-004; DES-005 | Candidate chosen with authorized account, terms, API access and test authentication availability. |
| INT-002 | First optional external sandbox vertical slice — Sol, Astra review | Planned; access/design dependent | INT-001; IMP-005; applicable G2-P mapping/provider design; access | Verified request/event/reconciliation behavior; exact capability matrix and deviations. Local fixtures continue independently; do not substitute simulated evidence for provider support. |
| INT-007 | Standalone external ACP lifecycle adapter — Sol, Astra review | Done (bounded local-peer evidence) | ADR 0009; review 0025 | Responsibility-based create/retrieve/update/cancel contract, bounded HTTP transport, independent local peer and fresh full-schema captures. No complete/provider/financial wiring; source review 0027 and fresh 368-test aggregate pass. |
| INT-008 | Evolve executable provider contract and TCK — Sol, Astra review | Implemented bounded adapter-only contract; publication evidence recorded separately | ADR 0012; review 0030 | [Versioned specification](../specification/provider-execution-1/README.md), additive SDK, compatibility bridge and pinned behavioral suite with independent synthetic fixtures. Includes explicit selected capabilities, immutable resource/operation identity, uncertainty and optional true fencing. Retained-memory reconstruction is not process durability; production journals, vendors and financial integration remain separate. |
| INT-009 | Durable external checkout application wiring — Sol, Astra review | Implemented bounded commerce lifecycle; publication evidence recorded separately | INT-007; ADR 0011; review 0029 | [Durable checkout](durable-external-checkout.md): immutable stage/claim/outcome transactions, outside-lock sending, protected responses, persistent mutation barrier, authenticated API and separate LangGraph/SQLite restart workflow. External completion and AP2/financial admission binding remain unsupported. |
| REL-004 | License and distribution decision — PO / IO | Blocked | User decision; dependency inventory | Explicit license/visibility/distribution policy before public package/spec release; no silent license choice. |
| REL-005 | Clean-environment preview readiness review — Sol, Astra review / IO | Planned | G3; IMP docs | Reproduced setup, build/tests, security/dependency review, limitations and compatibility policy; G4 record. |
| REL-006 | Versioned preview publication — IO / PO | Planned | G4; REL-001/004 | Authorized exact artifacts/versions, tag, evidence bundle and release notes; G5 record or explicit deferral. |
| REL-007 | Preview feedback and compatibility triage — Sol / IO | Planned | REL-006 | Reproducible issues, severity/owner, compatibility impact and next bounded tasks recorded. |

CON-001/002 define **project** conformance only. Any “certified” label requires exact project version/profile and evidence, with explicit limits.
No project label implies upstream protocol, provider, PCI, regulatory or payment-network certification.
INT-002 is optional for the initial simulator preview; absent provider access must narrow claims, not produce fabricated coverage.

## Later managed service and live pilot

| ID | Task / owner | Status | Dependencies | Acceptance evidence |
| --- | --- | --- | --- | --- |
| MNG-001 | Managed platform and support design — IO / PO, Astra review | Planned | Preview findings | Tenant isolation, visibility, data lifecycle, identity, SLOs, costs, incident/reconciliation ownership; M1 record. |
| MNG-002 | Scoped sandbox pilot and operational drills — Sol / IO | Planned | M1; provider approvals | Named scope, failure drills, reconciliation and support evidence; M2 record. |
| MNG-003 | Legal/compliance/security and live authorization — PO / specialists / IO | Planned | M2 | Role assessment, production provider approval, explicit live scope/limits and written M3 go/no-go. |
| MNG-004 | Controlled live pilot — IO / PO | Planned | M3 | Monitoring/support staffed, stop conditions and exposure limits enforced; M4 findings. |
| MNG-005 | Wider managed availability decision — PO / IO | Planned | M4 findings resolved | Commercial/support/privacy commitments and operating capacity accepted; M5 decision. |
| INT-003 | Additional providers and card/bank/iDEAL method profiles — Sol / IO | Planned | Reviewed profiles; real access when connecting | Stripe/Adyen and further provider/method/region/credential/action contracts and synthetic negative fixtures may advance locally; real connectors require access and per-combination evidence. |
| INT-004 | Amazon Pay / PayPal / PayPay wallet tracks — IO / PO | Planned | Vendor-specific contracts; access before connection | Distinct wallet onboarding, method/action profiles, synthetic fixtures and later sandbox evidence; PayPal and PayPay remain separate. |
| INT-005 | Voucher/split-tender/refund execution track — IO / PO, Astra review | Planned | DES-010; issuer/PSP access for real execution | Implement reviewed per-leg reservations, partial/unknown redemption, compensation and refund controls in simulation first; actual issuer/PSP use requires separate permitted interfaces and evidence. |
| INT-006 | Uber Pay partner track — PO / IO | Blocked | Partner sponsorship, docs, access and permission | Permitted interface established before implementation commitments; retain simulation otherwise. |

## Completion records and working rules

For each completed task record: ID, commit, reviewer, exact checks/commands, outcome, evidence location and remaining limitations.
For blockers record: owner, requested decision/access, impact and independent tasks that can proceed.
No task is marked done solely because prose was drafted, a target date passed or a model reported success.
Remaining release/integration blockers do not prevent local development: public license/distribution terms, accepted real protocol/trust mapping, applicable provider/partner access and later operational/live authorization. Exact research pins exist; they are not integration acceptance. The supplied GitHub destination and published milestone history are established.
Runnable build/test commands exist in README and implementation evidence. The aggregate runner uses real PostgreSQL and preserves source/version identities, incomplete obligations and NOT_CERTIFIED; documentation checks alone are not execution evidence.
See the [roadmap](roadmap.md) for gate criteria and [product brief](product-brief.md) for product scope.

## Recorded milestone evidence

- G1 / DOC-001..006: accepted baseline and independent plan review; initial documentation commit 15f0776a2fa1628ac8e77999885cd86811380204.
- REL-001/002: exact public remote verified empty, reviewed baseline pushed to main, hosted documentation CI run 42293811152 passed.
- DES-008: review 0003 initially accepted create/quote/approve/reserve/read; ADR 0002 and later owner acceptance extended the bounded execution/client design. The original gate did not retroactively cover that later scope. See gate records and reviews 0005..0007.
- IMP-001..004/006/007: reference commit 314bd2fe2303892857816944e97a990195581dce published with actual aggregate evidence (47 Java, seven HTTP, three UI and 12 workflow tests at that revision); reference and documentation CI passed. These are historical counts, not the unaccepted current working tree's final result.
- REL-003: design commit 65c39dd0d8fe80a2a8d72fc5680471d414288448 pushed to codex/simulator-design; hosted docs CI 42294677444 passed; reviewer artifact hashes match. Draft PR creation returned integration HTTP403; branch comparison remains available. No merge/deploy.
- IMP-005: checkpoint 0f60d93 published after review 0008, package inspection and unchanged-source isolated aggregate manifest c2b7722352753012f3de99690fbf99d49ed613eb94f1457055d944e752c0c83b. Actual checkpoint evidence: 67 Java, eight HTTP, three UI, 12 graph tests; four upstream integrity tests and 12 structural probes; 21 schemas, 17 positive, 27 schema-negative, six binding-negative and one parser-negative. SDK jdeps reports java.base only. Hosted reference CI 42307004050 and documentation CI 42307004092 passed; current replay/lab working tree is not covered by those checkpoint results.
- CON-003/005 and LAB-001: reviews 0010..0012 accepted bounded source after corrections; fresh aggregate run b46fbaca340140e8a10db197970afa55 passed all 11 stages with stable source hash 66121c775907dbccce4d1ffd439c990ca00e0eda3c60d307f3891efd93fd3d89. Recorded replay has 14 integration and seven plan tests; lab has 26 tests. Conflict projection and the full crash matrix remain open.
- CON-001/002/004 follow-on: reviews 0013..0015 accepted the bounded evidence design, event/privacy tests and source after corrections. Fresh aggregate 7fb95c298b0f4f9aa09e4c096f4e23b3 passed twelve stages and 204 tests at stable source 04a8f6c197987367d8ccfa19f85db54f91adf24c032d68f7de7eb1cc562219ca. All 48 selected observations qualify; eleven CT gaps remain incomplete, with guarded CT-008 optional exclusion and NOT_CERTIFIED retained. Exact source/command/result evidence is in implementation-evidence.md.
- IMP-014: reviews 0016/0017 accepted the independently authored direct AP2 design and source, including the aggregate-discovered three-artifact evidence fix. Fresh run 90b8afaf1c3140f792a9b628d16c35de passed twelve stages and 249 methods at stable source 406fb4e96eb6fad8dec4f4488af5480ad8e082ba3dee434d51c242c6cfd47ea5. Native direct mandates, signed role exchanges and 74 cross-language vectors are implemented; the separate AP2 classifier was inspected and hashed. Durable core/ACP binding and external interoperability remain open. Exact commands/results are in implementation-evidence.md.

## Prioritized remaining work

1. Close the remaining CON-003 conflict projection and CON-004/005 adversarial/privacy/crash matrix with fresh requirement-bound evidence rather than reusing earlier checkpoint results.
2. Extend CON-001/002's selected inventory only after reviewing actual assertions and profile scope. Keep explicit gaps, failing fixtures and exclusions; approve a separate certification policy before considering G3.
3. Design and deliver IMP-010/011, then managed identity/visibility slices IMP-012/013. Preserve current safety invariants and add tests for the new behavior rather than claiming a production service from the local UI.
4. Continue DES-009/010 and local provider/method/action/tender fixtures under INT-003..006. Real integrations depend on accepted mappings and account/partner access; local protocol and SDK work can continue without those accounts.
5. Execute REL-005 clean-environment/readiness review and REL-006 only when G3/G4 and explicit license/distribution decisions permit an actual preview release. Source branch delivery remains distinct from release/publication gates.
