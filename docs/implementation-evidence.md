# Reference implementation evidence

Date: 2026-11-06. Profile: `local-fake-single-tender-0.1`; extension: `0.1.0-draft`; contract: `simulator-api-0.1-draft`.

## Executed aggregate run

Command: `python scripts/conformance.py`, using project-local CPython 3.12.14, Temurin 21.0.13+9, Maven 3.9.16, Node 24.16.0 and PostgreSQL 17.11.
Database credentials were read from ignored local configuration into process environment; no credential values enter this record.

Source-manifest SHA-256 before and after the run: `8e9ea08dbb169b39e7aece5ba3eb5f58d2ce7078eea3a6cc5051a061b3717746`.
The manifests matched. This final run used LF-normalized source matching Git checkout; two dependency inventory/lock files had only newline normalization after the preceding successful run. The runner covers source, schemas, fixtures, build manifest, scripts, UI and workflow locks; generated build output and local secrets are excluded.

| Check | Actual result |
| --- | --- |
| Java/PostgreSQL | 47 tests, zero failures/errors/skips: 22 database/core, 19 SDK, four checkout/golden, two operation-binding. |
| HTTP | Seven tests pass, including full synthetic execution, trusted challenge boundary, replay, strict JSON/UTF-8, origin/authentication and unsupported providers. |
| TypeScript UI | Build and three regressions pass: trusted snapshot display, changed-intent invalidation, stable approval retry key. |
| LangGraph | 12 tests pass: ten graph/checkpoint tests and two real Java/PostgreSQL workflows using fresh Python processes. |
| Contracts | 20 schemas; 16 positive, 24 schema-negative, six binding-negative and one parser-negative fixture check pass. |
| Visual UI inspection | Desktop 1280x1000 and mobile 390x844 rendered in headless installed Edge; no clipped controls or horizontal overflow observed. |

Aggregate preview result: **PASS**. Certification verdict: **NOT_CERTIFIED**. Local machine-readable report and per-stage logs are generated under ignored `target/conformance/`; regenerate them from the reviewed source. This record preserves the observed counts and source identity without publishing local logs or environment data.

## Independent review and limitations

[Astra source review](reviews/0006-reference-code-review.md) and [Sol 6.1 High client/tooling review](reviews/0007-client-tooling-review.md) identify reviewed file hashes, defects corrected and separately executed checks. The earlier plan review remains in review 0002; execution-design review is 0005. The integration owner accepts only the bounded synthetic reference.

The suite does not yet prove full seeded multi-intent schedule replay, every concurrent clock order, a complete process-kill financial crash matrix or comprehensive adversarial/privacy coverage. The JDBC repository currently also coordinates transactions; further application/domain separation is planned. Outbox claim fences are implemented, while timed worker leases are not.

No real AP2/ACP interoperability, provider sandbox, 3DS, wallet/bank/voucher, split tender, refund, live payment or managed-service claim follows from these tests. The report retains missing obligations and `--require-certification` fails accordingly. Public licensing/package release remains undecided.

## Publication record

