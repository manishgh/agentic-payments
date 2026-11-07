# Review 0017: Independent AP2 direct protocol source

Status: independent source review completed and updated after integration testing, 2026-11-07. Recommend bounded source acceptance at the exact manifest below; all six source findings are resolved. Publication remains conditional on a fresh unchanged-source database aggregate that also completes evidence evaluation and publication. The integration owner successfully reran the existing Windows path-escape regression with real junction support; no test was skipped or weakened.

Reviewer: independent protocol/security architect. The reviewer authored this record and review probes only, with no runtime, fixture-generator or application-test implementation edits. Source authors separately own Java claims, Java cryptography/role processing, adversarial Java tests and Python cross-language vectors. The accepted design is [ADR 0005](../adr/0005-independent-ap2-engine.md), reviewed in [0016](0016-independent-ap2-design-review.md), against baseline `95b5ac8276601f41c5f9d8e0380eb57c9892e9c7`.

## Scope and security assessment

The reviewed implementation targets only `ap2-v0.2-direct-tap-synthetic-0.1`. It uses independently authored Java framing/claims/role logic with JDK 21 JCA primitives and existing Jackson; the inspected source has no Google SDK imports, generated upstream models or implementation dependency. Published AP2 schema bytes are locally verified test references, not executable protocol authority.

The direct Trusted Agent Provider envelope is a zero-disclosure SD-JWT VC, with a mandatory terminal tilde, `dc+sd-jwt`, project credential type and one complete native closed mandate. Bare JWT substitution and unsupported disclosure/key-binding/delegation forms fail. Original encoded JWS components are verified directly. Checkout identity hashes its exact compact JWS string; receipt references hash the exact received SD-JWT including the tilde.

The key registry pins purpose, issuer, key identifier, P-256 key material, validity and revocation. Authority contexts supply the expected issuer/audience/nonce/time separately from the token. CP and MPP have distinct payment-mandate trust purposes. Strict claims reject unsupported fields, explicit nulls, wrong native versions, coercible/fractional/overflow money and malformed digest encodings. Public protocol records redact their string representations; mutable JSON trees are not retained as authority.

Challenge registration binds role/nonce to immutable issuer, audience, checkout and expiry. Atomic consumption checks both the registered challenge and the role/issuer/JWT identity. A different valid ECDSA signature representation does not create new authority. CP's synthetic credential binding has a private constructor and an engine-owner marker, and MPP independently re-verifies the mandate and complete scope before consuming its separate challenge. All replay state is bounded, process-local memory; rebuilding the engine does not preserve consumption and must never be treated as financial admission.

Receipt-only verification establishes signature, expected role/context, branch shape and reference; it deliberately does not authenticate the referenced mandate. Complete successful-bundle verification separately re-verifies the merchant checkout, both mandate roles and both receipt references. This distinction is essential: a signed rejection referencing invalid input does not convert that input into valid authority.

## Findings and disposition

