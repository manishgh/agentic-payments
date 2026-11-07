# Review 0014: Internal event and privacy regressions

Status: independent test-source review completed, 2026-11-07. Recommend acceptance of the bounded test additions at the identities below. Integration-owner acceptance and final aggregate evidence remain separate.
Reviewer: execution/security architect, independent of the Java test author and the integration owner authoring the HTTP test. Design baseline: [accepted ADR 0004](../adr/0004-requirement-evidence.md) and [review 0013](0013-evidence-design-review.md).
Scope: the new AdversarialEvidenceIntegrationTest and the isolated HTTP privacy test in scripts/test_http.py. This is test-source review, not acceptance of the separate evidence collector/evaluator or all CT-006/010 obligations.

## Findings

| ID | Finding | Disposition |
| --- | --- | --- |
| AT-001 | Initial HTTP privacy requests covered credentials but not the planned real waiting-action rejection surface. | Fixed: real WAITING_ACTION, trusted invalid decision and extra OTP field, unchanged action/payment state excluding correlation IDs, and fixture-credential scans. |
| AT-002 | General HTTP helpers could include a response body or ValidationError list in a failed privacy assertion; assertNotIn on captured output also prints that output on failure. | Fixed: privacy path avoids body-bearing create/wait helpers, schema-error assertion uses bool(errors), and state/output checks use booleans with fixed messages. |

No blocking finding was identified in the new Java source. No production Java or database migration change was part of this slice.

## Java observations checked

| Exact method in io.agenticpayments.core.AdversarialEvidenceIntegrationTest | Observed assertion scope |
| --- | --- |
| exactJournalEventReplayHasNoDurableEffect | Reconstructs service/adapter and replays the same journal event three times. A count/hash witness of all 18 relevant durable tables checks every row, including counters/versions; status proof and consumed exposure remain stable. |
| journalVerifierRejectsAbsentAndCrossBoundEventHandles | Actual JdbcSimulatorAdapter.verifyEvent refuses an absent handle, another real operation's handle and eight mutated expected identity fields. Durable state remains unchanged; the original positive handle still verifies. |
| forgedAdapterDeliveryCannotReachInbox | A privileged test adapter first returns forged delivery and then absent/different authoritative lookup. The two lookup calls are witnessed and rejection precedes inbox/accounting changes. This tests the existing trusted lookup boundary, not a public webhook. |
| authenticatedBindingVariantsAreRetainedWithoutAuthority | Ten fresh isolated runs exercise eight identity variants plus amount and currency conflicts. Original outcome/proof/accounting/journal persist; rejected evidence is retained and the original budget freezes with disputed exposure. |
| sameSequenceDistinctEventFreezesWithoutConsumption | A trusted equal-sequence/new-ID pending fact is quarantined. Exposure remains held, accepted fact sequence does not advance, and repeating the quarantined event produces no further durable changes. |
| contradictorySuccessAfterSealedNoEffectCannotRewriteRelease | Actual journal NO_EFFECT is followed by a trusted contradictory success fixture. The original release/final proof/journal persist; dispute/freeze records the contradiction without consuming unreserved funds. |
| higherSequenceSameFinalCannotReplaceAcceptedProof | Documents current terminal behavior: observed cursor advances, but the additional same-final event is not accepted proof and does not change status/accounting or journal truth. This is not a replay-normalizer acceptance test. |
| rejectedCredentialAndActionCanariesStayOutOfSafeCoreSurfaces | Positive controls place distinct synthetic values in rejected command objects. Fixed errors and actual safe checkout/status/action/fact outputs exclude them. Persisted command responses, audit, conflict and journal-identity surfaces are each nonempty before absence assertions are credited. |

The journal-verification test deliberately creates a second independent synthetic journal operation as a positive handle fixture. Its no-change witness starts afterward; it does not claim that the second fixture was admitted by the core. Trusted adapter conflict fixtures are explicitly separate from real local journal authentication.

The durable hash witness is appropriate for these sequential isolated runs, where no background worker mutates the scope. It is not a claim of a globally consistent concurrent database snapshot. Failed witness comparisons expose table identifiers and hashes, not raw row content.

## Evidence and remaining limits

The author ran AdversarialEvidenceIntegrationTest against actual PostgreSQL. The reviewer independently inspected the generated Surefire summary/XML: **8 tests, zero failures, errors or skips**, about 26.24 seconds. The reviewer read every new method and helper, checked the referenced core verification/application paths, and confirmed no production Java/migration diff. The reviewer did not run a competing Maven build.

The HTTP test owns a distinct Java subprocess, run and bearer values; instance overrides are removed in finally and do not terminate the shared class server. It uses both valid bearer canaries and rejected input across query, path, authorization, origin, credential and action fields. It inspects actual response bodies/headers and the terminated process's stdout/stderr. It creates a valid synthetic challenge before testing rejected action fields, and checks that action/payment state did not change. Legitimate fixture credential references are also scanned.

The integration owner reported the final refined HTTP method passed independently against the actual Java/PostgreSQL service: **one test, about 2.2 seconds**. The reviewer inspected the final source and independently computed its hash, but did not rerun that process test. An initial refinement compared fresh correlation IDs and failed; the correction excludes only correlationId and preserves all actual state fields in the comparison. No application code changed to make this test pass.

Both review findings are resolved; no blocking test-source finding remains within the stated scope. The whole nine-method HTTP suite and subsequent aggregate are integration-owner validation, not inferred from this focused run.

These methods are suitable named observations for the subsequent reviewed inventory. They do not grant full CT-006/010 PASS by themselves. The current capability boundary is synthetic and in-process: there is no new external ingress, cryptographic provider authentication, real OTP/challenge or live payment call.

Rejected-input canaries establish the named rejection/projection surfaces. They do not model every successful provider credential lifecycle, production logger/trace sink or graph path. Existing graph/checkpoint tests and new evidence-report privacy tests remain separate required observations. The report collector must also preserve failures and scope accurately before crediting these methods.

## Reviewed identities

| Artifact | SHA-256 |
| --- | --- |
| src/test/java/io/agenticpayments/core/AdversarialEvidenceIntegrationTest.java | `e23051b11da119415f233bfa0c20cafe4edc80a9bc33cc7bae287c3448be394c` |
| scripts/test_http.py | `9567d5efc4c28d3536f3d724c745ac4726cfa73b2566f894998a667bea3d6c3c` |

Only this review file was authored by the reviewer for this task; no implementation or test was edited. Documentation/whitespace checks and the final review hash are recorded in the handoff.
