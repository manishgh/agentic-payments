# Offline merchant checkout artifact lab

`offline-ap2-acp-artifact-lab-0.1` verifies synthetic merchant checkout JWS signatures, frozen ACP bytes, closed mandate content matching and durable sidecar identities. It is separate from the Java payment core and from the disabled real-bridge candidate. It cannot dispatch a payment, verify native AP2 user/delegation authority, complete 3DS, or establish provider interoperability.

The [source lock](../integrations/upstream/source-lock.json) pins upstream artifacts; the lab does not enable an external financial bridge.

## Run and verify

Use the repository Python 3.12 environment with existing `jsonschema==4.26.0`. The [crypto lock](../integrations/offline_lab/requirements-crypto.lock.txt) pins binary distributions for `cryptography==50.0.2`, `cffi==2.1.1` and `pycparser==3.12`, including their official PyPI SHA-256 values. Metadata, inspected license-record hashes and RFC-source hashes are in [dependency provenance](../integrations/offline_lab/dependency-provenance.json). The inspected licenses are Apache-2.0 OR BSD-3-Clause, MIT-0 and BSD-3-Clause respectively; this records dependency terms without choosing a project license.

```powershell
# Install reviewed binary additions; --require-hashes checks the selected wheel.
./.venv/Scripts/python.exe -m pip install --require-hashes -r integrations/offline_lab/requirements-crypto.lock.txt

# Explicit setup only: hydrate reviewed immutable schemas for source probes and lab.
./.venv/Scripts/python.exe integrations/upstream/probe_contracts.py --fetch --include-offline-lab

# Run all artifact, binding, replay, concurrency and privacy regressions.
./.venv/Scripts/python.exe -m unittest discover -s integrations/offline_lab/tests

# Run a synthetic exchange; only safe assertions and opaque IDs reach stdout.
./.venv/Scripts/python.exe -m integrations.offline_lab
```

The resolver needs the nine exact cached schemas identified by [required-source-artifacts.json](../integrations/offline_lab/required-source-artifacts.json). The explicit upstream setup command above verifies that manifest against the reviewed source lock and populates `.tools/upstream-research/<sourceId>/<upstream-path>`; its union with the separate source probes is 11 schemas. Existing source-research setup already contains them. Missing or modified bytes fail closed; the lab itself has no network hydration or schema-fetch operation. Dependency installation/source preparation are separate setup actions, not artifact-verification behavior.

The demo uses an explicit injected clock at `2026-11-06T10:00:00Z` and a synthetic checkout valid for 30 minutes. It creates in-memory signing keys, chooses a distinct test run and retains only safe sidecar metadata in ignored `.tools/offline-artifact-lab/demo.sqlite`. Supply `--database <path>` for another local test database. Each run is isolated; randomized signatures and operation IDs intentionally differ. Private keys, compact tokens, mandate contents, instrument credentials and browser authentication material are absent from reports and the sidecar database.

## Implemented contract

| Component | Enforced boundary |
| --- | --- |
| PinnedSchemaRegistry | Verifies source length, SHA-256 and Git blob identity before parsing. Only the nine locked schema IDs, local pointers and explicitly reported receipt-status file-location alias resolve. Remote retrieval is refused. |
| FrozenAcpCheckout | Strict UTF-8, duplicate-key rejection, object/size limits and integer-only artifact JSON; exact ACP `2026-04-17` session schema. Preserves original bytes and immutable revision. Requires positive full EUR/USD totals, matching synthetic line-item allocation, bounded UTC expiry, one allowlisted handler and no FX, marketplace payees or unsupported extensions. |
| OfflineHandlerRegistry | Matches the entire synthetic handler descriptor, PSP, version, config and schema identities against hash-pinned local fixtures. Validates its configuration/instrument using closed local schemas. URLs carried in a checkout are never fetched. |
| MerchantCheckoutJwsVerifier | Requires exactly three canonical unpadded base64url ASCII segments and the dedicated `offline-merchant-checkout+jwt` type. Resolves `kid` only through an explicit merchant-role/key/origin map, checks trust validity/revocation and checkout expiry, and verifies ES256/P-256 over the original protected-header/payload segments. |
| MandateContentBinder | Independently invokes JWS verification, validates the unchanged closed-mandate content schemas and profile-required expiry, then matches both checkout hashes, merchant/payee, integer money, instrument, session and revision. Its only result is `CONTENT_BOUND_AUTHORITY_UNVERIFIED`. |
| SyntheticSidecarLedger | SQLite transactions persist tenant/run/merchant/session scope, principal-scoped command replay digests, content binding and a sole completion owner/operation. Changed checkout revisions invalidate earlier bindings. After an operation exists, a new checkout cannot create its replacement. |
| State projection/report | Authentication remains blocked. Completed/canceled/expired checkout states never establish financial success or no effect. A timeout preserves the original operation as UNKNOWN; reports verify artifact/binding/operation consistency before exposing safe assertions. |