| ID | Finding | Disposition |
| --- | --- | --- |
| AS-001 | The original Java output harness signed preselected Success receipts without executing the role decisions. That demonstrated artifact generation, not the accepted decision-to-receipt exchange. | `Ap2Exchange` now authorizes inside each role operation before signing Success; failures produce correctly scoped signed Error receipts. Signing identity, key pair and both output branches are checked before consumption. A post-consumption signing failure preserves admission and returns `receipt_unavailable`, never a fabricated rejection. The Outcome constructor is private. Eight exchange tests exercise these paths. |
| AS-002 | Per-artifact receipt checks alone did not satisfy the complete bundle obligation. | Added read-only `verifyBundle` independently verifies checkout, CP and MPP mandate purposes plus both matching Success receipts. Tests reject a re-signed mixed mandate and exchanged receipt types. |
| AS-003 | Initial CP and MPP verification used one payment-mandate trust purpose despite the reviewed separate-role permission contract. | Added `PROCESSOR_PAYMENT_MANDATE`; CP enrollment alone cannot establish MPP issuer trust. |
| AS-004 | Initial challenge keys included audience, permitting the same role/nonce to be re-registered under another audience contrary to the immutable registration contract. | Registration keys now use role/nonce; audience is an immutable challenge value checked at consumption. Tests cover audience, issuer, checkout and deadline rebinding rejection. |
| AS-005 | The independent Python output verifier accepted freshly re-signed artifacts with wrong recipient/challenge/JWT identity and checkout amount inconsistent with the payment. | Reproduced independently in memory without source or fixture mutation. The verifier now checks exact fixture checkout facts, key identifiers, mandate/receipt metadata, closed shapes and bounded times. The identical probe now rejects; valid output still passes. Four independent Python regression tests cover correctly signed semantic mutations. |
| AS-006 | The first aggregate completed all twelve child stages but failed evidence evaluation: both receipt/report schemas still limited `buildArtifacts` to two entries despite adding the third `ap2-engine` identifier. The initial source review checked the enum expansion but missed this cardinality bound, and existing fixtures carried only two artifacts. | Both schemas now allow at most three entries, and the input lock pins their corrected bytes. A new complete-bundle regression preserves all three identifiers/hashes through evaluation, serialization and bundle validation, retains `NOT_CERTIFIED`, and rejects a fourth artifact or unknown identifier against both schemas. The failed aggregate is not accepted; a fresh complete aggregate is required. |

## Evidence and review method

The reviewer read the Java runtime, claims and adversarial test sources; independently inspected the Python generator/verifier and its primary schema/reference loading; and traced trust, byte identity, replay and synthetic credential boundaries. No Maven invocation or financial operation was performed by the reviewer, avoiding interference with the integration owner's build.

The integration review also covers the AP2-only Maven classifier, CI hydration flag, exact nine-schema dependency helper, conformance runner and source identity expansion. Each Java stage generates current public vectors under its private run directory, performs Maven clean/build/tests using that path, and independently verifies Java output only after Maven succeeds. A failed prerequisite or verifier fails the stage, so successful individual tests cannot qualify observations from a failed stage. Both receipt/report schemas permit the additional `ap2-engine` build artifact, the runner records its hash, and the changed schema/POM hashes match the refreshed input lock. The existing selected CT inventory remains unchanged: new AP2 tests contribute aggregate counts without silently closing CT-007 or granting certification.

Independent executed checks:

- The concrete AS-005 in-memory semantic substitution probe reproduced acceptance before the fix and rejection after it. All five signatures and references were recomputed with transient synthetic keys, so rejection could not be attributed to merely damaged signatures.
- The unchanged valid Java output independently verified five signatures and two exact receipt references after the fix.
- `python -B -m unittest integrations.ap2_vectors.test_verifier` passed all four tests, including multiple fully re-signed semantic variants.
- All eight locally fetched RFC/versioned-draft/final-reference byte lengths and SHA-256 identities matched `standards-lock.json` when independently recomputed.
- The combined affected Python run executed 54 tests across the vector verifier, upstream integrity, conformance runner, collectors and requirement evaluator: 53 passed and one existing path-escape regression failed because this sandbox could create neither a Windows symlink nor its real-directory-junction fallback. Both new runner regressions passed: failed vector generation prevents Maven, and failed independent verification disqualifies successful Maven observations and blocks dependent stages. The one environment failure is retained as an integration prerequisite, not relabeled PASS.
- After AS-006, the reviewer independently ran the new three-artifact complete-bundle regression and the existing two-artifact valid-observations regression: both passed. The corrected schema bounds and refreshed input lock were independently checked. The owner demonstrated that the new regression failed with `SCHEMA_VALIDATION_FAILED` before the correction and passed afterward.

