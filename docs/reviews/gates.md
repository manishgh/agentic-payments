# Engineering gate records

## G1: documentation baseline

Decision: accepted by integration owner on 2026-11-06 for detailed design work.

Evidence: [product brief](../product-brief.md), [roadmap](../roadmap.md), [backlog](../backlog.md), [requirements](../../specification/0.1.0-draft/README.md), [source register](../references.md), [risk register](../risk-register.md), [initial architect review](0001-protocol-boundaries.md) and [independent plan review](0002-plan-review.md).

Checks: repository documentation validator passed; a missing-file-link negative probe failed as expected. Git staging/whitespace validation and publication are recorded in the status handoff. No application build or payment test has run.

Scope: shared platform requirements; documentation -> reviewed design -> implementation; synthetic preview is separate from real AP2/ACP/provider integration. The initial architect review's unresolved real-protocol findings are assigned to G2-P, not declared fixed.

Remaining dependencies: public license/package release terms, exact upstream wire pins and trust mapping, provider access, live operating scope. These do not prevent detailed local-fake design.

## G2-S: local-fake simulator design

Decision: pending independent detailed design review. No application implementation is approved by this record yet.

## G2-P: real protocol/provider integration

Decision: open. Immutable upstream pins, real signature/trust/credential verification, exact ACP transport/continuation mapping and scoped provider evidence remain outstanding. No real protocol compatibility is claimed.

## Later gates

G3-G5 and M1-M5 are not assessed. There is no certification, package release, deployment or live payment authorization.
