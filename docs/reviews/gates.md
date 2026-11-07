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

Decision: open. AP2/ACP source pins now exist, while the remaining native cryptographic profile pins, real signature/trust/credential verification, exact ACP transport/continuation mapping and scoped provider evidence remain outstanding. No real protocol compatibility is claimed.

## G2-S extension and implementation acceptance

On 2026-11-06 the integration owner accepted the execution architect's separate-transaction journal, run-lock ordering, sealing and trusted-event clarifications before assigning the execution implementation. This was an extension of the limited gate above, not an interpretation that the original gate already covered dispatch. The written acceptance record was completed during integration.

Accepted clarification: [ADR 0002](../adr/0002-simulator-execution.md), SHA-256 `abb098bbd08301fbb84ffe1b4fed2f788b8c67d3208010c395571ce7b48d5580`. [Review 0005](0005-execution-design-review.md), SHA-256 `ef0e69560b0d4effe3d957def5c47b781d1bf03baab4e02dae801f4984ace4f2`, independently reviewed the earlier Sol-authored execution design. Its author also wrote ADR 0002; the integration owner's separate acceptance addresses that distinction.

Mandatory bootstrap completed before source: verified Java/Maven/PostgreSQL distributions, checksums and dependency/license inventory in [toolchain evidence](../toolchain.md). The bounded extension covers prepare/action/admit, independent journal/query/seal, authenticated internal evidence, recovery and the local client boundary. LangGraph is an authenticated command/status client with separate SQLite checkpoints; the trusted UI alone requests approval/action completion. These client designs were implemented with scoped tests and independently reviewed source.

During integration, the owner added a trusted checkout-read endpoint so displayed approval values come from the same authoritative snapshot as the approved digest/version. Astra reviewed this addition in [review 0006](0006-reference-code-review.md). It changes the project draft only and makes no change to upstream AP2/ACP.

Source acceptance is limited to the reviewed local reference and its tested behavior. The independent [core review](0006-reference-code-review.md) and [client/tooling review](0007-client-tooling-review.md) record exact source identities and findings. Final aggregate evidence is generated by `scripts/conformance.py`; source manifests must match before/after the checks. Missing coverage remains explicit and the report verdict is `NOT_CERTIFIED`.

At the reference checkpoint, seeded multi-intent replay, the complete adversarial/privacy matrix, full managed-platform separation and all real integration/release gates remained open. Later bounded acceptance is recorded below. An accepted local reference is not a completed commercial platform.

## Later gates

The integration owner separately accepted [ADR 0003](../adr/0003-replay-and-adapter-conformance.md) on 2026-11-06 before assigning replay implementation and writing descriptor/TCK code. Accepted authored-design SHA-256: `deb1d3b3971befd692cd5fc334cf421f0d8f72674701b6d368b5125aac403f6d`. This covers durable explicit schedules, ownership guards, raw invariant validation before normalized digests, generic metadata negotiation and optional-feature TCK evidence. It does not loosen the interactive core's local profile. [Review 0008](0008-extension-tck-review.md) independently assesses the generic SDK/TCK source; replay source acceptance requires its own review and aggregate evidence.

The owner also accepted the isolated offline artifact-laboratory contract in [independent review 0009](0009-upstream-bridge-review.md), SHA-256 `fce6ac509378abdbe74134466bf228907d6a1a41bdbee8734a63f5d2257be9c6`, before assigning that implementation. Its exact-byte merchant JWS checks, schema/handler constraints, content binder and synthetic sidecar may not authorize core dispatch. Native AP2 mandate verification and real bridge interoperability remain false. At that design gate, code/cryptographic acceptance was pending independent source review and negative/golden-vector tests.

On 2026-11-06 the integration owner accepted the bounded offline source following [Astra review 0011](0011-offline-artifact-code-review.md), review SHA-256 `bb700a2df1d752d4d196bcc1d4dea5ee70bcdd345bc987de4a091ebea3e0e01a`, with 26 independently passing lab tests. Accepted lab source-manifest SHA-256: `56a3871c81d4825e534803069cc10f89938403c2b514931148cdbe79fc2d3c01`. This accepts merchant signature/content checks and synthetic sidecar behavior only; it does not close G2-P. Integrated publication still requires the aggregate evidence run.

