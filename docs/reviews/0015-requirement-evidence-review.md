# Review 0015: Requirement evidence implementation

Status: independent source/mapping review completed, 2026-11-07. Recommend integration-owner acceptance of the bounded implementation at the identities below, contingent on the owner's final unchanged-source aggregate and semantic bundle validation. No unresolved source-review blocker remains. This is not a completed CT matrix or certification grant.

Reviewer: execution/security architect, independent of the implementation authors. The integration owner authored the evaluator, schemas, inventory, mapping and runner integration; Sol authors supplied collectors and independent adversarial tests. The reviewer authored this record, inspected source and supplied findings, without editing implementation or running Maven.

## Scope and decision boundary

This review compares the implementation with [ADR 0004](../adr/0004-requirement-evidence.md) and [design review 0013](0013-evidence-design-review.md). It covers the evaluator and bundle verifier, collection wrappers, current-run orchestration, closed evidence schemas, selected observation inventory, CT mapping, POM report-directory override, tests and CI publication. The additional payment/event/privacy tests have their separate [review 0014](0014-adversarial-test-review.md).

The selected inventory contains 48 logical observations. Exact runtime selectors receive individual credit; other tests contribute only counts. The mapping retains eleven incomplete CT rows when every selected observation passes. CT-008 may be NOT_APPLICABLE only when the local voucher/split profile remains absent and its baseline refusal observation passes. No CT becomes complete merely because the aggregate exits successfully. NOT_CERTIFIED remains fixed.

## Findings and disposition

| ID | Finding | Verified disposition |
| --- | --- | --- |
| EI-001 | The XML reader checked a testcase's immediate parent but recursively discovered testcases anywhere. A suite nested inside system-out could therefore manufacture a selected PASS. The reviewer reproduced complete=true with no issues from that malformed native result shape. | The shared reader now rejects structural suite/testcase nodes beneath non-structural ancestors. Java and Node nesting regressions pass, alongside valid suite containers, Node duplicate-title and selected-skip cases. |
| EI-002 | A structurally valid bound named failure combined with an erroneous zero stage exit became qualified INCOMPLETE, weakening the specified FAIL precedence. | Evidence remains INVALID while the trustworthy named FAIL/ERROR/UNEXPECTED_SUCCESS qualifies as FAIL. Malformed or binding-invalid producers retain no trusted result. The masked-zero-exit regression passes. |
| EI-003 | CI uploaded the entire bundle under always(), even if the preceding semantic verification failed. | Upload now requires the semantic verification step's success. Verify-only accepts honest failed/incomplete reports for preservation without changing their preview verdict or aggregate exit. Actual CLI subprocess tests reject forged reports under the same flag. |
| EI-004 | Internal receipt source equality establishes a historical source identity, but alone permits an older complete bundle to print PASS after the worktree changes. | Default bundle verification compares the complete current canonical source manifest with receipt.sourceAfter. Tests change, add and remove an unpinned runtime file while proving the input locks still validate; each receives CURRENT_SOURCE_MISMATCH. Historical verification requires the preserved source revision. |
| EI-005 | Semantic verification ignored additional files while CI uploaded the whole directory. An unvalidated diagnostic file could accompany an otherwise valid bundle. | Verification now checks the exact metadata/declared-artifact file set and refuses unexpected directories, symlinks or resolved escapes. An extra canary log is rejected with UNDECLARED_BUNDLE_CONTENT without echo; removal restores valid verification. |

The separate test author also found and reproduced noncanonical source-manifest paths before this review: ../outside.py and scripts/../outside.py. The evaluator now rejects those paths rather than accepting their internally consistent hashes. This finding is attributed to that author, not presented as an independent discovery by this reviewer.

## Reviewed invariants

Fresh run IDs, stage execution IDs and before-source hashes bind producer contexts to owner receipts. Java XML is collected only from the current Maven invocation's explicit report directory outside target; Maven clean cannot erase the receipt or select older reports. Python records TestResult outcomes, including lifecycle/subtest failures, while an isolated worker discards native stdout/stderr. Node builds first and uses a fresh native JUnit destination. Missing selected tests, partial producers, wrong identities, count contradictions, duplicate selectors and failed stages cannot qualify a selected PASS.

The evaluator reloads the locked definitions and schemas, validates closed inputs, checks exact selected identities and recomputes qualification and CT coverage. Report status edits, removed gaps, forged N/A, arbitrary payload fields and stale producer references cannot authorize themselves. Optional-profile absence is the reviewed, locked local capability declaration, not a claim about real providers. The inventory's assertion meaning remains a human review responsibility; an unchanged method name cannot prove unchanged semantics.

The runner moves the prior current bundle aside before preflight. Missing tools/database configuration yields a fresh incomplete record when definitions are valid; invalid definitions yield a fixed failure without a consumable current report. Source changes during execution invalidate qualification. Publication is atomic after semantic validation. Raw XML, failure text, parameters, environments and process logs stay outside the safe bundle; public fields are bounded identities, counts, hashes, statuses and fixed reason codes.

## Evidence and limitations

