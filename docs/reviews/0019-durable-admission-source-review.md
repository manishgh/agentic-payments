# Review 0019: Durable AP2 admission source

Status: accepted for the bounded ADR 0006 implementation, 2026-11-07. All five source findings are resolved. The unchanged-source aggregate passed for this admission snapshot. The separate runtime review requires corrections and a new aggregate before publication.

Reviewer: independent protocol/security reviewer. Baseline: `839b4cfcf5fd7a71d8452ccb5661fe70f1e50f0d`; accepted design: [ADR 0006](../adr/0006-durable-ap2-admission.md), SHA-256 `b15b91b3209a384ab25c9212513859706a85293b9c73166477413f352e052d0f`, and [review 0018](0018-durable-ap2-design-review.md). The reviewer owns this report only and has made no runtime, migration or test-source edits. Runtime scheduling and Temporal implementation belong to the separate ADR 0007/source gate.

## Reviewed boundaries

The source inspection covers the stable composition contracts and crypto strategy, independent ACP fixture/AP2 adapters, immutable artifact stores, JDBC admission integration and migration, service composition/capability gate, native database/contract tests, HTTP/schema extension and native ACP schema verification wiring.

| Boundary | Source assessment |
| --- | --- |
| Profile and trusted approval | Profile persists on each intent; quote and approval branch on that stored value. A core without native composition cannot use legacy approval to reserve a native intent. HTTP accepts reviewed revision/digest/grant only, rejects raw mandate fields, and retains separate approval/action roles. |
| Frozen ACP identity | The reference produces a bounded ACP session, verifies the signed original payload against frozen bytes and supported expected fields, and binds its exact compact merchant JWT to both native mandates. Re-signing identical JSON changes authorization identity. The Java fixture is separately checked against the pinned upstream schema. |
| Issuance versus authority | Trusted key provisioning is separate from mandate issuance. Issuer output cannot enroll a key. Approval reloads stored artifacts and invokes the registered verifier; it accepts no caller-created proof. CP and MPP checks remain distinct role-specific signature/trust checks. |
| Atomic authority | Attempt, authority/challenge association, tenant-wide role/issuer/JWT-ID replay identities, reservation, held amount and safe command result use one run-locked transaction. Cross-run replay has an independent PostgreSQL uniqueness constraint. Artifact staging commits separately but grants no authority and may leave unreferenced bytes on rollback. |
| Dispatch and recovery | Execute, preparation gates and dispatch reload native artifacts and check current grant/trust/expiry. Dispatch records admission before the adapter send. Later expiry/revocation does not release admitted UNKNOWN exposure or replace its operation; reconciliation retains the original adapter identity. |
| Privacy and portability | Contract artifact/key values redact representations. Private signing keys remain process-local; public trust and immutable artifacts survive restart. Actual signature, protocol, issuance and artifact-store adapters are injected. Registered key resolution retains authoritative enrollment policy; role-specific owner declarations are enforced. |

## Findings