The owner subsequently ran 28 affected Python tests with the required Windows filesystem privilege; all passed, including the unchanged real-junction regression. This resolves the earlier environment prerequisite without changing the reviewer's original failed-run result. Separately, the owner's first full aggregate reached PASS for all twelve child stages but failed the final evidence evaluation at AS-006; it therefore supplied no accepted completed aggregate. Only a fresh run against the corrected manifest can satisfy the publication condition.

The integration owner executed the final focused Java suite. The reviewer independently read the resulting Surefire XML: 37 tests, zero failures/errors/skips, comprising nine claims, eight protocol, twelve security and eight exchange methods. The owner also verified the fresh Java output independently in Python. These are owner-executed Java results, not a claim that the reviewer ran Maven.

The Java adversarial source uses independent ephemeral JCA signing of raw JSON, exercises the alternate ECDSA `(r, n-s)` signature, concurrent authorization, failure-before-consume behavior, key-purpose isolation, exact time boundaries and engine-owned credential scope. The eight exchange methods verify actual decision-to-receipt paths, signed rejections, invalid framing, signer preflight and preservation of consumed authority after signer failure. Assertions comparing compact artifacts were changed to fixed-message boolean checks to avoid dumping payloads in regression diagnostics.

## Limits retained

This is a synthetic direct TAP protocol slice, not general SD-JWT VC, holder proof, delegated/autonomous AP2, real human consent, credential ownership, external peer interoperability or provider execution. No assertion that the core's financial admission accepts AP2 follows. Expected payment facts and current checkout bytes remain trusted integration inputs; ACP schema parsing, authoritative amount extraction, merchant sidecar agreement and durable atomic financial admission require the separately reviewed bridge.

The process-local fixture signer is not a production trusted surface or key-management service. Synthetic receipt IDs do not establish PSP/network processing. Complete bundle verification is current-policy cryptographic verification, not a long-term dispute adjudicator with historical trust/revocation policy. The local source review supplies no AP2/vendor/PCI/regulatory certification or release permission.

## Reviewed source identity

The updated 27-file manifest SHA-256 is `f62d789efce1d0636165d646b7c4a83c88bfd1bb6b45da606cadd31200647fb5`. It supersedes the initial 26-file identity after AS-006 and includes the added evaluator regression source. Recompute by hashing each listed file's bytes, sorting `(repository-relative path, lowercase SHA-256)` pairs by path, serializing the pair array using Python `json.dumps(pairs, separators=(",", ":"))`, UTF-8 encoding it, and SHA-256 hashing those bytes. No generated fixture, private key or local diagnostic is part of this source manifest. Three vector files received final CRLF-to-LF normalization; the reviewer verified that restoring their previous line endings exactly reproduces the previously inspected identities, with no semantic change.

