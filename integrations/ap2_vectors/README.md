# Independent native AP2 vectors

These self-authored fixtures exercise `ap2-v0.2-direct-tap-synthetic-0.1` against AP2 v0.2.0 commit `b4587ac1d055888a73b4b21750973cffba961793`. They use Python `cryptography` to sign native direct Trusted Agent Provider SD-JWT mandates, then Java verifies them. Python independently verifies Java-produced merchant checkout, mandate and receipt signatures. Neither direction uses Google's SDK or upstream implementation code.

Use the existing [crypto dependency lock](../offline_lab/requirements-crypto.lock.txt) and repository Python environment. Schema preparation is an explicit upstream setup action; fixture generation itself never fetches a schema, key or URL. The [required schema manifest](required-source-artifacts.json) records nine native AP2 schema artifacts already present in the unchanged upstream source lock. Every schema load verifies the reviewed source record, SHA-256, byte length and Git blob identity. Resolution uses only these local resources, including the explicit receipt-status file-location alias; remote resolution fails closed.

```powershell
# Prepare reviewed schemas using the repository upstream setup command.
./.venv/Scripts/python.exe integrations/upstream/probe_contracts.py --fetch --include-ap2-vectors

# Generate synthetic public artifacts and independently specified expected outcomes.
./.venv/Scripts/python.exe -m integrations.ap2_vectors --generate .tools/ap2-focused-vectors

# Run the Java protocol/exchange tests using an explicit vector path that survives Maven clean.
mvn.cmd -B -ntp '-Dtest=Ap2*Test' '-Dap2.vectors=.tools/ap2-focused-vectors/vectors.json' test
# After that test has run, verify the opposite signature direction independently.
./.venv/Scripts/python.exe -m integrations.ap2_vectors --verify-java target/ap2-vectors/java-output.json
```

`Ap2ProtocolTest` requires the explicit `ap2.vectors` Java system property, falling back to `target/ap2-vectors/vectors.json` for an intentionally prepared non-clean focused run. Absence fails the test. The aggregate runner always generates a fresh per-run vector path outside `target` before invoking Maven clean. The test-only `Ap2VectorHarness` emits public Java artifacts after actual role decisions. Generated output belongs under ignored `.tools/` or `target/`; private keys are generated only in memory and are never serialized. Randomized ES256 signatures change between generations; acceptance expectations and recomputable exact-byte hashes remain stable. Output prints only safe counts/profile assertions.

The native positive pair is `issuerJWT~` with no disclosures or holder binding, protected type `dc+sd-jwt`, a collision-resistant outer credential type and one inline closed mandate in `delegate_payload`. Published AP2 schemas independently validate the four positive content/receipt shapes. Cryptographically valid negative content covers trusted facts, exact checkout/receipt references, authority versions, issuer/audience/nonce/time, unsupported delegation and unknown fields. Additional negatives exercise malformed framing, duplicate JSON, UTF-8, algorithm/header confusion, key policy and receipt branch/role confusion. CP Error and MPP success/error are distinct trust purposes. Java role tests additionally exercise registered challenge scope, process-local concurrency and synthetic credential transfer.

The [standards lock](standards-lock.json) records eight fetched primary RFC/versioned-draft/final-reference byte identities. Raw references remain in ignored `.tools/ap2-standards`. RFC 9901 section 4 requires the final `~`; SD-JWT VC draft 15 section 3.2.2.4 permits zero disclosures and prohibits `_sd` in that case. Draft 15 is a pinned historical work in progress. Delegate draft 00 and OpenID4VP final were examined but their delegation and user-credential flows are not implemented.

This evidence establishes synthetic cross-language protocol behavior, not interoperability with Google or any external peer. It does not establish a human's consent, holder proof, autonomous authority, durable replay protection, real credential release, provider execution, ACP merchant agreement or financial core admission. See [requirements](../../docs/ap2-requirements.md) and [ADR 0005](../../docs/adr/0005-independent-ap2-engine.md) for the exact limits.