[Sol integration review 0012](0012-integration-tooling-review.md) accepts the orchestration/setup changes subject to that aggregate run and the separate replay acceptance. Its missing-schema-manifest finding was fixed and independently verified. Failure-path probes do not substitute for running the actual application suite.

On 2026-11-07 the owner accepted the bounded replay source after [Astra review 0010](0010-replay-code-review.md), SHA-256 `c83ef893c32777a298769b6561a2dd2001c0e1256b4da2dae9166480ca626e9d`. RR-001 through RR-008 were resolved. The owner independently reproduced the reviewed 13-file replay source manifest `5158f52b8bd51c274eaaefda1e98c0c41c7e60ae83430640e3147c99936b65b7` and three-file replay-test manifest `57966b366dd5b8aad06b79ded772af923af5034fdc8138d674774aaba04eec02`. The author passed 47 focused PostgreSQL and seven plan tests; final aggregate evidence is recorded separately. Acceptance includes two finite interleaving recipes, selected concurrent-clock cases and one real JVM journal-commit crash boundary. Authenticated conflicts, disputed exposure and frozen budgets deliberately fail normalization without equality digests; broader CT-011 coverage remains open.

G3-G5 and M1-M5 are not assessed. There is no certification, package release, deployment or live payment authorization.

The final integrated fresh-database run on 2026-11-07 passed all 11 stages with 99 Java tests and all client/lab/upstream checks. Before/after source identity matched `66121c775907dbccce4d1ffd439c990ca00e0eda3c60d307f3891efd93fd3d89`; see [checkpoint evidence](../implementation-evidence.md). This satisfies the aggregate prerequisite for publishing the accepted bounded source. It does not close the remaining G3 matrix or any later release/live gate.

## Requirement-evidence follow-on: test design acceptance

On 2026-11-07 the integration owner accepted a bounded extension of the existing CT-006/CT-010 test plan after Sol's gap assessment and Astra's independent design review. This authorizes tests against existing behavior: exact durable event replay, absent/cross-bound journal handles, forged adapter delivery, authenticated binding variants, same-sequence conflicts, contradictory success after no-effect sealing, higher-sequence repetition of a final outcome, and rejected credential/action canaries on named safe core surfaces. Each test must inspect durable accounting/provenance witnesses. Trusted fixture assertions are explicitly distinct from actual journal verification and real provider authentication.

The owner also accepts a dedicated local HTTP subprocess privacy test: valid synthetic bearer canaries and rejected payload/header/path values must be absent from responses and captured process output after shutdown. This adds no payment endpoint, provider, financial behavior or production secret handling. Any discovered behavior change requires review before implementation. Report-schema/collector implementation remains gated on its separate ADR review; these independent negative tests need not wait for that tooling design. All resulting source still requires independent review and the final aggregate run before publication.

The owner subsequently accepted [ADR 0004](../adr/0004-requirement-evidence.md), SHA-256 `2466075b822ef5d410ae9b5daf96a8d3a945df2d88b14b4f1b6d77e27df23a67`, before assigning evidence implementation. Independent [review 0013](0013-evidence-design-review.md), SHA-256 `2a63397b75039f7c5c6f8a2bd9a4489a2e98f238e2aff4067add83440f7546ac`, resolved ER-001 through ER-008. Accepted scope is selected named observations, strict current-run receipts/producers, reviewed CT mappings and retained gaps, safe report 0.2 and a validator that recomputes claims from independent inputs. No complete certification policy or financial behavior change is accepted by this gate.

The owner accepted the bounded event/privacy test source after [Astra review 0014](0014-adversarial-test-review.md), SHA-256 `2702bcda4522b93f1eefc426ce98722a3e1cd2736d5acf47e4d783306ed8225e`. Eight PostgreSQL methods and the isolated HTTP privacy method passed focused validation. The review distinguishes real local journal verification from privileged conflict fixtures, and rejected-input privacy from full telemetry coverage. Test acceptance does not close CT-006/010 or authorize any new financial behavior; evidence-tooling acceptance and unchanged-source aggregate validation remain separate.

