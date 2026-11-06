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

Publication evidence: reviewed design commit `65c39dd0d8fe80a2a8d72fc5680471d414288448` on `codex/simulator-design`; [documentation CI](https://github.com/manishgh/agentic-payments/actions/runs/42294677444) passed. Four artifact hashes in review 0003 were rechecked after commit and matched. This validates document identity/format, not payment execution.

## G2-P: real protocol/provider integration

Decision: open. Immutable upstream pins, real signature/trust/credential verification, exact ACP transport/continuation mapping and scoped provider evidence remain outstanding. No real protocol compatibility is claimed.

## G2-S extension and implementation acceptance

On 2026-11-06 the integration owner accepted the execution architect's separate-transaction journal, run-lock ordering, sealing and trusted-event clarifications before assigning the execution implementation. This was an extension of the limited gate above, not an interpretation that the original gate already covered dispatch. The written acceptance record was completed during integration.

Accepted clarification: [ADR 0002](../adr/0002-simulator-execution.md), SHA-256 `abb098bbd08301fbb84ffe1b4fed2f788b8c67d3208010c395571ce7b48d5580`. [Review 0005](0005-execution-design-review.md), SHA-256 `ef0e69560b0d4effe3d957def5c47b781d1bf03baab4e02dae801f4984ace4f2`, independently reviewed the earlier Sol-authored execution design. Its author also wrote ADR 0002; the integration owner's separate acceptance addresses that distinction.

Mandatory bootstrap completed before source: verified Java/Maven/PostgreSQL distributions, checksums and dependency/license inventory in [toolchain evidence](../toolchain.md). The bounded extension covers prepare/action/admit, independent journal/query/seal, authenticated internal evidence, recovery and the local client boundary. LangGraph is an authenticated command/status client with separate SQLite checkpoints; the trusted UI alone requests approval/action completion. These client designs were implemented with scoped tests and independently reviewed source.

During integration, the owner added a trusted checkout-read endpoint so displayed approval values come from the same authoritative snapshot as the approved digest/version. Astra reviewed this addition in [review 0006](0006-reference-code-review.md). It changes the project draft only and makes no change to upstream AP2/ACP.

Source acceptance is limited to the reviewed local reference and its tested behavior. The independent [core review](0006-reference-code-review.md) and [client/tooling review](0007-client-tooling-review.md) record exact source identities and findings. Final aggregate evidence is generated by `scripts/conformance.py`; source manifests must match before/after the checks. Missing coverage remains explicit and the report verdict is `NOT_CERTIFIED`.

Seeded multi-intent replay, the complete adversarial/privacy matrix, full managed-platform separation and all real integration/release gates remain open. An accepted local reference is not a completed commercial platform.

## Later gates

The integration owner separately accepted [ADR 0003](../adr/0003-replay-and-adapter-conformance.md) on 2026-11-06 before assigning replay implementation and writing descriptor/TCK code. Accepted authored-design SHA-256: `deb1d3b3971befd692cd5fc334cf421f0d8f72674701b6d368b5125aac403f6d`. This covers durable explicit schedules, ownership guards, raw invariant validation before normalized digests, generic metadata negotiation and optional-feature TCK evidence. It does not loosen the interactive core's local profile. [Review 0008](0008-extension-tck-review.md) independently assesses the generic SDK/TCK source; replay source acceptance requires its own review and aggregate evidence.

The owner also accepted the isolated offline artifact-laboratory contract in [independent review 0009](0009-upstream-bridge-review.md), SHA-256 `fce6ac509378abdbe74134466bf228907d6a1a41bdbee8734a63f5d2257be9c6`, before assigning that implementation. Its exact-byte merchant JWS checks, schema/handler constraints, content binder and synthetic sidecar may not authorize core dispatch. Native AP2 mandate verification and real bridge interoperability remain false. Code/cryptographic acceptance remains pending independent source review and negative/golden-vector tests.

G3-G5 and M1-M5 are not assessed. There is no certification, package release, deployment or live payment authorization.
