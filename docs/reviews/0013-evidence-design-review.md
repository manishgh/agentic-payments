# Review 0013: Requirement-bound conformance evidence design

Status: independent design review completed, 2026-11-07. Recommend integration-owner acceptance of the bounded design at the hash below. This is permission to implement the design, not acceptance of implementation, a completed CT matrix or certification.
Reviewer: execution/security architect, independent of the Sol author of [ADR 0004](../adr/0004-requirement-evidence.md). The reviewer authored only this review record and supplied review findings; the ADR author applied its changes.
Baseline reviewed: source checkpoint `9fd7dbbc5cef135007b7f2253aba24c825386954`, requirement register, CT brief, current conformance runner/CI, existing named Java/Python/UI tests and the proposed additional event/privacy test scope.

## Decision and implementation boundary

The final design is implementable using the existing stack and dependencies. It corrects the current gap between stage-level green results and requirement-level evidence without changing financial execution or claiming new provider behavior. No blocking design finding remains at the reviewed identity.

Accept a selected, reviewed observation inventory rather than requiring the first slice to enumerate every test. Named Java, Python and UI results may qualify specific observations. Other results remain safe aggregate counts; aggregate-only contract/CLI results cannot silently become individual CT proof. The report version is reference-evidence-0.2; inventory and mapping have their own explicit versions.

Payment state remains in the existing Java/PostgreSQL core. Additional tests use either actual local journal verification or explicitly trusted injected adapter fixtures, labeled separately. A proved runtime bug requires a focused reviewed fix; this design does not authorize broad core, webhook, provider, cryptographic or replay-normalization changes. NOT_CERTIFIED remains mandatory, and require-certification continues to fail.

## Findings resolved in the final ADR

| ID | Finding | Accepted disposition |
| --- | --- | --- |
| ER-001 | A green suite or printed total cannot establish that a particular requirement assertion ran. | Exact reviewed selectors and cardinalities map to stable observation IDs. Missing/renamed/skipped selected tests remain incomplete; unmapped replacements cannot satisfy them. Human review must still establish that each selected test's assertions support its claimed scope. |
| ER-002 | Stale/partial reports and artifacts left by a failed stage can look successful. | Fresh run/stage-owned locations and execution receipts bind artifacts to the current source and child execution. Failed stages cannot qualify a named PASS. Missing completion, stale binding, malformed input and ambiguous selected matches prevent acceptance. |
| ER-003 | A report that validates only its own status fields can certify its own assertions. | Evaluation takes the current-run receipt, producer artifacts, inventory, mapping and schemas; verification recomputes from those inputs. Shape-only offline validation does not authenticate execution. A complete forged unsigned input set remains outside the stated local provenance claim. |
| ER-004 | Optional N/A or an incomplete observation inventory can hide mandatory omissions. | Explicit versioned obligations/gaps control CT coverage. FAIL precedes INCOMPLETE, then PASS. CT-008 N/A requires absent optional capability and passing baseline refusal guards; failed guards yield FAIL and missing/skipped guards yield INCOMPLETE. |
| ER-005 | Framework edge cases can produce false green named observations. | Java errors/skips/cardinality, Python subtest failures/expected failures/unexpected success/setup/import errors, and the native Node result shape are handled explicitly. A failed UI build cannot qualify existing compiled output. Unmapped failures still fail their stage. |
| ER-006 | Raw failure text, XML system output, parameters and logs may carry privacy canaries. | Shareable evidence uses reviewed IDs, bounded counts, hashes and fixed reason codes. Raw diagnostics stay ignored and separate. The existing CI raw-log tail must be replaced by validated safe summaries; moving logs outside an upload alone is insufficient. |
| ER-007 | Runtime source identity alone does not bind CT definitions or reviewed mapping semantics. | Pin the requirement register, CT-definition document, mapping, inventory, schema and review identities alongside before/after source, runtime/dependency and actual input-artifact identities. Changing a mapped assertion's meaning requires review, even if its method name stays unchanged. |
| ER-008 | Early tool/configuration failure can leave an old default report discoverable as the current result. | Invalidate the current pointer before work, retain historical runs separately, emit current INCOMPLETE records where safe, and atomically publish only validated current-run evidence. Invalid preflight yields a fixed failure and no consumable current report. |

