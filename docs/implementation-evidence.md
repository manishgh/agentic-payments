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

## Independent AP2 engine checkpoint

Date: 2026-11-07. Following accepted ADR 0005, requirements and independent Astra reviews 0016/0017, the owner ran `python scripts/conformance.py` against a new isolated PostgreSQL database. Run `90b8afaf1c3140f792a9b628d16c35de` passed all twelve stages with identical before/after 257-file source SHA-256 `406fb4e96eb6fad8dec4f4488af5480ad8e082ba3dee434d51c242c6cfd47ea5`. No dependency or migration was added. Parallel Sol authors implemented claims/exchange and independent Python vectors; the owner integrated cryptographic verification, replay guards and packaging; Astra independently reviewed design and source.

| Suite | Actual passing test methods |
| --- | --- |
| Java/PostgreSQL, including 37 AP2 methods | 144 |
| Evidence collectors, evaluator and runner | 45 |
| HTTP | 9 |
| Trusted UI | 3 |
| LangGraph | 12 |
| Offline lab and independent AP2 verifier | 30 |
| Upstream integrity | 6 |
| Total | 249 |

There were zero failures, errors, skips, expected failures, unexpected successes or duplicate observations. Supplementary checks passed: draft contracts, twelve upstream structural probes using twelve cached schema dependencies, offline demo, adapter TCK and recorded replay CLI. The Java protocol tests consume 74 freshly generated Python vectors (seven accepted, 67 rejected); independent Python verification checks five Java signatures and two exact receipt references from actual Merchant/CP/processor decisions. These vectors are checks inside test methods, not 74 additional methods or evidence of Google interoperability.

The new `ap2-engine` classifier contains 33 classes, all in `io/agenticpayments/ap2/`. Its local JAR SHA-256 is `c0fe54206ce79f1e3b29d4fb4d86f172c20bfe61b2cae900c2ea249c33fa3150`; the receipt separately records this and the reference/SDK artifacts. This is a build artifact, not a published package release. Its runtime uses JDK JCA and the already pinned Jackson libraries; no Google implementation is imported or wrapped.

Independent `python -m scripts.requirement_evidence --bundle target/conformance` validation passed against the current checkout. Report SHA-256: `53b61024ac6fd05115104d24c9b4509b0ee652e7a2e606f1b1a9b0748ab0e8c2`. All 48 existing selected observations qualify PASS. New AP2 tests contribute counts but are not silently mapped to full CT-007: eleven CT rows remain INCOMPLETE, guarded CT-008 remains NOT_APPLICABLE, and the verdict remains **preview PASS, evidence VALID, NOT_CERTIFIED**.

The preceding run `302b066567034f7e926f7b1f6775714d` passed its twelve stage commands but failed final evidence evaluation: receipt/report schemas still limited build artifacts to two. It produced no accepted current report. AS-006 in [source review 0017](reviews/0017-independent-ap2-source-review.md) records the correction to three, the new full-bundle regression that failed before the fix and passed afterward, independent re-review and refreshed 27-file source identity. Its raw diagnostics are retained privately; this checkpoint uses only the later complete run.

The accepted profile is `ap2-v0.2-direct-tap-synthetic-0.1`: independently authored native direct mandates, role-bound trust, signed success/error exchange and process-local replay protection. Durable financial admission, negotiated ACP merchant transport, real consent/trust enrollment, delegated/User Credential profiles, real providers and external peer interoperability remain open. See [engine usage and composition](ap2-engine.md).

## Durable native AP2/ACP and runtime checkpoint

Date: 2026-11-07. Accepted ADRs 0006/0007 and independent Astra source reviews 0019/0021 cover the native financial composition and the runtime boundary. Sol 6.1 High agents authored bounded core/runtime work; Astra handled complex cryptographic composition and independent design/source reviews. The integration owner reproduced both final review manifests before publication.

Fresh-database run `ac79d0a17b14434e8f6e23995ce00cf9` passed all twelve stages with unchanged 294-file source SHA-256 `9875cd5084a44ecd048a5429b95d71bc6fafb14becf4dae8adf6b66857912b68`. Separate semantic bundle validation passed. Report SHA-256: `c1b86cd232036bdc0038f430551689903adfd1604f96e3be66f9fea058fb7421`.

| Suite | Passing test methods |
| --- | --- |
| Java/PostgreSQL | 185 |
| Evidence tooling and Temporal bootstrap | 50 |
| HTTP | 11 |
| Trusted UI | 3 |
| LangGraph and durable runtime | 32 |
| Offline lab and independent AP2 verification | 30 |
| Upstream integrity | 6 |
| Total | 317 |

There were zero failures, errors, skips, expected failures, unexpected successes or duplicate observations. Contract checks passed 21 schemas, 18 positive fixtures, 27 schema negatives, six binding negatives and one parser negative. Independent Python checks verified the fresh Java ACP checkout against the pinned schema and retained the existing AP2 cross-language signature/reference checks. Adapter TCK, recorded replay and structural probes also passed.

New Java evidence covers transactional authority/replay/reservation binding, exact checkout bytes, restart without resigning accepted authority, concurrent cross-run replay, expiry/revocation ordering, malformed or unavailable artifacts, injected resolver/owner behavior and populated V006-to-V007 migration. Retryable crypto verification outages retain the same operation and HELD reservation; definitive signature rejection still prevents dispatch.

