# Review 0002: Delivery plan and gate boundaries

Reviewed: 2026-11-06. Role: independent senior plan reviewer; not the author of the product brief, roadmap, or backlog. This reviewer previously authored extension/SDK/conformance briefs, which are context rather than independently approved design artifacts in this review.

## Scope and verdict

Reviewed the product brief, roadmap, backlog, status handoff, decision register, and [initial Astra concept review](0001-protocol-boundaries.md). The integration owner supplied the verified empty GitHub destination and the user's instruction to continue design without waiting for calendar estimates. This is a plan review, separate from design and later code review.

**Verdict: delivery plan acceptable for continued local-fake design after the corrections below.** The integration owner must still record the G1 documentation-baseline decision. This review does not pass G2-S, G2-P, implementation, conformance, publication, or live-payment gates. Design authors and independent design reviewers must provide the evidence for those gates.

## Findings and corrections

| ID | Severity | Finding | Correction and disposition |
| --- | --- | --- | --- |
| PLAN-001 | High | G1/G2 combined requirements discovery, local simulator design, and complete real AP2/ACP mapping. This made missing external pins/provider access appear to prohibit a pure fake implementation, while allowing no precise scoped decision. | Roadmap now separates G1 documentation baseline, G2-S implementable local-fake design, and G2-P real protocol/integration design. Backlog adds DES-009 for real mappings/pins. Corrected in plan; G2-S and G2-P remain unpassed. |
| PLAN-002 | Medium | Product brief/roadmap/backlog still called the exact remote unknown or blocked after the user supplied it and the integration owner verified empty history. | Recorded `https://github.com/manishgh/agentic-payments.git`; removed user-destination blocker. Delivery still records appropriate branch/history/visibility checks and does not authorize force-push, merge, release licensing, or deployment. REL-001 completion evidence remains the integration owner's responsibility. |
| PLAN-003 | Medium | Day/week tables and stale next-task text could cause waiting for a target day or asking permission despite authorized design work. | Estimates explicitly express effort/order, with immediate advancement on accepted evidence. Next work is baseline review plus local-fake design, not waiting for a future day or full real integration. No unattended automation is implied. Corrected. |
| PLAN-004 | Medium | A generic G2 dependency did not identify which design safety evidence the first local implementation needs. | G2-S explicitly requires ownership, authority simulation, exact money, reservations, idempotency, ambiguity, revocation, actions, recovery, privacy, runtime ADRs and test plan without unresolved critical local findings. IMP-001 depends on G2-S. Corrected; technical design proof is outside this review. |
| PLAN-005 | Medium | “Independent review” did not consistently distinguish plan, design and code roles from authors. | Roadmap and DES-008 now require separate assignments; this plan verdict is not design acceptance. Corrected; reviewer identity/scope must remain in each actual review record. |

## Security and scope preserved

The gate split does not downgrade payment safety. Pure simulation must visibly use local-fake mandates, event authentication, credentials, providers, checkout and customer-action semantics. It must not advertise real AP2 signatures, accepted ACP extension fields/statuses, provider support, or production readiness. No provider onboarding is needed to model and test a fake boundary.

The initial Astra findings remain binding design questions for their applicable scope. Single execution ownership, revocation ordering, atomic reservations, idempotency, unknown outcomes and secret isolation must be resolved for G2-S, even without a network provider. Real signer trust, immutable external artifacts, AP2/ACP field/continuation mapping, and actual provider semantics remain G2-P blockers. Unknown required capabilities and unsupported external mappings fail closed.

The approximately 12-week horizon remains a developer-preview estimate. Optional sandbox work may move without delaying a local-fake preview or expanding its claims. Managed/live service remains later, with explicit provider, legal/compliance, operational and live-authorization gates. Licensing is unresolved for public artifact distribution; it does not require stopping local design.

## Remaining risks and required follow-through

- Integration owner must update status/completion records to match the final reviewed milestone and actual commit/check evidence; this reviewer did not mark implementation tasks done.
- Independent design review must reject any local-fake design that cannot specify crash windows, authoritative reconciliation, revocation ordering, and testable invariants. Describing a gate is not satisfying it.
- G2-P requires evidence that a proposed ACP continuation/transport is permitted; a project extension or out-of-band channel must not be represented as accepted upstream behavior without agreement.
- Effort estimates remain uncertain; partner access and release decisions can change external scope. Preserve stable task IDs and record narrower evidence rather than fabricate coverage.

Validation: read the named planning/review documents, compared gate/dependency wording, and checked the corrected files for relative links and whitespace. There is no application build or payment test result associated with this plan review.
