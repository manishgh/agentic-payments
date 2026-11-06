# Delivery backlog

Status snapshot: documentation baseline accepted (G1); detailed design in progress, 2026-11-06 (Europe/Berlin).
This is a planning register. No implementation, provider integration or certification is complete.
Stable IDs are retained across replanning; add findings as new rows rather than renumbering existing work.
The integration owner updates status only when the stated evidence exists.

## Status and ownership

- **In progress**: authorized work being assembled or independently reviewed now.
- **Ready**: a bounded task with prerequisites met; proceed within the current authorized work without waiting for an estimated day/week.
- **Planned**: future task; prerequisites or gate acceptance still outstanding.
- **Blocked**: named external decision/access prevents that task; unrelated work can proceed.
- **Done**: acceptance evidence reviewed and linked in the completion record.

**Sol** means GPT-6.1 Sol at high reasoning; **Astra** means GPT-6 Astra, normally high reasoning.
**IO** means integration owner; **PO** means user/product owner. These are responsibility labels, not guaranteed staffing.
Current bounded work: **DES-001..008** detailed design and independent review. G1 is accepted in reviews/gates.md; G2-S remains required before local-fake implementation. G2-P independently governs real AP2/ACP integration. Continued design is authorized; estimates do not create waiting periods.

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
| REL-002 | Focused reviewed documentation commit and push — IO | Planned | DOC-006; REL-001 for push | Secret/private-file and whitespace/link checks; local commit; non-force branch push when destination verified. |

## Reviewed design — Week 2 / target workdays 6-10

| ID | Task / owner | Status | Dependencies | Acceptance evidence |
| --- | --- | --- | --- | --- |
| DES-001 | Modular architecture and ownership — Sol, independent design review | In progress | G1 for acceptance; drafting authorized | Core domain/application/ports/infrastructure boundaries; trusted UI and graph/payment truth separated. |
| DES-002 | Versioned local-fake extension contracts and state model — Sol, independent review | In progress | DES-001 | IDs, transitions, errors and negotiation; project-only continuation explicit; unsupported real ACP mappings tracked in DES-009. |
| DES-003 | Authority, customer action and threat model — design author, Astra review | In progress | DES-001/002 | Scoped simulated authority, trust boundaries, expiry/replay, 3DS/2FA-style action lifecycle, credential isolation and refusal rules; no real cryptographic claim. |
| DES-004 | Exact money, reservations and recovery design — Sol, Astra review | In progress | DES-002/003 | Atomic policy reservation, scoped idempotency, local event verification and one execution owner through crashes/timeouts; revocation order defined. |
| DES-005 | Capability and adapter contract design — Sol, independent review | In progress | DOC-004; DES-002 | Provider/method/credential/action dimensions, unknown-required refusal and partial-support behavior. |
| DES-006 | Simulator and conformance profile design — Sol, independent review | In progress | DES-002..005 | Deterministic scenario catalog, requirement mapping, clock/event controls and incompatible reference fixtures; local-fake labels. |
| DES-007 | Runtime/framework/dependency ADRs — Sol, independent review / IO | In progress | DES-001..006 for acceptance | Java/Python LangGraph/TypeScript/PostgreSQL proposals reviewed; versions, licenses and dependency rationale recorded for the selected slice. |
| DES-008 | Independent local-fake design review and G2-S decision — Astra / IO | Planned | DES-001..007; G1 | Independent of design author and plan reviewer; no critical local authority/concurrency/recovery findings; implementable first slice and test plan accepted. |
| DES-009 | Real AP2/ACP profile, pins and integration review — design author, Astra review / IO | Planned | DOC-002; DES-001..005; applicable partner/API evidence | G2-P: immutable upstream artifacts, permitted wire/continuation mapping, real roles/trust/signatures/receipts, provider assumptions and negative fixtures reviewed. Does not block pure local-fake work. |
| REL-003 | Reviewed design milestone commit/push — IO | Planned | DES-008 for local-fake scope; REL-001 for push | Focused design diff, G2-S scope and remaining G2-P exclusions, validation evidence and recorded branch/commit. |

## Implementation in vertical slices — Weeks 3-8

