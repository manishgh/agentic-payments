# Review 0006: Reference implementation source review

Status: completed source re-review, 2026-11-06. Recommend accepting the bounded local reference implementation at the source identities below. Full simulator conformance remains open; this is not a certification decision.
Reviewer: independent execution/concurrency architect; no application source authored in this implementation slice.
The reviewer authored ADR 0002 earlier; source review here checks the separately authored implementation against the integration owner's accepted design.

## Scope

Review covers Java application/domain/SDK/infrastructure, PostgreSQL migration, loopback HTTP transport and the trusted checkout handoff.
Target is `local-fake-single-tender-0.1` / `0.1.0-draft`. No real AP2/ACP/provider behavior is assessed.
The integration owner retains acceptance authority. Exact source identities and inspected test evidence are recorded below.

## Findings sent during implementation

| ID | Severity | Finding / required resolution | Disposition |
| --- | --- | --- | --- |
| CR-001 | High | Core checked only its static capability catalog and never negotiated the configured adapter. Missing authentication/action capability could survive into reservation/admission. Require the exact local offer or persisted negotiated selection before reservation and at admission. | Closed. Exact local offer checked before commands and worker steps; reduced-capability regression passed. |
| CR-002 | High | Frozen-budget dispatch branch treated the conflict as authority expiry and released a pre-admission hold. Later NO_EFFECT application could also release while frozen. Preserve disputed exposure and evidence; block admission and automatic release while frozen. | Closed. Dispatch, termination and NO_EFFECT guards retain exposure. Authenticated conflict followed by no-effect/revoke/expiry regression passed. |
| CR-003 | High | Trusted UI displayed money from the earlier status response while approving the later checkout digest/version. A concurrent EUR-to-USD requote could authorize a different currency from the displayed amount. Render approval details from the exact trusted checkout snapshot. | Closed. Display and selected grant derive from reviewed checkout money. Reviewer independently ran the stale-status/checkout UI regression successfully. |
| CR-004 | Medium | Journal replay compared identity/digest/money but ignored changed synthetic scenario, despite scenario changing success/no-effect behavior. Bind scenario in request digest and reject changed scenario for an existing key. | Corrected source independently inspected: versioned OperationBinding includes scenario, journal compares scenario, and changed-scenario test passes in the observed 14-test database run. |
| CR-005 | High | Dispatch checked a customer action only if a row happened to exist. A faulty prepare result could omit a required synthetic challenge. Derive requirement from trusted scenario and enforce current verified action/binding at admission. | Closed. Preparation action type must match scenario; admission requires a verified current bound row. Faulty preparation regression passed. |
| CR-006 | Medium | Initial application facade imported/constructed JDBC infrastructure directly. | Corrected source independently inspected: PaymentStore port and infrastructure PaymentServices composition factory remove those imports. |
| CR-007 | Acceptance gate | Initial tree had SDK/golden tests but no executable core database/recovery evidence. A small happy-path suite cannot establish all CT cases. | Bounded source gate satisfied by inspected 45-test Java run, including 22 real-PostgreSQL tests. Broader CT gaps remain below; no full-profile pass recommended. |
| CR-008 | Medium | A changed authenticated fact with a duplicate event ID froze the budget but discarded the second fact; the evidence primary key prevented retaining both variants. | Closed. V002 adds safe conflict facts and digests; all conflict branches preserve variants. Same-ID retention regression passed. |
| CR-009 | Medium | Status selected the highest-sequence evidence even when it was quarantined or pending after success. | Closed. V003 records accepted evidence; status selects accepted evidence matching stored final outcome. Stable-success-evidence regression passed. |

## Trusted checkout addition

The additive `GET /intents/{id}/checkout` contract is appropriate for this draft local profile.
It obtains merchant, money, revision, digest, expiry and version directly from the authoritative snapshot instead of trusting graph-provided approval values.
The inspected implementation requires `approval:write`, tenant/run/merchant scope, the permitted fixture grant and matching trusted subject.
Only QUOTED/APPROVED snapshots are returned, without credential references or raw authority payloads.
Approval still rechecks current expected version/digest/grant in its transaction, so a subsequent requote cannot reuse the reviewed snapshot.
The UI must display values from this same response; CR-003 addresses that necessary client condition.
The HTTP test source exercises graph-role denial, and the UI regression exercises status-versus-checkout requote mismatch.
Core action/status tests exercise wrong subject/run boundaries; a full checkout-specific wrong-subject/merchant/run matrix remains additional conformance evidence.

## Positive source observations

- All core mutations take the run row lock; authoritative status uses a coherent REPEATABLE READ transaction.
- Attempts, financial operations, adapter keys and reservations have tenant/run-qualified uniqueness and references.
- Execute persists a single operation and work before dispatch; admission commits before the independent journal call.
- Journal mutation uses a separate connection and unique insertion plus row lock, and does not lock core rows.
- Atomic sealing stores permanent NO_EFFECT; a duplicate send returns the winning durable journal fact rather than executing again.
- Lookup absence and transport failure preserve UNKNOWN/HELD; recovery reuses operation identity.
- Local event verification resolves journal-backed immutable event data; no public webhook/injection endpoint exists.
- Trusted actions require separate role/subject/scope and expected version; graph sessions have no approval/action roles.
- HTTP binds loopback, rejects unexpected origins/hosts and uses strict JSON parsing, bounded bodies and safe error messages.

These observations are source evidence, not a substitute for executable race, failure and authorization tests.

## Actual evidence inspected and executed

