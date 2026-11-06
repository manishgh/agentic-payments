# Review 0010: Recorded replay and durable boundary evidence

Status: independent source review completed, 2026-11-07. Recommend accepting the bounded local replay implementation at the identities below; integration-owner acceptance and the final aggregate run are recorded separately.
Reviewer: independent execution architect, distinct from the implementation author. The reviewer authored ADR 0003; this report independently reviews another author's source against the integration owner's accepted design, not the reviewer's own design decisions.

## Scope

Reviewed all classes in `src/main/java/io/agenticpayments/replay`, the PaymentStore/PaymentService/WorkerReceipt additions, JdbcPaymentStore and DatabaseMigrator changes, V004-V006, replay tests and the DurableBoundaryIntegrationTest helpers.
The baseline is the preceding accepted reference/SDK milestone. Existing simulator safety and [review 0006](0006-reference-code-review.md) remain relevant; this report focuses on changes and new evidence.
After the fixes below, no blocking source defect remains for this bounded scope. The fail-closed conflict-normalization exclusion and evidence limits remain explicit.

## Findings and disposition

| ID | Finding | Required disposition |
| --- | --- | --- |
| RR-001 | Loading replay state before taking the owner lease permits a stale RUNNING decision after another owner completes/fails the execution. | Root identified; source now reloads after acquisition in continue, advance and inspect. Barrier-based regression covers all three paths. |
| RR-002 | An incomplete recipe whose worker is not known to have stopped must never later obtain apparently final digests through inspect. | Root identified; source preserves the stored diagnostic-only INCOMPLETE report, including reason, without recollecting a final snapshot. |
| RR-003 | Checkout expiry was absent from normalization and raw validation, allowing materially different future validity to compare equal. | Fixed: every revision is projected and its expiry must match ordered QUOTED audit time plus 900 seconds. Mutation regression added. |
| RR-004 | Checking surviving audit rows only against an upper bound does not detect deletion. Clock advances previously consumed a sequence without an audit row. | Fixed: CLOCK_ADVANCED is an explicit audit event; exact contiguous sequence/count and monotonic virtual-time checks detect missing evidence. Deletion regression added. |
| RR-005 | Phase/exposure checks alone allowed a ledger-consistent forged success/release without matching operation final/journal/accepted evidence. | Fixed: admitted terminal phase, exposure, final outcome, journal and accepted proof must agree. Crafted false-success and false-no-effect regressions added. |
| RR-006 | Session advisory-lock ownership cannot rely only on logical Connection.close when the generic DataSource could pool sessions. | Fixed: explicit unlock in AutoCloseable lease, connection cleanup, and a pooled-close proxy regression. Metadata begin/finish require RUNNING and terminal reports cannot be replaced. |
| RR-007 | Reflection over declared member classes omits anonymous/compiler-generated runtime classes, including actual JdbcPaymentStore$1, ReplayRunner$1 and ReplayPlanJson$1 resources. | Fixed: getNestMembers covers the current complete class families; a filesystem-versus-pinned-family regression checks actual generated resources. |
| RR-008 | Explicit null boolean plan parameters can deserialize as false unless null-for-primitives rejection is enabled. | Fixed: FAIL_ON_NULL_FOR_PRIMITIVES and a null-boolean rejection regression, preserving intentionally nullable boxed VersionRef fields. |

## Source properties checked

The expanded plan has closed action-specific records, exact versions, canonical signed-64-bit seed strings, named deterministic choices, explicit expected results and logical references. Strict parsing rejects duplicate, missing, unknown, coerced and trailing data. Choice generation is outside execution; persisted work selection uses kind priority and immutable enqueue sequence, while expiry/revocation scans use intent creation order.

V004-V006 are additive. Historical backfill is explicitly LEGACY and does not pretend to reconstruct earlier scheduling. Normalized financial tables and the independent simulator journal remain separate. No HTTP route exposes replay execution, ownership, event injection or fault recipes.