Actual Temporal tests use the pinned local CLI and Python SDK with persistent server storage and fresh worker processes. They cover accepted HTTP create/quote/execute crash boundaries, replay, cooperative pause, unpolled termination recovery, activity output rejection and the declared history-limit boundary. Two complementary native payment cases cover authentication pause/restart and response-loss UNKNOWN reconciliation with the original operation and one journal effect. Privacy checks inspect decoded protobuf payloads, JSON-escaped values, metadata, recovery rows and process diagnostics; a planted encoded-payload regression verifies the scanner detects those values.

The separately packaged AP2 engine now includes the injectable crypto boundary: 37 classes in the AP2/crypto packages. Local artifact SHA-256 values are reference `c7b5a1e08b91339fcaf1f53d139b9accfde5e4a2ad9bf9115fdf04e8fc8a8dde`, adapter SDK `b9b3e308a58fd87724ef02fdc91e2e813d1b4aadc086f2455208feee3ad01efd` and AP2 engine `a455316d9809e640cc7dc0206ccfcbfefd596884561747210725a8697534d703`. These identify local build artifacts, not package releases or cross-platform reproducibility guarantees.

Earlier run `ac40da97d91241c098685d41030096a7` passed 309 methods with stable source `95fa8d90dc5853d77f55ed76889f8defa30333fec6df19ebd25055a4f060688b`, before the final runtime review corrections. It is retained as preliminary evidence; the later 317-method run is the publication prerequisite. The accepted 30-file admission manifest is `923819cf2d6f356873b21d1dd61d221146f94691ffbaab5c6557ca91872bfa69`; the accepted 23-file runtime/tooling manifest is `f3b6720ab385f895afb3c458ce5663f775fa534adb267600e2ac206264f566f8`.

All 48 selected named observations qualify PASS. New native/runtime tests contribute evidence counts without silently closing the full CT matrix: eleven rows remain INCOMPLETE, CT-008 remains guarded NOT_APPLICABLE, and the verdict remains **preview PASS, evidence VALID, NOT_CERTIFIED**. Real providers and 3DS, merchant transport interoperability, broader AP2 authority, Cadence/Azure adapters, distributed registration and managed operation remain future work. No merge, package release, deployment or live payment is authorized by this checkpoint.

The earlier 317-method run `b3da0d3a6c12498199e4570431171e26` also passed before publication formatting. Staging then identified extra terminal line breaks in ADR 0006. Independent [review 0022](reviews/0022-design-format-review.md) proved the correction changes no design text and leaves all 53 implementation review entries unchanged. Because the document participates in source identity, the owner ran the complete suite again against another fresh database; the run at the top of this checkpoint validates the exact normalized publication bytes. No whitespace or evidence check was disabled.

## Native application coordination checkpoint

Date: 2026-11-07. Sol 6.1 High agents authored the bounded coordinator and independent PostgreSQL regression tests; a separate Sol plan review and Astra design/source reviews 0023/0024 preceded acceptance. [ADR 0008](adr/0008-native-application-coordination.md) preserves existing contracts and transaction ordering. The owner reproduced the twelve-file reviewed manifest `a78b9de6a8b0f9ab344edd483b6a44f2291eeeebb5050e115a85549081e433b8` and matched all staged source bytes before the aggregate.

The owner ran `python scripts/conformance.py` against the fresh isolated PostgreSQL database `agentic_coordination_20261107_final`. Run `ee1b4a75992f4ee788ed2cab4376516f` passed all twelve stages with unchanged 299-file source SHA-256 `ae26d0b6006b40e783d4d8fed1200de1034772e5b2ca43fac5e1238e97a10a9b`. Separate `python -m scripts.requirement_evidence --bundle target/conformance --verify-only` passed. Report SHA-256: `c968596678ea466f531f5649b05eb986761f30a42c9d58cfdd6b8ef1c2cb7910`.

| Suite | Passing test methods |
| --- | --- |
| Java/PostgreSQL | 194 |
| Evidence tooling and Temporal bootstrap | 50 |
| HTTP | 11 |
| Trusted UI | 3 |
| LangGraph and durable runtime | 32 |
| Offline lab and independent AP2 verification | 30 |
| Upstream integrity | 6 |
| Total | 326 |

There were zero failures, errors, skips, expected failures, unexpected successes or duplicate observations. The nine added methods cover decorated protocol/artifact ports, exact retained bytes and strict UTF-8, complete verified-role policy, independent legacy digest framing, pre-/post-provision trust snapshots, historical JSON shapes across service restart, and staged-read failure after real provisional authority/challenge inserts. The existing crypto-outage scenarios also assert unchanged dispatch fences. Earlier focused validation passed 50 distinct Java/PostgreSQL methods; the six application methods were rerun after the final digest-oracle correction. Fifty focused evidence-tooling methods passed separately. These preliminary runs are not substituted for the final aggregate.

Contract/schema checks, independent Java ACP/AP2 verification, adapter TCK, replay and real Temporal recovery stages all passed. No schema, dependency, HTTP API or extension port changed. Local build artifact SHA-256 values are reference `16fc94107639f5c2b00ed51bf6eea6c4d7a13df2f997949705a64a88a294af1e`, adapter SDK `ac0afa521a3e619586c5d72bed85f6fa03e73d22de8d1464af4226164789984b` and AP2 engine `4503e36a88eaafd4f45324ac171fa7b0de53e4bee19ecbbfc6a31fefdae3ecc7`.

All 48 selected observations qualify PASS; the new methods add evidence without changing the requirement mapping. Eleven CT rows remain INCOMPLETE, CT-008 remains guarded NOT_APPLICABLE, and the verdict remains **preview PASS, evidence VALID, NOT_CERTIFIED**. This is partial IMP-010 progress. JDBC still owns broader financial-use-case coordination; external ACP/providers, additional admission backends and production operation remain separate work. No merge, package release or deployment is part of this checkpoint.