The bounded selected-inventory decision is deliberate: additional unmapped tests do not require exhaustive enrollment. Their raw names must not enter the safe report. A failed unmapped test still fails the stage, while an unmapped skip receives no requirement credit and cannot repair a selected missing observation.

## Required acceptance evidence

Implementation/source review must verify all of the following before closing this slice:

1. Closed schemas plus semantic validation reject duplicate/missing CT rows, invalid references, empty mandatory selections, unsupported versions, false optional exclusions, removed mandatory gaps and forged verdicts. PASS observations with INCOMPLETE CT rows must be representable and preserved.
2. Current-run collection rejects stale artifact substitution, incomplete producers, source/mapping mutation, malformed XML/DTD, selected duplicate/ambiguous matches, inconsistent counts and missing selected invocations. A named PASS from a failed Maven run remains diagnostic only.
3. Unit fixtures exercise all framework result paths, including Python failed subtests and load/setup/teardown errors, skipped/expected-failure selected cases, unexpected successes and Node build/result failures. Exact native selectors must be observed from actual framework output before being credited.
4. Evaluator tests mutate report statuses while keeping producer inputs fixed and prove recomputation rejects the change. Report-only checking must not present itself as execution verification. Missing inputs cannot qualify claimed PASS.
5. Synthetic canaries in malformed producer names, parameters, failure text, output and paths never reach shareable evidence or CI summaries. Failure messages from privacy tests themselves must not print the canary or captured payload. Raw diagnostics remain explicitly unreviewed local material.
6. The new PostgreSQL event tests demonstrate exact replay, absent/cross-bound journal-handle refusal, rejected unverifiable adapter delivery, retained authenticated conflicts, same-sequence conflict and settled-proof/accounting stability. Their trust boundaries remain distinct; no real webhook signature claim follows.
7. Core rejected-input canaries, the isolated HTTP subprocess capture, and existing graph/checkpoint tests retain their exact surface coverage. Rejected-input tests alone do not establish secrecy of every successfully admitted credential/provider path. The HTTP test must own its server process and avoid test-order dependence.
8. A fresh unchanged-source aggregate validates its final report from the associated receipt/artifacts, with actual named observations, all twelve CT rows, retained gaps and NOT_CERTIFIED. Certification mode stays nonzero. Independent source/mapping review precedes any claim that an obligation has been completed.

These are acceptance conditions specified by the reviewed design, not assertions that implementation tests already exist or pass. The design's listed existing selector anchors were checked against source names; proposed AdversarialEvidenceIntegrationTest selectors remain proposed until implemented and observed.

## Scope that stays open

CT-005 applies only to synthetic challenge continuation. CT-007 local binding and the separate merchant-JWS lab do not establish native AP2 authority or real protocol interoperability. Generic descriptors are metadata, not activated provider connectors.

CT-006 observations distinguish actual durable journal verification from trusted adapter fact-application fixtures. CT-010 evidence names each inspected producer/sink; report redaction cannot prove all product telemetry privacy. CT-011's authenticated-conflict/disputed-exposure/frozen-budget equality projection remains an explicit gap because the current replay normalizer fails closed without a digest for those states.

Test-result collection is evidence of what this reviewed local program ran. Hashes do not supply signatures, trusted third-party execution, an approved certification policy, vendor accreditation or deployment readiness. Exact coverage classifications belong to the later reviewed mapping and actual run, not this design recommendation.

## Reviewed identities and checks

| Artifact | SHA-256 |
| --- | --- |
| `docs/adr/0004-requirement-evidence.md` | `2466075b822ef5d410ae9b5daf96a8d3a945df2d88b14b4f1b6d77e27df23a67` |
| `specification/0.1.0-draft/README.md` | `154051b23dafeb87955ff1f803bb908b25dd106cf4770cea76ef8734c4d9a135` |
| `docs/simulator-and-conformance.md` | `0f184e0c23531b6d2d841096efff396bb1fd71f9f673e69397e48bdc1a2e5e43` |

The reviewer read source and design, checked selector names and independently computed these hashes. No Maven build, application test, payment command or implementation edit was performed for this design review. Documentation and whitespace validation are recorded with the review handoff. Integration-owner acceptance belongs in the gate record, separately from this independent recommendation.