The REPLAY run stores an owner capability; ordinary PaymentServices.local stores lack it and are refused under the run-row lock. The runner composes the private owned store and fixed fixture actor roles. This is a trusted in-process capability boundary, not a production identity provider. Its owner token is excluded from reports and normalized state.

STARTED commits before the scheduled action; DONE and discovered relationship aliases commit together afterward. Stable command keys derive from plan identity/ordinal/actor. An orphan STARTED produces INCOMPLETE and never repeats RUN_NEXT to select another operation. Fresh replay creates a new run; original unknown exposure is preserved. An infrastructure failure may still leave RUNNING plus STARTED until subsequent inspection/continuation identifies the interruption; no automatic scheduling continuation is inferred from that gap.

WorkerReceipt comes from the actual selected operation and committed activity, without a second selection. Unchanged unknown polling is distinguishable from progress. Its audit extent encompasses concurrent actions deliberately placed inside a finite recipe; it is not an attribution claim that every audit row was emitted by the worker itself.

Both private barrier recipes wait outside core transactions. Admission/revoke/send preserves the admitted operation; seal/send exercises the durable no-effect fence before stale physical send. Worker termination is checked before final evidence, otherwise only an incomplete diagnostic is stored. These are finite recorded schedules, not an arbitrary concurrent scheduler.

The normalizer reads one REPEATABLE READ database snapshot. It validates raw operation hashes including physical scope and credential binding before substituting semantic aliases and computing a separate logical binding hash. Journal/core identity, money, stable adapter key, event history, accepted evidence and held exposure are checked before an equality digest is emitted. Unknown references fail instead of receiving invented aliases.

Runtime identities include selected compiled class nest families, including anonymous/compiler-generated members, and all migration resource bytes, with separate fixture and policy identities. They identify this local artifact set, not signed provenance or every third-party runtime dependency. Review/test tooling avoids concurrent Java compilation while those identities are evaluated.

## Evidence and limits

The reviewer inspected test sources and earlier generated Surefire reports; Java tests were executed by the implementation author, with final aggregate validation owned by the integration owner. The reviewer deliberately did not launch competing Maven builds because replay plans pin actual compiled class resources.

At final frozen-source handoff, the author reported **47 PostgreSQL tests passed**: ReplayIntegrationTest 14, DurableBoundaryIntegrationTest 11 and PaymentCoreIntegrationTest 22. The separately executed final ReplayPlanTest passed all **7** tests, including generated-class coverage and primitive-null rejection. All reported zero failures, errors and skips. The command used Maven 3.9.16 with `-Dmaven.repo.local=C:\project\agentic-payments\.tools\m2 -q -Dtest=ReplayIntegrationTest,DurableBoundaryIntegrationTest,PaymentCoreIntegrationTest test`, local JDK 21 and PAYMENTS_DB environment configuration; plan tests used `-Dtest=ReplayPlanTest test`.

These final counts are attributed to the author's execution, not a reviewer rerun. The integration owner's fresh-database aggregate had begun and removed the preceding Surefire files before the reviewer could reread those final XML reports. The reviewer independently rehashed the final key source files and confirmed the handoff identities. The later aggregate result belongs in the integration evidence record and must not be inferred from this source review.

Replay tests cover fresh runs with equal core/journal/audit/combined digests, explicit multiple-intent work order, reconstruction, owner denials, pending exposure after orphan STARTED, both recipes, unsafe diagnostic retention and negative raw identity/money/proof/action/grant/request checks. Golden tests pin named-choice vectors and a 1106-byte canonical plan independently of changing runtime class hashes.

Eleven durable boundary tests extend the prior reference evidence: a fresh JVM halts immediately after independent journal commit and before core receipt; subsequent fresh JVMs recover and replay the same operation without a second effect. Parent-side SQL witnesses establish ADMITTED/HELD and missing core evidence before recovery. Six barrier-controlled tests exercise both actual PostgreSQL lock orders for clock versus approval, admission and action completion at exact expiry. Additional cases check trusted checkout scope and retained conflicting/stale evidence.

