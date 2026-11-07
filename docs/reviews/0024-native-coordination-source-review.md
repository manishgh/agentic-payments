# Review 0024: Native application coordination source

Status: independently accepted for the bounded ADR 0008 extraction, 2026-11-07, at the exact identities below. No blocking source finding remains. Publication still requires the integration owner's successful validation on unchanged reviewed source; this report does not assert those pending results or certification.

Reviewer: independent architecture/protocol reviewer, distinct from the implementation and integration-test authors. Baseline: `5e9d6329b0d8d67d92937c00034b888934e12b07`. The reviewer owns only this report for the source assessment, changed no implementation/tests and ran no Maven or application tests. [Design review 0023](0023-native-coordination-design-review.md) preceded implementation. The accepted ADR and design-review hashes remain unchanged.

## Implementation assessment

`NativeApplicationCoordinator` contains substantive application policy, not merely a new facade: exact checkout/mandate staging and reconstruction; stored checkout digest comparison before reads; reference metadata/bytes verification; strict UTF-8; authority reference-pair digest calculation/comparison; independent verifier invocation; and complete role/issuer/hash/expiry validation. Its dependencies are existing responsibility ports, immutable composition values, `ArtifactIntegrity`, application errors and the domain hash helper. It imports no JDBC, JSON codec, infrastructure implementation or protocol implementation. Tests instantiate it with decorated ports without a database.

The JDBC change is limited to constructing that coordinator, moving the two stored evidence records, and substituting explicit application phase calls for the extracted logic. `StoredCheckout` and `StoredAuthority` preserve all original component names, types and positions, including string-form authority instants. Added redacted string representations do not alter JSON properties. JDBC still owns Jackson and SQL mapping; no migration or serialization-format rewrite is introduced. SQL digest columns remain text, so passing their typed string values preserves the old comparisons.

The original capability checks, trusted provisioning/upsert, authoritative resolver wrapper, run lock, transaction retry/error handling, command replay, row predicates, all financial writes and native worker gate remain in place. No HTTP/profile/capability/dependency change or new admission interface is present. The reference transaction owner still contains context/nonce/deadline construction and broader use-case coordination; this is partial IMP-010 progress.

## Ordering and security checks

| Boundary | Source comparison result |
| --- | --- |
| Quote | Provision/upsert precedes freeze; the resolver binds after freeze; validation precedes ACP payload then JWT staging. Persisted checkout writes remain afterward. |
| Approval before issuance | Authorization/current-access and accepted-command replay behavior is unchanged. Existing grant, quote and budget checks precede provisional attempt insertion. Persisted checkout digest comparison and payload/JWT reads precede pre-provision resolver binding and checkout validation. |
| Approval after issuance | Provision/upsert and unchanged context generation precede issue/stage. Authority and all three challenge inserts remain before mandate reload. Checkout Mandate then Payment Mandate reload precedes a new resolver and independent verification/role validation. Replay insertion and effective expiry update still precede reservation/budget/state/command writes. |
| Execution reverification | Capability/eligibility, revision, authority row associations and effective expiry precede JSON/artifact reads. Checkout validation retains its own freshly bound resolver. Context reconstruction, mandate reads and persisted pair-digest comparison precede the separate verifier resolver. Effective expiry equality and complete replay/challenge predicates remain afterward. |
| Trust | No resolver/key enrollment code moved or changed. Scope/time/equality, revoked-key and half-open validity checks still constrain the configured resolver under the same admission run lock. |
| Failure and recovery | Existing fixed composition mappings and `nativeGate` catches are unchanged. 5xx rolls back and retries with exposure retained; non-5xx produces the existing pre-admission EXPIRED/CANCELLED transition. PREPARE, post-preparation and DISPATCH gates remain. Reconciliation of admitted/UNKNOWN work does not reverify or issue authority, and its operation/idempotency identity is unchanged. |

Artifact staging remains independently committed immutable storage; failed admission can leave unreferenced bytes. Neither a staged reference nor a constructed verification result gains a new financial-consumption path. The moved artifact decoder and role loop preserve their former rejection conditions and error types. No broad adapter/runtime/parser catch was added.

## Tests reviewed and corrections resolved

Six application tests exercise decorated checkout/issuer/verifier/artifact implementations; exact checkout reconstruction; independent mandate reload; no issuance/writes during reverification; stored digest rejection before retrieval; substituted bytes/media type/profile/length; foreign-profile rejection before store access; valid-digest malformed UTF-8; redacted evidence records; and missing, duplicate, extra, wrong-issuer, wrong-hash and invalid-expiry verifier outputs. The authority digest now has an independent JDK SHA-256 oracle for the original UTF-8 newline-delimited, `sha256:`-prefixed representation; it is not checked only against itself.

Three new PostgreSQL tests add these witnesses:

1. Fresh service custody retains the old merchant enrollment, validates checkout before provisioning, and sees the new TAP enrollment only in the post-provision snapshot. Event ordering and key-purpose lookups establish the two approval phases. Execution explicitly observes separately bound checkout and mandate snapshots, then worker verification completes without provisioning again.
2. Independently declared legacy JSON record shapes rewrite existing checkout and authority rows, compare property/value equivalence, and survive fresh services, accepted-command replay and execution. Retained JSON, artifact/enrollment counts and single attempt/operation/journal witnesses prevent replacement authority from masquerading as compatibility.
3. A staged mandate read fails after the active real PostgreSQL transaction observes one provisional attempt, one authority and three challenges, with no replay rows yet. A narrow data-source observer exposes that same connection to the test witness while immutable artifacts use the original data source. External reads after failure establish rollback of enrollment, admission/replay/reservation/budget/command work, retention of harmless staged artifacts, and one successful same-key retry.

During review, the integration-test author corrected a nonexistent retry accessor and added the execution snapshot witness; the coordinator-test author replaced a self-consistency digest assertion with the independent legacy-format oracle. Those corrections are included in the reviewed hashes. No production-code defect was found.

The existing crypto-outage helper now compares the DISPATCH fence/done row before and after failure. Its retained checkout and mandate scenarios also assert 503/SAME_COMMAND, READY/HELD exposure, unchanged operation/evidence, no journal effect and same-operation recovery. Definitive signature rejection remains distinct. Existing admission, registration, replay/race, expiry/revocation, restart, artifact-outage, legacy and UNKNOWN tests remain unchanged and are required regression coverage.

## Documentation and evidence boundary

The reviewed guides describe the application policy versus transaction ownership accurately and explicitly leave broader payment-store decomposition and external ACP transport outstanding. The shared core guide's stale claim that AP2 verification was absent is corrected without claiming an external merchant adapter. The evidence script adds only ADR 0008 and review 0023 to its explicit source identity; its existing recursive source/test inventory already captures the new Java files. Evaluation and certification rules are unchanged.

The reviewer independently read the final source/test corrections, recomputed all manifest hashes and ran `git diff --check` successfully. The integration owner owns focused Maven checks and the complete documented PostgreSQL/HTTP/UI/schema/workflow aggregate, plus documentation and whitespace validation. Preliminary test output obtained before the final test correction cannot substitute for validation of these final bytes. This report records source acceptance, not results from an unperformed reviewer test run.

Completion ledgers (`status`, `backlog`, `gates` and implementation-evidence reporting) are excluded from this source manifest so the integration owner can record actual subsequent results. They must not broaden this scope or rewrite the accepted design/source identities. Changes to a manifested file require renewed review of the changed bytes before publication.

## Reviewed manifest

| File | SHA-256 |
| --- | --- |
| `src/main/java/io/agenticpayments/application/NativeApplicationCoordinator.java` | `6e8f6a9411a205655aa19c6f988cef892924559a7d6d9e48eeaf0f317d508737` |
| `src/main/java/io/agenticpayments/infrastructure/JdbcPaymentStore.java` | `3c4cd8dc8980e6278d6571724ae832b029db101fc58500d607651f7fd4dffbae` |
| `src/test/java/io/agenticpayments/application/NativeApplicationCoordinatorTest.java` | `7501de82975b2091766277d9b883d4f868b7c8cc3a55f6061cf3869dbd954e61` |
| `src/test/java/io/agenticpayments/core/NativeCoordinationIntegrationTest.java` | `eb0c8702daa43967560594fed8376f7c0bb90b5f3944e3829818ca6addea0181` |
| `src/test/java/io/agenticpayments/core/CryptoAvailabilityIntegrationTest.java` | `7833e97879a68e219da5dd3314bbd27deeaf9284fb8fda5875d98c29a64a22a5` |
| `scripts/requirement_evidence.py` | `95ee1166bd10baaf23a9815adda44dda3601586b787815d6d6b00a0dd675deb1` |
| `README.md` | `409be6c63c26f783d115c7efe53d663376b5fcef18f66b55526518833db57df2` |
| `docs/architecture.md` | `59ad92bc46a87385909f7a9abd66fda8a4541261d153cf7683df9340b6c2eebc` |
| `docs/core-implementation.md` | `459da80d81bddfc8ee668989a3a071663b0562b1d356c6412d91cf184b6715ff` |
| `docs/durable-composition.md` | `c2dd60f5bcdba02638a54595327743d31388e9c2fecba797420b65a103431e80` |
| `docs/adr/0008-native-application-coordination.md` | `597d7bb960f6fcb6b1e295bf795a0834d800673ae552392fc220724999428aa3` |
| `docs/reviews/0023-native-coordination-design-review.md` | `12086a2522e12356a96d4a219500411d22d7c08326a0ede5c074446984ec9b7c` |

Accept this bounded source extraction subject to the unchanged-source validation gate. Real-provider/managed-operation, complete backend replacement, broad AP2/ACP interoperability and certification obligations remain outside this acceptance.
