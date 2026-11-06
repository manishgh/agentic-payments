# Review 0011: Offline merchant artifact laboratory

Status: independent source review completed, 2026-11-06. Recommend acceptance of the bounded offline laboratory only; integration-owner acceptance is recorded separately.
Reviewer: protocol/security architect, distinct from the implementation author. Review [0009](0009-upstream-bridge-review.md) proposed the lab contract; this review checks another author's implementation against that accepted contract, not an independent approval of the reviewer's original proposal.

## Scope and verdict

Reviewed every Python module, fixture, dependency lock and test under [integrations/offline_lab](../../integrations/offline_lab), plus [implementation documentation](../offline-artifact-lab.md), the unchanged source-lock relationship and relevant pinned ACP/AP2 schema definitions.
No blocking defect was found within the explicitly trusted, synthetic, in-process lab boundary. The implementation performs real merchant JWS signature verification and exact artifact-byte binding. It does not verify native AP2 mandate authority or execute a payment.

The reviewer independently ran `.venv/Scripts/python.exe -m unittest discover -s integrations/offline_lab/tests`: **26 tests passed**, including the two subprocess demo runs and concurrent sidecar test. The observed runtime was cryptography 50.0.2 with OpenSSL 4.0.3. No Java build was run during this review.

## Verified boundaries

| Boundary | Source and evidence reviewed |
| --- | --- |
| Exact signed bytes | `jws.py` verifies original protected-header and payload segments, requires decoded payload equality to the frozen bytes, and computes checkout hash over the entire exact ASCII compact string. Canonical unpadded base64url rejects alternate unused bits, whitespace, extra segments, detached payload and double encoding. Tests distinguish payload hash from artifact hash and require new binding after re-signing/reordering. |
| Dedicated merchant trust | Exactly alg/typ/kid are admitted; ES256/P-256 and the lab-specific type are mandatory. Static trust selection checks merchant, origin, role, key validity and revocation. There is no token-directed key retrieval. Wrong key/curve/type/role/origin and damaged signatures are exercised. |
| Maintained cryptography | Project code handles fixed-width JWA R/S representation using library DSS encoding helpers; it implements neither elliptic-curve arithmetic nor ASN.1. The published RFC 7515 Appendix A.3 public vector checks verification independently of the fixture signer. The full merchant profile correctly refuses that generic vector because it lacks this profile's context/header. |
| Randomized fixture signatures | The dedicated signer explicitly passes deterministic_signing=False. Tests observe that parameter and verify two distinct signatures/hashes for the same bytes. The verifier makes no inference about an external signer's nonce generation. |
| Frozen checkout and schemas | Strict UTF-8, duplicate-member, numeric and size checks precede unchanged pinned schema validation. Source bytes must match length, SHA-256 and Git blob identity. Resolution is local-only, including the explicit receipt-status alias. The closed synthetic handler/config/instrument tuple cannot turn an arbitrary checkout URL into a fetch. |
| Content versus authority | The binder invokes signature verification itself, validates closed mandate content shapes and exact compact/hash/transaction/payee/money/instrument/session/revision/expiry relationships, then returns CONTENT_BOUND_AUTHORITY_UNVERIFIED. It does not accept an externally asserted signature-valid boolean or manufacture AP2 approval. |
| Durable single owner | SQLite BEGIN IMMEDIATE serializes the isolated sidecar's binding/completion transitions. Principal-scoped command keys bind request content; the operation table allows one operation per tenant/run/merchant/session. New keys, restart and timeout preserve that operation; changing checkout invalidates earlier binding and cannot replace an outstanding operation. Sixteen concurrent calls produce one operation in the test. |
| Authentication and financial uncertainty | Required intervention cannot silently become ready. Authentication-required content cannot bind. Completed/canceled/expired commerce states never establish financial success/no effect; financial_state remains UNKNOWN after admission. No dispatcher, provider client or Java payment facade is imported or exposed. |
| Reporting and privacy | Safe-report construction checks artifact/binding/operation relationships and refuses stronger authority/financial claims. It emits predefined fields, opaque identifiers and fixed claims; it does not serialize raw artifacts or exceptions. Tests examine report/repr/database contents, CLI output and storage-error redaction. |