| ID | Severity | Finding and required disposition | Status |
| --- | --- | --- | --- |
| DS-001 | P1 | `KeyResolver` was a nominal interface, while `JdbcPaymentStore` always constructed a private SQL resolver. Key-service replacement required changing core code or replacing the complete admission backend. Register a stable transaction-scoped resolver factory over an immutable enrolled-public-trust snapshot. No JDBC type enters the port. The core must retain authoritative revocation/validity ordering and reject returned key identity/material/version changes. Exercise the injected path and wrong-key/scope/version refusal in financial tests. | Resolved; source and passing registration tests inspected |
| DS-002 | P2 | Composition descriptors declare an owner, but initial capability selection ignored it. A conflicting checkout/authority execution owner was accepted despite the reviewed one-owner composition rule. Enforce role-appropriate owner declarations before native mutation and again at admission; test rejection without reservation or financial send. | Resolved; creation/dispatch owner-refusal test passed |
| DS-003 | P2 | The core artifact reader hashed a permissively UTF-8-decoded string re-encoded to bytes, rather than original returned bytes. A faulty replacement store could hide malformed-byte changes through replacement-character normalization. Verify the exact byte/reference identity and decode UTF-8 strictly before protocol use. The current ASCII fixture limits exposure but does not excuse weakening the stable byte-integrity contract. | Resolved; strict source path, corruption and reference-integrity tests inspected |
| DS-004 | P2 | The new `AUTHORITY_REPLAY` error emitted by the core was absent from the closed HTTP error-code schema. Add the error to the versioned schema/documentation and validate an emitted-shape replay response so the rejection path satisfies its advertised contract. | Corrected and independently schema-validated |
| DS-005 | P2 | `Ap2Crypto.verify` converted every signature-provider runtime exception to `INVALID_SIGNATURE`. A temporary replacement verifier outage therefore became invalid authority and canceled/released a READY attempt. The signature port needs a bounded availability failure that remains a safe retryable 503 through native verification. A genuine `false` signature decision remains a rejection. Test outage at the dispatch gate, preserved HELD/same operation, and success after restoration. | Resolved; typed availability propagation and three passing dispatch regressions inspected |

The integration owner's first focused run also found a false rejection in ACP full-tree comparison: a parsed integer node differed from a constructed long node with the same value. The author normalized the trusted expected tree only, preserving original signed bytes. This was not acceptance of an unauthorized artifact; the subsequent positive/negative focused validation passed.

The reviewer read the DS-001/002 corrections and the real-database registration tests. A registered factory binds the transaction's immutable public enrollment snapshot; the core checks current revocation/time first and exact returned `TrustKey` equality afterward. The replacement cannot use its lookup result to enroll a key or relax current policy. Owner checks apply before native creation and again through the pre-dispatch capability gate. The DS-003 correction verifies original bytes and complete canonical artifact reference, then uses a strict UTF-8 decoder; backend-specific object identities remain behind the artifact adapter.

## Validation and acceptance

The reviewer inspected tests for native restart and historical approval replay; duplicate approval/budget races; half-open mandate expiry; separate authentication; invalid issuer/signature rollback; SQL failure after replay insertion; cross-run same-issuer/JWT-ID replay; retained artifact corruption/unavailability; trusted-key revocation commit ordering; old-profile bypass refusal; and an abrupt child-JVM exit after native admission/journal commit followed by original-operation recovery. Contract tests cover independently signed semantic negatives, exact checkout signatures, role trust, substituted signature providers, unsupported algorithms, checkout delegation, immutable store behavior and signer-error redaction.

The integration owner alone runs Maven in this session; no independent Maven invocation is claimed by the reviewer. The owner reports 78 distinct relevant Java methods passing across focused runs: 41 new admission/composition/migration/availability methods and 37 existing AP2 methods. The final availability run passed all 48 selected methods (three availability, eight native composition and 37 AP2). HTTP integration passed 11 methods. Independent Java-generated ACP schema verification and cross-language AP2 signature/reference checks passed. These focused results support source acceptance but do not substitute for the full unchanged-source aggregate publication gate.

The reviewer independently ran contract validation after DS-004: 21 schemas, 18 positive fixtures, 27 schema-negative fixtures, 6 binding-negative fixtures and 1 parser-negative fixture passed, retaining the 402-byte legacy golden checkout. The reviewer inspected Surefire XML from the owner's focused run: 38 methods passed with zero failures/errors (23 durable admission, 8 native composition, 3 immutable artifact store, 3 registered resolver/owner tests and 1 migration upgrade). The reviewer also inspected all three final availability-test results with zero failures/errors. The typed outage retains HELD exposure and the existing operation, records no financial send, and succeeds after restoration; a definitive invalid signature still rejects.