Published commits: `69fe8d7` (contracts/SDK/toolchain) and `314bd2fe2303892857816944e97a990195581dce` (reference implementation) on `codex/reference-platform`. Hosted [reference CI](https://github.com/manishgh/agentic-payments/actions/runs/42302509998) and [documentation CI](https://github.com/manishgh/agentic-payments/actions/runs/42302510197) both passed for 314bd2f. Subsequent development has its own evidence; this record describes that exact milestone.

## Extensibility and adapter TCK checkpoint

The staged SDK/TCK/upstream checkpoint was exported with `git archive` into an isolated ignored checkout, installed from its pinned dependencies and tested against a newly created PostgreSQL database. Concurrent replay development was excluded from this checkpoint. Evidence run `545005e5ca6a4f008a161cfc84091b7e` retained identical before/after source-manifest SHA-256 `c2b7722352753012f3de99690fbf99d49ed613eb94f1457055d944e752c0c83b`.

All eight aggregate stages passed: 67 Java tests with no failures/errors/skips, eight HTTP tests, three UI regressions, 12 LangGraph tests, four upstream integrity tests and 12 structural probes. Contracts covered 21 schemas, 17 positive, 27 schema-negative, six binding-negative and one parser-negative fixture. The separately executed JDBC adapter TCK passed its four selected cases and retained its exact plan and evaluated capabilities. `jdeps -summary` found only `java.base` for the built adapter SDK classifier JAR; release POM separation remains required before package publication.

[Independent SDK/TCK review](reviews/0008-extension-tck-review.md) accepted the bounded source after corrections. [Independent upstream review](reviews/0009-upstream-bridge-review.md) accepted only the pinned research and a separate offline artifact-lab contract. This checkpoint does not include replay implementation or the artifact lab. Its certification verdict remains **NOT_CERTIFIED**; structural schema acceptance is not cryptographic or wire interoperability evidence.

Published as `0f60d93de1045967a48a65c521ceae0abf7bcfe1` on the implementation branch. Hosted [reference checks](https://github.com/manishgh/agentic-payments/actions/runs/42307004050) and [documentation checks](https://github.com/manishgh/agentic-payments/actions/runs/42307004092) both passed for that exact commit.

## Integrated replay and offline artifact checkpoint

Date: 2026-11-07, Europe/Berlin. The integration owner ran `python scripts/conformance.py` against a fresh PostgreSQL database after independent replay, artifact-lab and integration-tooling acceptance. Run `b46fbaca340140e8a10db197970afa55` passed all 11 stages with identical before/after source-manifest SHA-256 `66121c775907dbccce4d1ffd439c990ca00e0eda3c60d307f3891efd93fd3d89`. The 219-file manifest now sorts complete POSIX path strings, making its ordering portable across Windows and Linux. Historical hashes above retain their original runner semantics.

| Check | Observed result |
| --- | --- |
| Java/PostgreSQL | 99 tests, no failures/errors/skips: 33 core/boundary, two operation-binding, 21 replay/plan, 43 SDK/checkout/descriptor/TCK. All six migrations applied to the fresh database. |
| Crash and clock boundaries | Real JVM halt after independent journal commit; two fresh recovery processes preserve one effect. Six PostgreSQL lock-order cases cover clock versus approval, admission and customer action. |
| Replay CLI | Two-intent example passed with raw invariants and normalized core/journal/audit/combined digests. Separate integration tests compare fresh runs and retain failed/incomplete evidence. |
| Adapter TCK and package | JDBC CLI passed four selected cases with plan provenance; SDK JAR inspection reports only java.base. |
| Offline lab | 26 tests and the safe demo passed; merchant signature/content checks retain native AP2 authority, interoperability and dispatch as false. |
| Upstream | Five integrity tests, 11 exact cached schema files and 12 structural probes passed without runtime downloads. |
| Clients | Eight HTTP, three UI and 12 LangGraph tests passed, including fresh Python workflow processes. |
| Draft contracts | 21 schemas; 17 positive, 27 schema-negative, six binding-negative and one parser-negative fixture passed. |

The 11-stage run includes 153 test methods across Java, HTTP, UI, LangGraph, lab and upstream-integrity suites; structural probes, fixture checks and CLI executions are recorded separately. Documentation and staged whitespace checks also passed. Generated evidence and logs remain in ignored target/conformance and the per-run tools archive; they contain no provider credentials.

[Replay review 0010](reviews/0010-replay-code-review.md), [artifact review 0011](reviews/0011-offline-artifact-code-review.md) and [integration review 0012](reviews/0012-integration-tooling-review.md) record exact identities and independent review limits. The owner independently reproduced the replay source/test, lab and integration group hashes before publication. Final review findings were fixed before this aggregate run.

Result: **preview checks PASS; NOT_CERTIFIED**. Authenticated conflicts, disputed exposure and frozen budgets fail replay normalization without an equality digest; their reproducible safe-conflict projection remains unfinished. The complete adversarial/privacy and process-crash matrix, certification policy, real AP2/ACP authority/wire integration, provider connectors, split tender/refunds and managed operations remain separate work. Hosted validation for this checkpoint is attached to its GitHub commit; prior hosted runs above validate only their named earlier revisions.

Publication: `9fd7dbbc5cef135007b7f2253aba24c825386954` on `codex/reference-platform`. Hosted [reference CI](https://github.com/manishgh/agentic-payments/actions/runs/42309571643) and [documentation CI](https://github.com/manishgh/agentic-payments/actions/runs/42309571661) passed for that commit.

## Requirement-bound evidence checkpoint

Date: 2026-11-07. Following accepted design review 0013 and independent source reviews 0014/0015, the integration owner ran `python scripts/conformance.py` against a newly created isolated PostgreSQL database. Run `7fb95c298b0f4f9aa09e4c096f4e23b3` passed all twelve stages with unchanged 238-file source SHA-256 `04a8f6c197987367d8ccfa19f85db54f91adf24c032d68f7de7eb1cc562219ca`. Actual runtimes: Java 21.0.13, Maven 3.9.16, Python 3.12.14 and Node 24.16.0. No dependency or migration was added by this slice.

| Suite | Actual passing test methods |
| --- | --- |
| Java/PostgreSQL | 107 |
| Evidence collectors, evaluator and runner | 42 |
| HTTP | 9 |
| Trusted UI | 3 |
| LangGraph | 12 |
| Offline signed-checkout lab | 26 |
| Upstream integrity | 5 |
| Total | 204 |

All suites have zero failures, errors, skips, expected failures, unexpected successes and duplicate observations. The aggregate also passed draft schema/fixture checks, twelve structural probes, offline demo, adapter TCK CLI and recorded replay CLI. These supplementary checks are separate from the 204 method count and do not invent named requirement credit.

The 48 selected observations all qualify PASS. The versioned mapping still reports eleven INCOMPLETE CT rows with explicit gaps; CT-008 is NOT_APPLICABLE for the absent voucher/split profile only after its required baseline refusal guard passes. The result is **preview PASS, evidence VALID, NOT_CERTIFIED**. No full CT or external integration is silently marked complete.

The owner separately ran `python -m scripts.requirement_evidence --bundle target/conformance` successfully. Final local report SHA-256 is `55f7eef5eab4486ee1df1419a54911aeabe0d2f714781ee488ccc69b91acd8dc`. The safe bundle includes its owner receipt, producer artifacts and current-run hashes; raw diagnostics remain private under ignored `.tools/conformance/`. CI uploads only after complete bundle/source/claim verification, including refusal of unexpected files. Preserve the exact source revision with historical evidence.

The first exploratory run `2ab79817ec1d4d11925f530ff18b26bc` had passing stage exits but source changed during review. It correctly failed evidence acceptance and is retained privately as a failing run; its results are not substituted for the final run. Review 0015 records the nested-XML injection, failure-precedence, CI upload gating, current-source identity and extra-file publication defects corrected before acceptance.

The event/privacy tests add selected durable-journal, conflict and isolated-process canary coverage only. Concurrent event delivery, complete telemetry sinks, full crash/revocation matrices, conflict normalization, certification policy, real native AP2/ACP integration and provider/managed operation remain open. See the [evidence usage guide](requirement-evidence.md) and [backlog](backlog.md).