On 2026-11-07 the owner accepted the bounded evidence implementation after [Astra review 0015](0015-requirement-evidence-review.md), SHA-256 `cff4d223593efad105b2fed83334ca4caa7992cbdc6b27ba6c84f55d8842d910`. All five findings were corrected and independently verified. The owner reproduced the nineteen-file review manifest `d8f5de18fa356ba332087c534ce6eb08d4286a33be66f039aad1c0a68f969fd7`. Independent evidence-tooling validation passed 42 tests; the final fresh-database aggregate `7fb95c298b0f4f9aa09e4c096f4e23b3` passed all twelve stages and 204 test methods with unchanged 238-file source manifest `04a8f6c197987367d8ccfa19f85db54f91adf24c032d68f7de7eb1cc562219ca`. Separate semantic bundle verification passed against that same checkout.

The final report qualifies all 48 selected observations, while eleven full CT rows remain INCOMPLETE and CT-008 is NOT_APPLICABLE only after its baseline refusal guard passes. This accepts the local evidence pipeline for milestone publication, not G3 certification or any provider/managed-service release. See [implementation evidence](../implementation-evidence.md) for exact counts and verification scope.

## Independent AP2 protocol implementation

On 2026-11-07 the owner accepted [ADR 0005](../adr/0005-independent-ap2-engine.md), SHA-256 `1fe1530cad49d89b6a4293efb0e81b4bd1050dc82e375b8589d3e00643a59513`, and the [AP2 requirements](../ap2-requirements.md), SHA-256 `90183f90f4440776fc50e2baed7239dd39ff89040fe2e67a5a6c2f3f91016b23`, after [independent Astra review 0016](0016-independent-ap2-design-review.md), SHA-256 `3e5c89887ba1cef8d4ebdc60192855f4620fc1587de4018474a5323a8ffc58ed`. This acceptance precedes implementation assignments.

The user explicitly requires an independently authored AP2 protocol engine, without importing, wrapping or copying Google's SDK implementation. The accepted first profile is the synthetic direct Trusted Agent Provider path: native closed SD-JWT VC mandates, role-bound verification, exact checkout/receipt hashes, independently signed receipts, registered in-memory replay controls and cross-language test vectors. Java JCA supplies established cryptographic primitives; no new dependency or handwritten elliptic-curve primitive is authorized. The AP2 engine remains isolated from financial admission; delegated/user-credential paths, live trust/consent and external interoperability remain unsupported. Source acceptance and aggregate evidence are separate prerequisites before publication.

The owner accepted the corrected bounded source after [Astra review 0017](0017-independent-ap2-source-review.md), SHA-256 `f2aaae0506331c56fbebc51f9651da73287967f8f89e17b0e97e1d31ae5dc31a`, and independently reproduced its 27-file manifest `f62d789efce1d0636165d646b7c4a83c88bfd1bb6b45da606cadd31200647fb5`. All six findings were resolved, including the aggregate-discovered three-artifact schema bound. The owner passed 37 focused Java methods and independent Python verification of Java output. The elevated 28-test affected Python run passed, including the real Windows junction regression that the reviewer's restricted process could not execute. No regression was skipped or weakened. The new complete-bundle regression failed before the schema correction and passed afterward; Astra independently verified it and the older two-artifact fixture. Publication still requires the unchanged-source fresh-database aggregate recorded in implementation evidence.

That publication prerequisite was satisfied by fresh-database run `90b8afaf1c3140f792a9b628d16c35de`: twelve stages and 249 methods passed with stable 257-file source SHA-256 `406fb4e96eb6fad8dec4f4488af5480ad8e082ba3dee434d51c242c6cfd47ea5`. Independent complete-bundle validation also passed. The earlier run that failed report generation is retained as failed, not substituted for this result. The owner accepts milestone branch publication of the reviewed synthetic direct profile; no durable native financial bridge, external interoperability, certification or package/live release is accepted.