The reviewer inspected the final Surefire reports and their corresponding source: 22 `PaymentCoreIntegrationTest` cases, 19 `SdkContractTest` cases,
and four `GoldenContractTest` cases passed with zero failures/errors/skips. These tests were executed by the implementation owner against PostgreSQL;
the reviewer did not independently rerun the database suite. An earlier sandbox socket-permission initialization failure was followed by the successful run, not silently skipped.
After that full run, two additional `OperationBindingTest` cases passed in a targeted run; the reviewer inspected their source/report.
They pin the 666-byte operation encoding/digest and confirm a changed scenario changes its digest. This is 45 full-suite cases plus two separately run cases, not a claimed single 47-case run.
The owner's full command was Maven `-Dmaven.repo.local=C:\project\agentic-payments\.tools\m2 -q test` with the pinned JDK and configured local PostgreSQL environment;
the additional run selected `-Dtest=OperationBindingTest test`.

Database assertions cover exact checkout bytes, persistence after service reconstruction, command replay/conflict, concurrent budget contention,
response-loss UNKNOWN/HELD then same-journal recovery, trusted subject and one-use action, denial/expiry, both revoke/admission orders,
cross-tenant/run reads, split/unknown-capability refusal, changed checkout, journal seal fencing and changed scenario refusal.
Targeted regressions add reduced capabilities, omitted required challenge, bare NOT_FOUND hold before atomic seal,
acceptance-before-seal, a barrier between admission and physical send, stale prepare after revoke, conflicting event retention,
stable success evidence, and frozen exposure across later no-effect/revoke/expiry.

The reviewer independently executed `node --test ui/tests/review.test.cjs`: three tests passed with no failures/skips.
They verify displayed money/approval digest use one trusted snapshot, changing the typed intent invalidates review, and transport uncertainty preserves the same command key.
The reviewer inspected the six-case real HTTP test source in `scripts/test_http.py`; execution evidence for that suite belongs to the integration owner's report.
The reviewer also ran `pwsh -NoProfile -File scripts/Check-Docs.ps1` (30 Markdown files passed) and `git diff --check` (passed).

These are bounded tests, not a complete CT-001..012 certification suite. Remaining evidence must include:

- Concurrent same-key replay and explicit stale-worker fence reclamation, beyond sequential replay and stale preparation after revocation.
- Actual competing clock/admission/action transactions and verified-action expiry before admission, beyond sequential exact-boundary expiry.
- Fresh-process crash/restart at the acceptance/core-commit gap, beyond service reconstruction and simulated response loss.
- Concurrent journal seal/send contenders, beyond both deliberately selected sequential winner orders.
- Full altered-binding, spoof/cross-run event, late/reordered/contradictory sequence and action authorization matrices.
- Sensitive canaries across graph/checkpoints/logs/reports, beyond safe status and the narrow HTTP/UI checks inspected here.
- Pinned seed/configuration/explicit ordered schedule with normalized replay digests and failure-preserving machine-readable conformance reports.

In particular the current runner uses virtual time and claim fences but orders multiple pending operations by random UUID.
It does not yet establish CT-011 deterministic multi-intent replay from a pinned seed/schedule or a timed worker-lease model.
CT-008 voucher/split behavior remains unsupported; rejection tests do not turn it into a passed optional profile.

The run-serialized JDBC unit currently combines substantial use-case orchestration and persistence. The domain/SDK remain infrastructure-independent,
but extracting orchestration and typed aggregates would improve maintainability; do not describe the present implementation as fully separated clean architecture.
This concern is separate from the concrete financial/authentication blockers above and does not require a speculative production framework.

## Final artifact identity and verdict

No unresolved critical source defect remains in the bounded behavior reviewed here. Recommend source acceptance for the local synthetic reference,
with the explicit test and architectural limitations above. G3/full simulator conformance, real protocols/providers, sandbox connectors, package licensing
and deployment/live-operation gates remain separate and open. Python workflow implementation and browser visual quality are outside this review's source verdict.

The following SHA-256 group digests identify the exact working-tree files reviewed before commit. For each group, recursively include all regular files,
sort repository-relative forward-slash paths with ordinal comparison, concatenate `path`, TAB, lowercase file SHA-256, LF for each file,
then SHA-256 the resulting UTF-8 bytes without BOM. No ignored build outputs are included in these source directories.

| Source group | Files | Manifest SHA-256 |
| --- | --- | --- |
| `src/main/java` | 28 | `09e4c7f3a0b1b37667b5ed11eaef2ec565f10005bb47c8098ad5fcdbb6008525` |
| `src/main/resources/db` | 3 | `323d12793e116676241c2990896da03fcf8e6a2c199f6fe30a43269a0e09d4c1` |
| `src/test/java` | 4 | `21a2ef0bfc6a3094b8fa42871c168727443cad9d10e5f7621efb75721e76ab87` |
| `ui/src` | 1 | `98af90a47cdebb99f4134c3d70984ea2d10416747404dc8e75e88abd171ad4c9` |
| `ui/tests` | 1 | `f0eb68639b4c6fe71ab1122aac5309fa827020dfc7ccf7373406bbb6c1b3b743` |

Additional single-file identities: `JdbcPaymentStore.java` is `142a31ae15bc417a45d0dc58326335b883f75c31aa9143aacfc754a48e311218`;
`JdbcSimulatorAdapter.java` is `1aeefe162ba20542a8fbd558173065ca61e2827de88f983aeec34f75444c6f04`;
`scripts/test_http.py` is `0157f872c0b8fb1495a2a17b6fdb6a24aa279d0fe0908fee00133dd23848f599`;
`docs/contracts-implementation.md` is `941e635fc9e8f1daae56782099790708086b8ace2b7ce2f70278866e8f9230e8`.
Substantive changes after these identities require follow-up review. The integration owner must associate the final milestone commit with this record.
