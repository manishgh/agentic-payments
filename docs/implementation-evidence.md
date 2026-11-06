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