The handler and instrument are explicitly synthetic. The fixture instrument's token is a non-usable sentinel; it is never submitted to a provider, persisted by the sidecar, or emitted in a report. Full checkout bytes and unsigned mandate content exist only in the lab's trusted in-process test boundary. No external authentication or payment service is exposed.

## Exact signature and hash domain

For this profile only, `checkout_jwt` is the raw ASCII compact JWS string, without an outer base64url encoding. The checkout hash is unpadded base64url SHA-256 of those exact ASCII bytes. The decoded payload must equal the frozen ACP byte sequence; JSON reordering, whitespace changes and re-signing produce a different binding. The lab rejects padded/noncanonical encodings, whitespace, extra/missing segments, detached payloads and double encoding.

The protected header admits exactly `alg`, `typ` and `kid`. It rejects none/HMAC/other algorithms, token-supplied `jwk`, `jku`, `x5u`, `crit`, `b64` and other fields. Explicit merchant role, key, curve, type, origin and expected bytes prevent a receipt/mandate token or another merchant key from substituting for a checkout signature. Key enrollment/trust is a synthetic fixture assumption here, not real AP2 enrollment.

ES256 verification and fixed-width signature conversion use the maintained cryptography library's EC and DSS encoding APIs. No elliptic-curve arithmetic or ASN.1 encoder is implemented in project code. The dedicated fixture signer explicitly requests `deterministic_signing=False` and generates private keys only in memory. Tests verify two signatures over the same checkout and their different hashes. They do not infer an arbitrary external signer's nonce policy from its signature.

The independent [public RFC 7515 Appendix A.3 vector](../integrations/offline_lab/fixtures/rfc7515-a3-public-vector.json) validates the ES256 primitive/signature representation separately from generated merchant fixtures. It contains only a published public key and compact signature, with source SHA-256 provenance. Its generic header lacks this lab's required type/key identifier, so it is deliberately refused by the complete merchant profile. JWS/JWA/JWT references are pinned separately in lab provenance; this does not select the unresolved native SD-JWT/delegation profile.

## Evidence and limits

The 26 tests cover valid signatures and an independent vector; changed header/payload/signature; algorithm, type, kid, key, curve, role, origin, trust expiry/revocation and serialization refusals; duplicate/malformed/oversized JSON; schema/source integrity; handler/money/expiry/authentication refusal; altered content bindings; randomized re-signing; durable restart, scoped replay and changed-input conflicts; 16 concurrent replays producing one operation; changed checkout invalidation; unknown timeout; mixed-evidence report refusal; storage error redaction; and safe report/database privacy checks. The runnable demo reports merchant signature validity and content binding while keeping native AP2 authority, provider interoperability and dispatch availability false.

This implements neither issuer/user/holder mandate signatures nor SD-JWT disclosures, proof of possession, delegation constraints, receipt-reference verification, production revocation discovery or real customer authentication. It does not patch the known upstream authentication/completion/delegated-payment contradictions. The AP2 compact-string choice still requires agreement with a real peer. Synthetic sidecar scope is provided by trusted test code; deployment authentication, transaction authority, ledger accounting and provider reconciliation remain outside this isolated lab.
