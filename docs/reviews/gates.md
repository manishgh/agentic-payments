# Engineering gate records

## G1: documentation baseline

Decision: accepted by integration owner on 2026-11-06 for detailed design work.

Evidence: [product brief](../product-brief.md), [roadmap](../roadmap.md), [backlog](../backlog.md), [requirements](../../specification/0.1.0-draft/README.md), [source register](../references.md), [risk register](../risk-register.md), [initial architect review](0001-protocol-boundaries.md) and [independent plan review](0002-plan-review.md).

Checks: repository documentation validator passed; a missing-file-link negative probe failed as expected; independent code review exercised eight checker fixtures. Staged `git diff --cached --check` passed after adding LF normalization. Published baseline is `15f0776a2fa1628ac8e77999885cd86811380204`; hosted [documentation CI](https://github.com/manishgh/agentic-payments/actions/runs/42293811152) passed. No application build or payment test has run.

Scope: shared platform requirements; documentation -> reviewed design -> implementation; synthetic preview is separate from real AP2/ACP/provider integration. The initial architect review's unresolved real-protocol findings are assigned to G2-P, not declared fixed.

Remaining dependencies: public license/package release terms, exact upstream wire pins and trust mapping, provider access, live operating scope. These do not prevent detailed local-fake design.

## G2-S: local-fake simulator design

Decision: LIMITED design acceptance by integration owner on 2026-11-06, following [independent Astra review](0003-design-review.md). Accepted slice: create -> quote -> approve -> reserve -> read for `local-fake-single-tender-0.1` only.

Evidence: exact reviewed artifact hashes and closure of DR-001/002/003/005/006 in review 0003; DR-004 is addressed by explicit mandatory pre-source bootstrap. The reviewer independently checked the 402-byte canonical fixture and SHA-256 digest. API/data/ADR/task documents are design artifacts, not executed code.

Next: complete verified distribution/dependency/checksum and license inventory bootstrap before any application source, then implement [slice 001](../implementation-slice-001.md) with independent code review and database/concurrency tests. Installation and build success have not been established.

This is not blanket G2-S approval: dispatch, customer actions, simulator journal, reconciliation, LangGraph and UI implementation require their later scoped reviews. Split tender, refunds and real protocols remain unsupported in the first slice. G2-P remains open.

## G2-P: real protocol/provider integration

Decision: open. Immutable upstream pins, real signature/trust/credential verification, exact ACP transport/continuation mapping and scoped provider evidence remain outstanding. No real protocol compatibility is claimed.

## Later gates

G3-G5 and M1-M5 are not assessed. There is no certification, package release, deployment or live payment authorization.