These tests add real abrupt-process evidence for one precise commit gap. They do not prove every kill point or crash recovery of the replay scheduler itself. The orphan replay test injects the persisted STARTED gap in process; it is correctly separate from the fresh-JVM payment recovery test.

A plan PASS means its recorded expectations and implemented raw checks passed. It can legitimately leave a pending/unknown operation held; it is not a claim that every payment succeeded or that CT-001 through CT-012 all passed.

The current replay normalizer rejects authenticated conflicts, disputed exposure and frozen budgets before producing a digest. This is fail-closed, but it does not implement ADR 0003's broader reproducible safe-conflict projection. Preserve that CT-011 follow-on scope. Clock-race tests are core evidence, not additional replay recipes. Generic provider TCK, native AP2/ACP authority and real connectors have separate gates.

## Final reviewed identities

Frozen source hashes independently computed after the author applied the findings and reconfirmed against the final handoff. Directory manifests sort complete repository-relative POSIX path strings ordinally (`key=lambda p:p.as_posix()`), concatenate path, TAB, lowercase SHA-256 and LF for each file, then hash UTF-8 bytes without a BOM. Sorting WindowsPath components is a different ordering and must not be substituted.

| Artifact | SHA-256 |
| --- | --- |
| `src/main/java/io/agenticpayments/replay`, 13 Java-file manifest | `5158f52b8bd51c274eaaefda1e98c0c41c7e60ae83430640e3147c99936b65b7` |
| `src/main/resources/db/V004` through `V006`, 3-file manifest | `19700b82bf81c05f6e3b383b9570573009ccd80b39f15abb6bdd4279c22204f9` |
| `src/test/java/io/agenticpayments/replay`, 3 Java-file manifest | `57966b366dd5b8aad06b79ded772af923af5034fdc8138d674774aaba04eec02` |
| `ReplayRunner.java` | `6f3f43564144e49ef0f4909128ca3cbec9e953942f72c6b829896bd17ad5e3c9` |
| `ReplayNormalizer.java` | `a2ce553a049f572b96f4d2ef35654b5217a20c62bffd8032bfbf31c73236fd2c` |
| `ReplayRepository.java` | `22fc752768732c21128cd5aef7fb656968a0c7ede8d6aaf94b23255bdd34abad` |
| `ReplayArtifacts.java` | `cff99dfcceacc5ac136eb00a402f05d621ae6227ec973ad737ed335782d4b2ad` |
| `ReplayJson.java` | `206493e65581704e32e88ca9452e2bd71568553f14d27a95d95d8dfd4eed2f94` |
| `PaymentStore.java` | `aa66f302813df255d48693ebfbbd08d221e0f452a0eae0e73560056948952166` |
| `PaymentService.java` | `a7163dadcf3aa5f5534aed7050138fdc6196dd2766b169f310a8da0ffdac51bb` |
| `WorkerReceipt.java` | `2b7e86c9a3cd59da66dae332569c6feb8f6aea7e1a5adfaf3e48c38efefb2cca` |
| `JdbcPaymentStore.java` | `e687f7863978ce2e034dfc4132d1e652f48cf99837a804aed890b95dd5d1f85d` |
| `DatabaseMigrator.java` | `156428d4c011a150ffe506fef7e4da8e5a9ab288d3d42bbee720dc309ba482e4` |
| `DurableBoundaryIntegrationTest.java` | `9ff69bf1a4dc17710c6fa1897db2d5debd788053f7d6e5faa265e8fc1e0774a3` |
| `CrashWindowProcess.java` | `80d1e7c53ad5c777ddd84b8df06c7d23f27b0d793d4aa56c7782dbd584e17672` |
| `RunLockBarrierDataSource.java` | `1dcb13c556b403b86009cbca5d3992b6446eecffc5146978bb8f0a86ee5e442d` |
| `CoreTestSupport.java` | `e37717c2e7d1460e0137e97a2aebe573f28c47d096a5960baf9cbfdaa4d6904b` |

Only this review document is owned by the reviewer for the replay task. No application source, migration, test or runtime artifact was modified by the reviewer.
