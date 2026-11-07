# Independently implemented AP2 direct engine

The Java package `io.agenticpayments.ap2` implements the bounded `ap2-v0.2-direct-tap-synthetic-0.1` profile against AP2 v0.2.0 commit `b4587ac1d055888a73b4b21750973cffba961793`. It is independently authored from pinned specifications. It does not import, wrap, translate or copy Google's SDK implementation. Standard JDK JCA primitives perform cryptography; Jackson handles JSON. [ADR 0005](adr/0005-independent-ap2-engine.md), [requirements](ap2-requirements.md) and [design review 0016](reviews/0016-independent-ap2-design-review.md) define its scope and accepted decisions.

## Implemented components

| Component | Responsibility |
| --- | --- |
| `Ap2Claims` | Immutable native closed Checkout/Payment Mandate contents, direct credential envelopes and typed receipts; strict parsing, exact versions and redacted string representations. |
| `Ap2Crypto` | Original-byte compact JWS verification, randomized ES256/P-256 signing through JCA, bounded JSON/UTF-8/base64url processing and exact SHA-256 references. No embedded/remote key trust. |
| `Ap2Engine` | Explicit issuer/key/purpose trust with validity/revocation, trusted expected checkout/payment facts, audience/nonce/lifetime validation, role-specific authorization and signed receipt verification. |
| Process-local replay guard | Trusted challenge registration followed by atomic nonce plus issuer/JWT-ID consumption per role. A different valid ECDSA signature or newly signed nonce cannot bypass an already consumed authorization identity. |
| `Ap2Exchange` | Actual synthetic Merchant, Credential Provider and processor decisions followed by purpose-bound signed receipts. It accepts no caller-created proof as authority to issue Success. |
| Full-bundle verifier | Independently verifies both mandates, both successful receipts, their signer roles and exact cross-artifact references. It is read-only and does not change financial state. |
| Independent test vectors | Python-generated valid signatures with authored negative expectations, Java verification, and independent Python verification of Java-generated exchange artifacts. |

The direct Trusted Agent Provider envelope is an SD-JWT VC containing one inline closed mandate, with no disclosures or holder-binding token: `issuerJWT~`. The outer type is the documented project profile; inner types are exactly `mandate.checkout.1` or `mandate.payment.1`. Generic plain-JWT mandates, open mandates, hidden digests, holder keys, unknown claims and unsupported chains fail explicitly. Receipts are plain signed JWTs with consistent generic `result` and AP2-specific `status` fields.

The Merchant checks the approved compact checkout against its trusted current checkout. CP and MPP independently verify the same payment mandate under separately permitted trust purposes within the explicitly configured shared payment audience. The synthetic CP binding is engine-local and non-negotiable; MPP verifies the native mandate and all expected scope again. There is no provider token or financial dispatch.

Merchant and MPP success receipts follow successful authorization, with explicit synthetic order/payment/PSP/network identifiers. CP rejection returns a CP-signed Payment Receipt Error; a CP key cannot produce an accepted Success receipt. Replays and invalid authority produce fixed signed errors when a bounded compact mandate can be identified. Framing-invalid input receives a safe rejection without a receipt. If signing fails after authorization succeeds, the result preserves `accepted=true`, reports `receipt_unavailable` and keeps the challenge consumed. It does not fabricate a rejection or silently reopen authorization.

## Run and package

The normal aggregate runner now prepares fresh public vectors outside `target`, passes their explicit path to Maven, and runs independent Python verification after Java succeeds. The final verifier's failure fails the Java stage and prevents qualification of its named observations. An old target output cannot satisfy a failed current build. Upstream setup adds the explicitly pinned AP2 schema dependency set:

```powershell
. ./scripts/Use-LocalToolchain.ps1
.venv/Scripts/python.exe integrations/upstream/probe_contracts.py --fetch --include-offline-lab --include-ap2-vectors
.venv/Scripts/python.exe -m integrations.ap2_vectors --generate .tools/ap2-focused-vectors
mvn.cmd -B -ntp '-Dtest=Ap2*Test' '-Dap2.vectors=.tools/ap2-focused-vectors/vectors.json' test
.venv/Scripts/python.exe -m integrations.ap2_vectors --verify-java target/ap2-vectors/java-output.json
```

The package phase additionally produces `reference-platform-0.1.0-SNAPSHOT-ap2-engine.jar`, containing only the independently authored AP2 package. It requires Java 21 and the pinned Jackson runtime. The aggregate receipt records its SHA-256 separately from the reference and adapter-SDK JARs. This classifier is a local build artifact; a separately reviewed publication POM and licensing decision remain prerequisites for publishing packages.

Generated public synthetic artifacts remain under ignored `.tools/` or `target/`; private keys are generated transiently and never serialized. Only safe counts, hashes and statuses are printed. [Vector documentation](../integrations/ap2_vectors/README.md) records the pinned standards, native schema checks and test limits.

## Composition and limits

The product still encloses embedded ACP checkout and authentication inside its platform PaymentIntent. This native AP2 engine supplies the protocol component for that design; the existing financial core still accepts its established synthetic execution profile. No runtime bridge silently substitutes this new protocol result for durable consent, replay or financial admission. LangGraph remains an orchestrator and receives no signing keys or raw authority artifacts.

The next integration slice must bind verified AP2 authority and the negotiated ACP checkout revision to a durable tenant/intent/attempt/operation transaction, with one execution owner and restart-safe replay protection. A process-local guard cannot supply that guarantee. Real Trusted Surface consent, issuer enrollment/discovery, User Credential/OpenID4VP, selective disclosures, holder-key binding, autonomous constraints, external AP2 peers, ACP merchant transport and real payment connectors remain separate work. Cross-language fixture success is not Google interoperability or full AP2 conformance.
