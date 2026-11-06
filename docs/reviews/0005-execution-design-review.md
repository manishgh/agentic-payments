# Review 0005: Independent review of the proposed simulator execution design

Status: completed document review, 2026-11-06. Recommendation: accept bounded synthetic execution design with the implementation conditions below.
Reviewer role: independent senior protocol/concurrency architect for the existing Sol-authored design. Integration-owner acceptance is recorded separately in [gate records](gates.md).
This reviewer also authored [ADR 0002](../adr/0002-simulator-execution.md). Its new implementation clarifications are not independently self-approved by this review; the integration owner must review and accept them.

## Reviewed scope and identity

The independently reviewed artifacts are the pre-existing [API contract](../api-contracts.md), [data model](../data-model.md), [ADR 0001](../adr/0001-simulator-preview.md) and prior [review 0003](0003-design-review.md).
Their bytes were unchanged from the earlier design milestone when read for this review. Exact SHA-256 values follow.

| Artifact | SHA-256 |
| --- | --- |
| `docs/api-contracts.md` | `1a125e83dac31bfeef8408a516cfca9dab1d68cce2d6149feab0b3a9d92b65bb` |
| `docs/data-model.md` | `05aab175de753df19fae166e3a3b0a4809afe5d9ac73080fb990d5e800582639` |
| `docs/adr/0001-simulator-preview.md` | `b5bd4bad4836394495a17cc76b48f326f3b69ea8cd57690f82f54631fd1933de` |
| `docs/reviews/0003-design-review.md` | `97a1f641e132e2c1bfb083f1d4335125439909c4725945b8c55c5d22157d894c` |

Target: `0.1.0-draft` / `local-fake-single-tender-0.1`. The scope extends the earlier accepted reservation slice to execute/preparation, synthetic trusted challenge, dispatch admission, journal/query/seal, verified event application and recovery.
This review does not implement any component, execute conformance tests or approve real AP2/ACP/provider interoperability.

## Findings and disposition

| ID | Finding | Review disposition / implementation condition |
| --- | --- | --- |
| ER-001 | The design explicitly persists operation identity before effect and distinguishes preparation from dispatch. | Accept. Preparation must remain effect-free; a sole operation/key/digest survives every work retry and restart. Unique database constraints and negative replay tests are required. |
| ER-002 | Admission versus revoke/clock ordering is fully described by the run-first lock order and durable ADMITTED commit. | Accept. Preserve the unavoidable commit-to-send gap; post-admission revocation cannot promise cancellation or release. Test both transaction orders and delayed send. |
| ER-003 | Journal independence is required but its deployment topology is unspecified. | Accept the existing separate-commit requirement. ADR 0002 proposes the same database with separate connection/transaction and no core-row locks in journal code; integration owner must accept that implementation choice. Test successful journal commit followed by core failure. |
| ER-004 | NOT_FOUND plus stale sender is correctly identified as unsafe; atomic seal semantics prevent delayed execution after release. | Accept conditional on an actual serializable journal identity transition using unique constraint/row locking. Separate query followed by non-atomic seal is not conformant. Test acceptance-first and seal-first races. |
| ER-005 | Trusted actions bind subject/run/attempt/operation/digest and are consumed once. | Accept. Action completion must not be exposed to graph roles. Recheck grant and action validity at admission; verify expiry equality, stale prepare results, denial and revoked authority. |
| ER-006 | Authenticated in-process facts are required, but the concrete verification mechanism is deferred. | Accept the requirement. ADR 0002 proposes journal-event lookup with private adapter context, not a public boolean assertion; integration owner must accept the choice. HTTP must expose no event injection route. |
| ER-007 | Late/stale/contradictory facts retain evidence and freeze affected budgets rather than fabricate success/failure. | Accept. Duplicate IDs with different content and contradictory lower-sequence terminal facts need explicit tests; sequence filtering must not hide conflicts. Unauthenticated spoofed facts cannot freeze a budget. |
| ER-008 | Leases fence workers but cannot unsend an admitted call. | Accept. Fence work-result/ack writes while allowing a verified late fact to reconcile through the normal event path. Never regenerate a provider operation identity on lease loss. |
| ER-009 | Optional split/partial/compensation behavior lacks an accepted execution design. | Excluded, not waived. Reject split/underfunded inputs before reserve; label CT-008 optional behavior not applicable. An additional design is required before implementation or a claim. |
| ER-010 | State invariants are strong on paper; no executable database or recovery evidence exists. | Implementation/code acceptance stays open. Run the full bounded CT matrix and restart/fault traces, record missing tests truthfully, and perform independent source review. |

No critical conceptual design blocker remains for this bounded simulation profile. ER-003/006 introduce concrete implementation choices in the authored ADR, so independent review of the original design alone cannot be represented as acceptance of those new choices.
The integration owner can accept those bounded clarifications after checking their transaction and trust properties; this does not require re-opening the resolved original design findings.

## Minimum acceptance conditions

1. Preserve the exact existing HTTP identity, schema, authorization and idempotency rules; no arbitrary provider configuration or network endpoint enters the simulator.
2. Finish mandatory distribution/dependency/checksum bootstrap before application source, as required by the earlier gate.
3. Accept ADR 0002 explicitly with its artifact hash/revision, distinguishing authored design from this independent review of the earlier artifacts.
4. Implement in reviewable slices: prepare/action/admit, independent journal, event/reconciliation, then client/conformance integration.
5. Exercise PostgreSQL constraints and both sides of revoke/clock/seal races using explicit barriers; in-memory-only tests are insufficient.
6. Demonstrate durable recovery after acceptance/core-state failure and lease loss using the same operation identity; unresolved outcomes retain full reservation.
7. Verify trusted-action/event access, same-tenant cross-run isolation, immutable binding negatives and sensitive-canary boundaries.
8. Obtain independent source review and exact test evidence before marking implementation complete or granting any project conformance result.

Required evidence maps CT-001..007 and CT-009..012 to concrete assertions; CT-005 and CT-007 are strictly local-fake subsets.
CT-008 remains unsupported optional behavior plus mandatory baseline refusal tests. An advertised capability with missing tests is incomplete, not passed.
G2-P, real sandbox connectors, managed/live operation, release licensing and external certification remain separate decisions.

## Review limitations and checks

The review inspected local design artifacts, requirement/test definitions, existing review decisions and exact file hashes.
No external protocol claim or changing provider fact is asserted; this review does not substitute unpinned source references for interoperability evidence.
The reviewer authored only ADR 0002 and this review record, with no application source changes or commits.
Repository documentation validation and `git diff --check` must pass after integration; application test results must be supplied by the implementation owner.