The reviewer independently inspected aggregate report `ac40da97d91241c098685d41030096a7`: all 12 stages passed, evidence validity is VALID, and source SHA-256 `95fa8d90dc5853d77f55ed76889f8defa30333fec6df19ebd25055a4f060688b` remained unchanged. It contains 309 passing methods (185 Java, 50 evidence tooling, 11 HTTP, 3 UI, 24 workflow, 30 offline artifacts and 6 upstream integrity), with zero failure/error/skip counts. This validates the admission manifest below. Runtime source findings require a later corrected-source aggregate; this earlier run cannot validate those changes.

The migration test creates an isolated real PostgreSQL schema, applies actual V001 through V006 resources with independently computed checksums, seeds a historical intent/attempt/HELD reservation/operation/outbox, and applies the migrator twice. It proves only the legacy profile was added to the old intent, preserves old rows and six prior checksums, records V007 once and creates no native authority. Cleanup targets only the test's exact validated random schema.

## Limits

This slice remains synthetic direct TAP authority, a local pinned ACP fixture and simulator financial execution. It implements no external merchant transport, live provider, real user consent, autonomous delegation or new native receipt transport. The replaceable artifact store is not a distributed financial transaction. Public trust enrollment and local identity are fixtures, not production key-management or tenant-authentication services. External replacement adapters require their own behavioral evidence and must satisfy the declared profile and admission guarantees.

## Reviewed source identity

The following 30 files identify the reviewed admission slice. Moving runtime/toolchain/evidence sources are covered separately by review 0021. Documentation outside the frozen ADR is explanatory and excluded from this implementation identity. Manifest SHA-256 is `923819cf2d6f356873b21d1dd61d221146f94691ffbaab5c6557ca91872bfa69`, computed over sorted UTF-8 lines `SHA256`, two spaces, repository-relative path and LF.