The cryptography API's documented deterministic-signing option affects signing only; verification is identical for deterministic and randomized ECDSA. Its documented verification and DSS conversion APIs match the implementation. [Cryptography 50.0.2 EC documentation](https://cryptography.io/en/50.0.2/hazmat/primitives/asymmetric/ec/).

## Explicit limits and follow-on gates

The lab's dataclasses are ordinary Python values, not tamper-proof remote attestations. Their constructors, trust map, injected clock and LabScope are available to trusted test code. A future service must obtain these values through authenticated boundaries and the verifier/binder, never deserialize a caller's VerifiedCheckoutArtifact or ContentBinding as proof. This is consistent with the present absence of an external server or dispatch port.

`observe` consumes a trusted synthetic commerce-state assertion. The test separately validates an order-bearing ACP completion response, but the ledger does not implement authenticated merchant responses, result callbacks or provider reconciliation. Reported commerce state is a lab projection, not verified peer evidence. Its financial result remains UNKNOWN, including after completed commerce.

SQLite here holds only the isolated lab's safe sidecar metadata; it does not replace the Java/PostgreSQL financial ledger or establish production recovery evidence. Tests cover same-key concurrent replay and retained operation identity, not arbitrary multiprocess crashes, attacker-controlled databases, production signing-key storage or distributed merchant trust.

Schema validation and content binding do not close native AP2 issuer/holder/delegation/SD-JWT/receipt verification. The exact compact-string representation is a local candidate choice; real peers must agree to it. The source conflicts and profile gates in review 0009 remain open. No real handler/provider combination is enabled, and the disabled real-bridge profile is unchanged.

The pinned wheel/provenance records and the public-vector test are reviewed evidence of the selected implementation. This review is not a cryptographic-library audit, deployment certification or complete dependency vulnerability attestation. Installation/hydration are separate setup operations; runtime verification is offline.

## Reviewed identities

Hashes below are SHA-256 of the reviewed working-tree bytes. The directory manifest sorts forward-slash relative paths ordinally, excludes generated __pycache__, concatenates `path`, TAB, lowercase file hash and LF for each entry, then hashes UTF-8 bytes without a BOM.

| Artifact | SHA-256 |
| --- | --- |
| `integrations/offline_lab`, 20-file manifest | `56a3871c81d4825e534803069cc10f89938403c2b514931148cdbe79fc2d3c01` |
| `jws.py` | `05709108fa22426002cf9148d4ee11b5bafca630259f1e1c729434952d52bdfe` |
| `binding.py` | `7d6b234786b133e41c943146a736c1c4ab7806c44f5faa19dda033f461cf4e08` |
| `contracts.py` | `db08ac88908443a1632ca9cebd7103ae07fde7076f38ead68e21aacae2e01a18` |
| `ledger.py` | `c72681d567deaff4687e042a56482c77cd1574b5bca37e061ce1f915bc218b0c` |
| `reporting.py` | `56f1e83175bf599f3033a69df3563cf8d62409129213397c1377b2c3e343893d` |
| `tests/test_lab.py` | `c3f939c65b947c1039f7766c464f3b8893931184f0fa7b4ef09764fc6a0438cd` |
| `requirements-crypto.lock.txt` | `16b2e11a0b9b963c7238f3199bf9d2fc570954b245740c60ad1634738dabf292` |
| `dependency-provenance.json` | `9d2e31c7ac0a98c4ca8a016cf96552230bc1be1dc31dd3e5b36c19fe756b3265` |
| `docs/offline-artifact-lab.md` | `ffcda2766a600fae28d99aa0513f1f2bf6cec621617a3fd986e0f138d8fbc7bd` |

Only this review document was authored for the lab review. No implementation, fixture, pin or upstream schema was modified by the reviewer.