| ID | Task / owner | Status | Dependencies | Acceptance evidence |
| --- | --- | --- | --- | --- |
| IMP-001 | Local build/CI and first persisted intent slice — Sol / IO | Planned | G2-S | Reproducible documented build; domain logic independent; exact money validation; PostgreSQL integration and migration evidence; local-fake only. |
| IMP-002 | Simulated embedded checkout and action lifecycle — Sol / IO | Planned | IMP-001; DES-003/006 | Purchase reaches result; required action can suspend/resume; denied/expired/invalid actions tested. |
| IMP-003 | Idempotent execution and event processing — Sol, Astra review | Planned | IMP-001/002 | Duplicate commands/events cause no duplicate charge; event verification and scope conflicts tested. |
| IMP-004 | Concurrent reservations and ambiguous-outcome recovery — Sol, Astra review | Planned | IMP-003 | Contention, timeout and crash/restart tests prove one execution owner; unresolved state is visible and recoverable. |
| IMP-005 | Adapter SDK and capability negotiation slice — Sol / IO | Planned | IMP-004; DES-005 | Reference/synthetic second adapters work through ports; unsupported requirements fail before execution. |
| IMP-006 | LangGraph reference integration — Sol / IO | Planned | IMP-004/005 | Workflow resumes safely from checkpoints and queries payment truth; no autonomous duplicate execution. |
| IMP-007 | Trusted TypeScript customer action UI — Sol, Astra review | Planned | IMP-002/006 | Authorized action handoff, expiry/replay/access checks; no OTP/credential passage through model context. |
| IMP-008 | Sanitized transaction visibility — Sol / IO | Planned | IMP-004/007 | State timeline, correlation IDs and unresolved outcomes visible under tenant access rules; redaction tests. |
| IMP-009 | Incremental slice documentation and GitHub delivery — Sol / IO | Planned | Each reviewed slice; REL-001 for push | Contract changes, commands, failure behavior and evidence included in each focused commit/push. |

All IMP tasks remain planned. The listed stack is a proposal until DES-007/008 accept its ADRs.
No development task authorizes production credentials, paid services, deployment or live money movement.

## Preview, compatibility and evidence — Weeks 9-12

| ID | Task / owner | Status | Dependencies | Acceptance evidence |
| --- | --- | --- | --- | --- |
| CON-001 | Executable project conformance profiles — Sol / IO | Planned | IMP-001..008 | Deterministic tests map to normative requirement IDs and exact profile/suite versions. |
| CON-002 | Negative adapter fixtures and independent evidence review — Astra / IO | Planned | CON-001 | Intentionally incompatible adapters fail for the correct requirements; G3 decision recorded. |
| INT-001 | Select first provider/method/region sandbox scope — PO / IO | Planned | DOC-004; DES-005 | Candidate chosen with authorized account, terms, API access and test authentication availability. |
| INT-002 | First optional external sandbox vertical slice — Sol, Astra review | Planned | INT-001; IMP-005; applicable G2-P mapping/provider design; access | Verified request/event/reconciliation behavior; exact capability matrix and deviations; simulator fallback if access/design blocked. |
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
| INT-003 | Additional providers and card/bank/iDEAL method profiles — Sol / IO | Planned | Access, demand, design | Per-combination contracts and evidence; no provider-wide claims from one method. |
| INT-004 | Amazon Pay / PayPal / PayPay wallet tracks — IO / PO | Planned | Vendor-specific access/terms | Distinct onboarding and adapter contracts, region/action coverage and sandbox evidence. |
| INT-005 | Voucher track — IO / PO | Planned | Issuer access/agreement | Issuer/redemption/expiry/partial-use semantics and accounting test plan. |
| INT-006 | Uber Pay partner track — PO / IO | Blocked | Partner sponsorship, docs, access and permission | Permitted interface established before implementation commitments; retain simulation otherwise. |

## Completion records and working rules

For each completed task record: ID, commit, reviewer, exact checks/commands, outcome, evidence location and remaining limitations.
For blockers record: owner, requested decision/access, impact and independent tasks that can proceed.
No task is marked done solely because prose was drafted, a target date passed or a model reported success.
Remaining release/integration blockers do not prevent local-fake design: public release license, real protocol pins/mapping, and applicable provider/partner access. The supplied and verified GitHub destination is no longer a blocker.
No build/test command exists yet; documentation validation comprises consistency, links and whitespace checks.
See the [roadmap](roadmap.md) for gate criteria and [product brief](product-brief.md) for product scope.