The first integration-owner exploratory aggregate had all twelve stage exits pass and all 48 observations observed PASS. Its source changed during review, so it correctly reported preview FAIL and evidence INVALID. That run is a negative source-mutation witness, not acceptance evidence.

The reviewer independently ran the final collector suite: 26 tests passed in 1.257 seconds. The reviewer then ran the evaluator and runner suites together: 16 tests passed in 19.312 seconds, with zero failures/errors/skips. This includes a real local directory-junction escape test and six actual validator CLI subprocess checks; runner child-tool failures use isolated controlled fixtures rather than Java/database execution. No Maven build or aggregate payment test was run by this reviewer. The final integration-owner aggregate was still pending when this source recommendation was recorded and must be attached separately before milestone acceptance.

No result in this review grants project certification, native AP2 authority, ACP interoperability, provider/webhook verification or complete telemetry secrecy. The CT-011 conflict-normalization gap remains open. Complete replacement of unsigned owner receipts and artifacts remains outside the local provenance claim; the verifier does not authenticate an independent executor. Source equality covers the versioned source scope defined by source_identity, excluding generated output and installed dependency trees; the exact dependency locks remain pinned separately.

## Reviewed identities

The reviewer independently recomputed the frozen canonical runtime source manifest: 238 files, SHA-256 `04a8f6c197987367d8ccfa19f85db54f91adf24c032d68f7de7eb1cc562219ca`. Its encoding is the implementation's compact JSON of globally sorted POSIX path/digest pairs.

The nineteen reviewed files below additionally have review-manifest SHA-256 `d8f5de18fa356ba332087c534ce6eb08d4286a33be66f039aad1c0a68f969fd7`: sort complete repository-relative POSIX path strings, concatenate each path, TAB, lowercase file SHA-256 and LF, encode UTF-8 without BOM, then hash. This review document itself is excluded to avoid a self-reference.

| Artifact | SHA-256 |
| --- | --- |
| `.github/workflows/reference.yml` | `72c5943f635904a3ca42b6e1c7605f33875e3f6663681e8ccf02d2414e5cb588` |
| `docs/requirement-evidence.md` | `8206cfd5de949fbab77de4964ac120208d42ba7bce5fd021a8cbf4aa715e36d2` |
| `pom.xml` | `c6383e143efc8cec03705cebbbadcf9c4c611591830acde5f09a25a23f86b5e8` |
| `scripts/conformance.py` | `18caecef6e25fe357af55429740009780c1adde47b42b6d63e5050a2b6269352` |
| `scripts/evidence_collectors.py` | `351e3c6e4706e287d2835e82be41f61acdef7c0c7b9ef0a9f0697578b803a840` |
| `scripts/requirement_evidence.py` | `18d61cb5cf573f1bb17063f240cdff9c95a43d3731a02cdd54dd447e9904586a` |
| `scripts/run_node_evidence.py` | `7e9a9028b4708924a8011a97b98689f43120b4fb5b5362470306a1a14099e937` |
| `scripts/run_python_evidence.py` | `bd0494932066911de9f444bf33c06b033be9a20369792d0ad2394ca674a0f699` |
| `scripts/test_conformance.py` | `d37b3627080d4fa8cd950751148f155a231f3b83435ebeeccf293205d523a570` |
| `scripts/test_evidence_collectors.py` | `5dfc43da7d9aed9be0fd6b1d1928ed272132922218b170d1005c8288620a314d` |
| `scripts/test_requirement_evidence.py` | `e1bb89eafed36cd7fe4efb6fa7c70e349a92b6ed3587011fff3d5fc9ad54a55b` |
| `specification/0.1.0-draft/evidence/input-lock.json` | `f788f0cdd2b02dbf8af138a64931d0fdb47055a6d412185639d9dd2d904399ea` |
| `specification/0.1.0-draft/evidence/inventory.json` | `c7f584fba060a8b927f88e3b198bcf853ed573f22aea21bb1f0bb86dca995122` |
| `specification/0.1.0-draft/evidence/inventory.schema.json` | `7b89d729761c34f33350ecf16cd8b24ce81200175e4db79321b78bce4371f048` |
| `specification/0.1.0-draft/evidence/mapping.json` | `002735d63028b7b1fdf8cbefb759d7aefdf0f88a9969a914eeab497d3d6b2446` |
| `specification/0.1.0-draft/evidence/mapping.schema.json` | `9e8110e343f6a0a134f9b603e4e8eb41c33a566fc8d14f343d48bde1cea872f2` |
| `specification/0.1.0-draft/evidence/producer.schema.json` | `2e827c5ebd3d98defa5a25bb4597b365cff32c7b84bec12d40fccd724f4f0362` |
| `specification/0.1.0-draft/evidence/receipt.schema.json` | `edb84f46e49d2321e0dd51b325e33509d3073e705bc6d290d528443f25cbce03` |
| `specification/0.1.0-draft/evidence/report.schema.json` | `f658e2f1a572d202afcfce95a55e818340b4e950a97e80b9d34e97f6b7343041` |
