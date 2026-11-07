# ADR 0005: Independently authored AP2 direct protocol engine

## Status

Proposed; independent security review and integration-owner acceptance precede implementation. This ADR defines an executable protocol slice, not full AP2 conformance or financial admission.

## Context

The platform embeds ACP checkout inside its enclosing PaymentIntent workflow. The existing offline artifact lab proves byte binding and schema facts but does not implement AP2 role verification. The next slice must exchange native signed Checkout and Payment Mandates, verify them independently in the Merchant, Credential Provider and Merchant Payment Processor roles, and return verifiable typed receipts. It must remain independently authored Java code, without Google's SDK, implementation code, generated models or runtime libraries.

The protocol source is AP2 `v0.2.0`, commit `b4587ac1d055888a73b4b21750973cffba961793`, already identified by [the source lock](../../integrations/upstream/source-lock.json). Its normative [specification](https://github.com/google-agentic-commerce/AP2/blob/b4587ac1d055888a73b4b21750973cffba961793/docs/ap2/specification.md), [authorization rules](https://github.com/google-agentic-commerce/AP2/blob/b4587ac1d055888a73b4b21750973cffba961793/docs/ap2/agent_authorization.md), [Checkout Mandate](https://github.com/google-agentic-commerce/AP2/blob/b4587ac1d055888a73b4b21750973cffba961793/docs/ap2/checkout_mandate.md) and [Payment Mandate](https://github.com/google-agentic-commerce/AP2/blob/b4587ac1d055888a73b4b21750973cffba961793/docs/ap2/payment_mandate.md) define the semantics. Schemas are specifications to implement, not code to reuse. Existing structural probes and their known contradictions remain unchanged.

## Decision and profile

Implement package `io.agenticpayments.ap2` with the exact profile identifier `ap2-v0.2-direct-tap-synthetic-0.1`. It supports human-present direct authorization through a locally configured Trusted Agent Provider (TAP) trust list. Synthetic fixtures simulate the deterministic Trusted Surface's consent; they do not establish real user authentication, enrollment or commercial acceptance.

Each mandate is one RFC 9901 SD-JWT with zero selective disclosures and no Key Binding JWT, serialized as `issuerJWT~`. The issuer JWT carries a `delegate_payload` array containing exactly one complete closed mandate. This chooses a strict terminal TAP envelope consistent with AP2's `delegate_payload` examples; it does not accept autonomous chains. The top-level credential `vct` is `https://agentic-payments.example/profiles/ap2-direct/1`, while the nested mandate uses the exact native AP2 type. The protected header `typ` is `dc+sd-jwt`, as required by the pinned SD-JWT VC draft.

Zero disclosures are intentionally sufficient for this first slice: the Checkout Mandate is sent only to the Merchant and the Payment Mandate only to the Credential Provider/MPP. No unrelated user credential is transported. Recursive `_sd`, `...`, `cnf`, `constraints`, `sd_hash` or other delegation material, extra `~` components, open mandate types, User Credential/OpenID4VP presentations and unknown fields fail closed. This profile must not be advertised as general SD-JWT VC, Delegate SD-JWT, VDC or autonomous AP2 support.

Normative crypto inputs are RFC 7515 (JWS), RFC 7518 section 3.4 (ES256), RFC 7519 (JWT), RFC 8725 (JWT security), RFC 9901 (SD-JWT, specifically serialization/hash processing), and AP2's exact pinned authorization text. Also pin draft-ietf-oauth-sd-jwt-vc-15 (credential envelope and `dc+sd-jwt` type), draft-ietf-oauth-delegate-sd-jwt-00 (examined single-item `delegate_payload` structure; delegation excluded), and [OpenID4VP 1.0 final](https://openid.net/specs/openid-4-verifiable-presentations-1_0-final.html) (examined, excluded). AP2's originally linked `openid4vp-1_0.html` endpoint was unavailable during review; that excluded path must not be silently treated as implemented. The implementation must not infer support from a mutable reference. Their chain and User Credential/VDC presentation requirements remain explicitly unsupported.

JDK 21 JCA implements ECDSA and SHA-256; existing Jackson 2.22.4 implements strict JSON parsing. No new dependency is required. JCA `SHA256withECDSAinP1363Format` maps directly to ES256's 64-byte `R || S` signature; no custom elliptic-curve arithmetic, hashing or ASN.1 conversion is introduced. The signing path uses JCA randomness. Merchant checkout signatures are nondeterministic ES256, as required by the pinned AP2 specification. Header-selected algorithms, remote key retrieval and embedded keys are prohibited.

## Exact wire and claim rules

All compact strings are ASCII, with strict unpadded base64url components. Decode then re-encode equality rejects noncanonical encodings. The JWS signing input is the original encoded header, a dot, and original encoded payload. Verification never serializes a parsed object to reconstruct signed bytes. Decode UTF-8 strictly; reject malformed UTF-8, duplicate members at any nesting, trailing JSON, non-object roots, excessive size/depth and unknown protected headers. The allowed header fields are exactly `alg`, `kid`, `typ`; `alg` is exactly `ES256`. The configured key must be P-256 and enabled for the requested artifact role.

Each mandate envelope requires exactly `iss`, `vct`, `iat`, `exp`, `aud`, `nonce`, `jti`, `_sd_alg`, `delegate_payload`. The algorithm claim is exactly `sha-256`; `aud` is a single exact string, not an array. The mandate content is the sole array element. Both envelope and content validity are checked. An arbitrary valid issuer signature is insufficient without configured issuer/key/role trust and deterministic expected transaction facts.

The profile requires epoch-second integers with `iat <= now < exp`, `iat < exp` and lifetime at most 300 seconds. Clock and expected audience/nonce are explicit verifier inputs; skew is zero. No wall-clock lookup, default audience or hidden tolerance changes results. String identities are nonempty bounded strings and are compared exactly, without case folding, URL normalization or Unicode normalization. Digests are exactly 32 bytes encoded as 43 unpadded base64url characters. Native schema optional fields outside this bounded slice fail closed rather than silently disappearing.

| Content | Required native fields and profile restrictions |
| --- | --- |
| Checkout Mandate | `vct=mandate.checkout.1`, `checkout_jwt`, `checkout_hash`. No optional content fields in this slice. |
| Payment Mandate | `vct=mandate.payment.1`, `transaction_id`, `payee`, `payment_amount`, `payment_instrument`. `payee` requires exact `id`, `name`, `website`; instrument requires exact `id`, `type`, optional `description`. No `pisp`, scheduled `execution_date` or `risk_data`. |
| Amount | Exactly `amount` and `currency`; positive signed 64-bit integer minor units, currency exactly `EUR` or `USD`. No decimal, float, string coercion or FX. |
| Checkout Receipt | Plain signed JWT; native `iss`, `iat`, `reference`, `status` plus generic `result`; Success requires only `order_id`; Error requires only `error`, `error_description`. |
| Payment Receipt | Plain signed JWT; native `iss`, `iat`, `reference`, `status`, `payment_id` plus generic `result`; Success requires both `psp_confirmation_id` and `network_confirmation_id`; Error requires only `error`, `error_description`. Synthetic success IDs are explicitly synthetic, never placeholders for absent real evidence. |

Receipts also require profile `aud`, `nonce`, `jti`, `exp` with the same validity rules. Checkout and payment receipt protected `typ` values are respectively `ap2-checkout-receipt+jwt` and `ap2-payment-receipt+jwt`. Both generic authorization `result: success/error` and type-specific AP2 `status: Success/Error` are required and must agree, an explicit profile resolution of BR-005. Success and Error fields are mutually exclusive; error descriptions come only from fixed safe text. Raw parser errors or artifacts are never copied into receipts or logs.

The merchant's signed checkout is a plain compact ES256 JWT with `typ=ap2-checkout+jwt`. Its contents remain the original checkout object. The offline exchange may embed a pinned ACP checkout object using AP2's Checkout Object extension, but cannot infer a cooperative ACP merchant or sidecar agreement. Expected merchant identity and current signed checkout bytes arrive through trusted verifier inputs. This engine verifies signature and exact bytes; ACP monetary extraction/schema validation stays a separate explicit composition boundary. It must never authorize arbitrary caller-provided checkout facts as trusted facts.

`checkout_hash` and Payment Mandate `transaction_id` equal `base64url(SHA-256(ASCII(exact checkout_jwt string value)))`. This hashes the compact JWT value once, without surrounding JSON quotes, an outer base64 encoding, a prefix, JSON canonicalization, or reserialization. Although one schema description says base64url-encoded serialized JWT, the pinned AP2 prose and examples use the compact serialized merchant JWT as this string. This profile fixes that representation explicitly.

Receipt `reference` equals `base64url(SHA-256(ASCII(exact received closed SD-JWT)))`, including its final `~` and excluding no components. Because this profile has no KB-JWT and exactly zero disclosures, that input is exactly `issuerJWT~`, matching RFC 9901 `sd_hash` input rules. A new valid ES256 signature changes checkout identity even when decoded JSON is unchanged. A forged or reserialized mandate cannot preserve its receipt reference.

## Deterministic role exchange

1. A synthetic Merchant signs its immutable checkout. The synthetic TAP fixture signs two independently addressed direct mandate SD-JWTs after simulated trusted approval, each bound to that checkout digest.
2. Merchant verification checks TAP trust for Checkout Mandate issuance, exact audience/nonce/lifetime, closed content, included checkout signature under an enrolled Merchant key, and equality to the merchant's current frozen compact checkout. Rejection returns a signed Checkout Receipt Error; acceptance returns a signed Checkout Receipt Success with synthetic order identity.
3. Credential Provider verification checks TAP trust for Payment Mandate issuance, exact audience/nonce/lifetime and complete closed content, then matches transaction digest, payee, exact minor-unit amount/currency and instrument against trusted expected authorization inputs. Success produces a synthetic transaction-scoped credential binding containing the exact Payment Mandate reference and checkout digest. The binding is an in-process synthetic protocol value, not a provider token or negotiable payment credential. On rejection, CP returns a signed Payment Receipt Error under its separately configured CP receipt-signing key, as AP2 requires. Its mandatory `payment_id` is a synthetic decision identifier and does not assert a real financial operation.
4. MPP verification independently verifies the Payment Mandate signature/trust/claims and checks the credential's complete scope against the same current checkout and expected payee, money and instrument. A synthetic successful processing decision produces a signed Payment Receipt with synthetic PSP and network confirmations. A rejection produces a signed Payment Receipt Error.
5. Receipt verification is a distinct read-only role check: enforce receipt-specific signer role/key, expected issuer/audience/nonce, exact mandate reference and correct Success/Error shape. A CP-signed Payment Receipt is accepted only when the caller explicitly expects CP rejection; CP-signed Success is always rejected. MPP signs payment Success or Error; Merchant signs checkout Success or Error. The synthetic agent retains exact artifacts and safe result summaries. Receipt verification alone never mutates payment truth or releases a reservation.

The CP and MPP independently verify the same signed Payment Mandate under one explicitly configured logical payment-verifier audience and registered challenge context. The MPP does not invent a second audience or nonce. Their verifier namespaces and replay-consume state remain separate, and configured TAP trust must independently permit each role. This is a declared cooperating-verifiers profile choice; independent audiences require separately approved mandates and distinct receipt references in a later profile. Merchant Checkout Mandate verification uses its separate audience and challenge.

Malformed and untrusted presentations are rejected without reflecting their bytes. Where a compact final SD-JWT is present, a rejection receipt may bind its exact hash even if verification fails; that is a rejection reference, never proof of authorization. Oversized or framing-invalid input returns only a safe protocol rejection because no valid closed mandate is identified. The exchange explicitly tests both cases.

Before a presentation, a trusted verifier explicitly registers a challenge with `issue(namespace, audience, nonce, checkoutHash, expectedIssuer, expiresAt)`; an unregistered challenge is never accepted. Registration fixes the expected TAP issuer as well as transaction identity and expiry. Changing any binding under an existing namespace/nonce fails. The replay port then performs atomic consume-if-unused against that issued challenge after signature, trust, content and expected-fact validation and before an authorization success. It additionally guards `(namespace, trusted issuer, jti)` across nonce/audience aliases, so a valid alternative ES256 signature or a new challenge cannot reuse the same authorization identity. CP and MPP register the shared logical context in their distinct namespaces. Nonces are verifier-issued; arbitrary caller-chosen nonces are not authorized. Duplicate presentations produce a signed replay Error. A failed comparison does not consume authorization. The offline implementation is in-memory and process-scoped; it proves concurrency behavior but supplies no durable financial replay protection. It is prohibited from PaymentIntent financial admission until a reviewed durable transaction boundary exists.

## Java responsibility split

All protocol values are immutable. Exposed arrays/trees require defensive copies. Expected facts are constructed by a trusted caller and are not inferred from the mandate being verified.

| Owner boundary | Java contract |
| --- | --- |
| Strict claims | `Ap2Claims` nested records for `Envelope`, `CheckoutMandate`, `PaymentMandate`, `Merchant`, `Amount`, `PaymentInstrument`, `CheckoutReceipt`, `PaymentReceipt`; parse methods accept already authenticated `JsonNode` and enforce allowlists/types/closed shapes. No trust, cryptography, clock lookup or persistence. |
| Crypto | Strict JSON and compact JWS framing, P-256 ES256 signing/verification, exact SHA-256/base64url helpers, exact SD-JWT terminal framing. Returns authenticated payload plus immutable original artifact; verifies only against an explicitly supplied trusted key and expected `typ`. |
| Trust and engine | Configured trust by issuer/kid/artifact role; `Clock` or explicit `Instant`; expected audience/nonce/transaction facts; replay port; Merchant/CP/MPP/receipt decisions. No financial core imports or provider ports. |
| Independent fixtures | Python-generated synthetic public-key artifacts and expected digests/decisions; independent Python validation of Java-emitted signatures, hashes, claims and references. No Google implementation dependency or Java-generated expected signatures. |

The frozen strict-claims API is one public `Ap2Claims` utility with nested public immutable records:

```java
sealed interface Content { String type(); }
record CheckoutMandate(String checkoutJwt, String checkoutHash) implements Content {}
record PaymentMandate(String transactionId, Merchant payee, Amount paymentAmount,
                      PaymentInstrument paymentInstrument) implements Content {}
record Merchant(String id, String name, String website) {}
record Amount(long amount, String currency) {}
record PaymentInstrument(String id, String type, String description) {}
record Envelope(String issuer, long issuedAt, long expiresAt, String audience,
                String nonce, String jwtId, Content content) {}
sealed interface Receipt {} // common accessors are defined by both records below
record CheckoutReceipt(String issuer, long issuedAt, long expiresAt, String audience,
                       String nonce, String jwtId, String reference, String status,
                       String result, String orderId, String error,
                       String errorDescription) implements Receipt {}
record PaymentReceipt(String issuer, long issuedAt, long expiresAt, String audience,
                      String nonce, String jwtId, String reference, String status,
                      String result, String paymentId, String pspConfirmationId,
                      String networkConfirmationId, String error,
                      String errorDescription) implements Receipt {}
```

`Content.type()` returns its exact native AP2 `vct`. Receipt status/result are validated exact strings. Optional instrument description and inactive receipt-branch members are `null`; absent and explicit JSON null are distinguished (explicit null is rejected). `envelope(JsonNode)`, `checkoutReceipt(JsonNode)`, `paymentReceipt(JsonNode)` parse authenticated trees; overloaded `toJson(Envelope)`, `toJson(CheckoutReceipt)` and `toJson(PaymentReceipt)` return fresh `ObjectNode` trees. Constructors validate the same value rules as parsing. Invalid shapes/values throw only `IllegalArgumentException("invalid_claims")`, never raw content. Records override `toString()` with redacted output; raw checkout compact JWTs and claim payloads are never included. Crypto, lifetime verification and expected-fact comparisons remain the engine's responsibility. This split permits parallel code only after independent design and owner acceptance.

## Acceptance and evidence

The executable result is a Java synthetic offline exchange of both mandate types, a scoped credential binding, Merchant and MPP role decisions, signed positive/negative typed receipts, and independent cross-language verification. This is materially more than JSON Schema validation. Tests must prove signature and type rejection, role-confused keys, wrong issuer/kid/audience/nonce, expiry and future issuance boundaries, excessive lifetime, duplicate JSON, malformed UTF-8/base64url, altered checkout bytes, checkout substitution with equal payload but different signature, amount/payee/instrument mismatch, missing PSP/network IDs, contradictory receipt fields, wrong receipt reference, unsupported delegation/constraints/VDC and replay. Concurrent duplicate presentations must yield at most one acceptance.

Positive vectors are produced independently in Python using the already pinned offline cryptographic dependency; Java verifies them. Java output is independently verified by Python. Negative vectors include cryptographically valid wrong-policy artifacts, not only damaged signatures. Recomputed fixture hashes and pinned profile/source revisions accompany evidence. Synthetic keys are randomly generated in memory for each fixture run; no private key is serialized or committed. Safe command output contains summaries and digests, never full credentials or payment artifacts.

No PaymentIntent state, existing PostgreSQL schema, provider admission rule, graph checkpoint, UI consent claim, deployed service or existing synthetic profile is changed. ACP remains embedded in the future enclosing PaymentIntent composition; LangGraph remains orchestration. Connection to the core requires a later reviewed slice that atomically binds the negotiated ACP checkout revision, durable replay/consent evidence, scope and financial operation owner. External provider, trusted-user, merchant sidecar and real authentication dependencies remain disabled.

## Consequences and alternatives

Independent Java source makes protocol decisions auditable and keeps payment truth independent from a vendor SDK. Existing JDK primitives and Jackson avoid a new dependency, but require strict bounded JOSE framing and independent interoperability vectors. A broad general-purpose SD-JWT/Delegate/VDC implementation would increase scope substantially and is deferred with fail-closed behavior. Reusing or translating Google's SDK was rejected by the user's explicit instruction. Merely extending the schema lab was rejected because it cannot demonstrate role authorization, cryptographic binding or exchanged signed receipts.

This profile can be described only as an independently authored synthetic direct TAP slice of pinned AP2 v0.2. It cannot be described as full AP2, ACP interoperability, user consent certification, provider support or financial execution readiness.