| File | SHA-256 |
| --- | --- |
| `.github/workflows/reference.yml` | `16524e4e5fe5d60d392ca731d465c263ccd71d5ad133624a096758f46a57bebf` |
| `integrations/ap2_vectors/README.md` | `5970d29fbfce6ad9f62d269b1ede2fe34cfa8d99a82463d9e6be3e151f3a38f7` |
| `integrations/ap2_vectors/__init__.py` | `f68d0b882978127090844a13cb7f583142cd260dabd822b6eeeff7777cdf1c84` |
| `integrations/ap2_vectors/__main__.py` | `b9e726643720e6a13fae4959db11fe5a7046d070b36c24223fe742d02a9d69c6` |
| `integrations/ap2_vectors/required-source-artifacts.json` | `6511bc0731de758088dd48ea32a1bec303ad40793720107816a6e67d21aca733` |
| `integrations/ap2_vectors/standards-lock.json` | `4a950abf02834374d98bdc8d4ce86b6cd573ab81e5cf430cb15aac1ac38c2e7f` |
| `integrations/ap2_vectors/test_verifier.py` | `997890c765ac23cd93642d79a3a34b60a089b2979c72273a637b5c6f95d47f2a` |
| `integrations/upstream/probe_contracts.py` | `bdb171ec92d7af5d13749ecd8cd843a4c2fa02ef94667dfcde218da931e8e369` |
| `integrations/upstream/test_probes.py` | `74fc7716804226783ccacbcccd050207d526d296bf07b892139f264e266d821a` |
| `pom.xml` | `f3a876b6a7b9ef3ddca452b03c7d3c0be259a8db788a18dd5439d95d939f1581` |
| `scripts/conformance.py` | `7ae99c90ed97649e7d26670043a99dea52985aff71a830a83bb92f4257195e1e` |
| `scripts/requirement_evidence.py` | `aaa7b0c5efeacb0125916a1df9594dda2284c7d7573ba3c1643afb5028ff85e7` |
| `scripts/test_conformance.py` | `c329e9d96952c182d573f2560d3afc3a9cd266eb1fb73c1d67abd22776862e12` |
| `scripts/test_requirement_evidence.py` | `dec81f2186135fe9c87bf0c91294706d578f12d6b14ce6cba915d8eb5dbab3eb` |
| `specification/0.1.0-draft/evidence/input-lock.json` | `8233f3861146b8510e1820bdc42fd11ad6dff468d16b1a2049726522f8de6c07` |
| `specification/0.1.0-draft/evidence/receipt.schema.json` | `e03747732f47ba0451982e865dafe625ed21ac1f70013dca48130377aa76f2f5` |
| `specification/0.1.0-draft/evidence/report.schema.json` | `0a077348e5dd2fdb7ad0ed1e47b51be2a39dd67903ddec45e9edd7b54c25819f` |
| `src/main/java/io/agenticpayments/ap2/Ap2Claims.java` | `f59107839503c5d31e37397787a5ee49469c74530154749173c3347589f8e548` |
| `src/main/java/io/agenticpayments/ap2/Ap2Crypto.java` | `62a6340d3557727b685d237f1db53b10551ae18d9bb704977b773715ed66aab4` |
| `src/main/java/io/agenticpayments/ap2/Ap2Engine.java` | `1b3c8bbcc7ac4eba903beaa06165cfbc7f8f905e9bc2971529b79eecf1ac556c` |
| `src/main/java/io/agenticpayments/ap2/Ap2Exception.java` | `332cb800271d452df1f77d9aa8cb31b00ce91caf6944319882e7a335d66d1e82` |
| `src/main/java/io/agenticpayments/ap2/Ap2Exchange.java` | `b5e0699d3e1f2aa955b0ff37e89716fd1bd82b8dafc4dfb40a8fb85146c0c010` |
| `src/test/java/io/agenticpayments/ap2/Ap2ClaimsTest.java` | `cf6e3850c2c98f97a59d34a106db1f89114d8041acbb2d89971e399a5555dce2` |
| `src/test/java/io/agenticpayments/ap2/Ap2ExchangeTest.java` | `05265d36bfaf1080e46cc2b1207574e7be202d234f146aa4481acbd855bdaef1` |
| `src/test/java/io/agenticpayments/ap2/Ap2ProtocolTest.java` | `cc534b742873898005fa68aed8fd68c960001f1dab14fb099c96bfbdd8f087db` |
| `src/test/java/io/agenticpayments/ap2/Ap2SecurityTest.java` | `671bab89e627da1def5df9c2530d3e19fb83cfcbb89791c28b18be39fb8c9f8c` |
| `src/test/java/io/agenticpayments/ap2/Ap2VectorHarness.java` | `99f96da83dbc23e41720215e0817a303c694995af05aed51d912cd42179e8bfc` |

Documentation validation passed for 56 Markdown files, and `git diff --check` passed. The reviewer independently checked the complete table against current file bytes and validated the refreshed evidence input lock. The integration owner's later aggregate evidence and source acceptance belong in the gate and implementation-evidence records; this review does not claim that the final aggregate has already run.