| File | SHA-256 |
| --- | --- |
| `integrations/verify_native_checkout.py` | `97a9073b8d7657639f848b9c76d5c4f6415c3f98a0e81330a5621f5dad61e56d` |
| `pom.xml` | `d7215ce68ad53cd5103435eb51f8c38a7b03cd85e90fa5a02230a3f87da97b5a` |
| `scripts/test_http.py` | `a71ceba205d9626f6c063689c84e6f2738c5f9245104471d64a24922f44ad460` |
| `specification/0.1.0-draft/fixtures/manifest.json` | `3211f53a096195b5c91d91f8e63b02fc19dd0c8fd82a980ef4174ca528dedd30` |
| `specification/0.1.0-draft/fixtures/valid/authority-replay-error.json` | `c352e3cc1feae860564aa5d90132ebaf75fa7793495ffefbb359fbb040f5ed12` |
| `specification/0.1.0-draft/schemas/contracts.schema.json` | `3f14377ba67a36de671e8288dae882e8da76c8d8db1ba8738a67fde8f826e2b2` |
| `src/main/java/io/agenticpayments/ap2/Ap2Crypto.java` | `266797a7dc5bd7317fbe93ee07218d777399ee97caccabba5bbbfd84c6c90bb1` |
| `src/main/java/io/agenticpayments/ap2/Ap2Engine.java` | `8ff6db3b40fc196ed4fc01b3ebc339b2f285f46a6e265df6ad68736a482de8bf` |
| `src/main/java/io/agenticpayments/application/PaymentService.java` | `5e63004402cb330a482349eae192688bd05f0751dfd4a1670f988ecefa774676` |
| `src/main/java/io/agenticpayments/application/PaymentStore.java` | `a727454fca7ebd1b9d72cb6157d06f5cd77e3f458167d720c5ba57132d4cc702` |
| `src/main/java/io/agenticpayments/composition/ArtifactIntegrity.java` | `628dc116741de12b2ca2a0f0af7b6563c7e01f70f20c406400c9efd743460ce1` |
| `src/main/java/io/agenticpayments/composition/Composition.java` | `a782dc9388ccb8e0675fc0ff3a264ec436ee3914d060dc1f03d64e90748cd8e0` |
| `src/main/java/io/agenticpayments/composition/EnrolledKeyResolverFactory.java` | `b2b04e85d7779fd541a317349f84e84bd57e42b28e7ba640842ff520fb5dc13a` |
| `src/main/java/io/agenticpayments/composition/FixtureKeyProvisioner.java` | `065d10b3c7e154b66838ce8d18dddc395b95cac3f2dfa127f5933746fb2c182d` |
| `src/main/java/io/agenticpayments/composition/InMemoryArtifactStore.java` | `549ddab2b84494a282a31a76f1f5bcb28b7323cf01b433d39f3020cc53bed9c4` |
| `src/main/java/io/agenticpayments/composition/NativeComposition.java` | `2f4888efced8c40489ed7f67f08fdad83c9eb42abb8ff214aa87d9b8a4244a27` |
| `src/main/java/io/agenticpayments/crypto/JcaSignatureProvider.java` | `93085c13ad9dab28442d326c19a8cc679ec60c1cfd037e6c9ee4a08b2e10c5e7` |
| `src/main/java/io/agenticpayments/crypto/SignatureProvider.java` | `a48d399b62f853ebfabbdf46f3583a0ca355dbf41ff91f77fa799acdeb57328a` |
| `src/main/java/io/agenticpayments/infrastructure/DatabaseMigrator.java` | `7b1d61b4cb5bf5831f1e8ce74c177ebb1e8bf34ab15a95717a0da2acccd89cb4` |
| `src/main/java/io/agenticpayments/infrastructure/JdbcPaymentStore.java` | `4dec62387a5f097f27edaa8eb9335855f63f86a3fe680b10c67faeb4b822e3ee` |
| `src/main/java/io/agenticpayments/infrastructure/JdbcSignedArtifactStore.java` | `f16e5ac709f5124c382de0550eac6735951a61cf4a054474012d11446f92aa15` |
| `src/main/java/io/agenticpayments/infrastructure/PaymentServices.java` | `9e0b4252d6ec9fed1d32c66fa5414d6515f5f13c4cf9fbdf53def17b073c905f` |
| `src/main/resources/db/V007__durable_ap2_admission.sql` | `96373df4073ee8a78d9c974eda1f018500662f7f48254537abfd53ff6b2363b9` |
| `src/test/java/io/agenticpayments/composition/ArtifactStoreContractTest.java` | `73b8d915029215e6b96bc1ef1fbeb6c2f3a28272921b6ceb4e146c6300e129ab` |
| `src/test/java/io/agenticpayments/composition/NativeCompositionTest.java` | `ed18a7585244142016befd124b33ff7b04626a0edeb352f6956cdbd7c7c05724` |
| `src/test/java/io/agenticpayments/core/CompositionRegistrationIntegrationTest.java` | `f4be1e63625c0ee3c8cab558305ce745af5a0b6ed25468fb91af8eca47a94ebd` |
| `src/test/java/io/agenticpayments/core/CryptoAvailabilityIntegrationTest.java` | `640ed5cb4556c8127e6c73b1e8d850a745de2293eef092c8fd297ebaad4950d6` |
| `src/test/java/io/agenticpayments/core/DurableAp2AdmissionTest.java` | `6343cb3b7d7366ad25528dc7d296d612e31e83d9a045c042b63b5cc6bb41cc91` |
| `src/test/java/io/agenticpayments/core/DurableAp2FaultDataSource.java` | `3984cbeb66b76d14bbd1d650505a39dc0834b7ea5360aedad4199da626037a8d` |
| `src/test/java/io/agenticpayments/core/NativeMigrationUpgradeTest.java` | `b96e214b3dc9d01275b876b85ecbfe9d051652e9f5f6d54d234c2cbd03f62a0c` |
