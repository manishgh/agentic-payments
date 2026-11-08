# ADR 0008: Extract native application artifact and verification coordination

## Status

Accepted for the bounded reference profile described below. See the platform guides and executable tests for current implementation limits.

## Context

`JdbcPaymentStore` currently combines SQL/transaction ownership with native checkout reconstruction, immutable artifact staging, strict artifact decoding, independent authority verification and verified-role policy. Those latter responsibilities use the existing [composition ports](../../src/main/java/io/agenticpayments/composition/Composition.java), not JDBC, and can be tested with alternate implementations without a database. Keeping them inside JDBC makes the replaceable protocol/storage contracts harder to exercise as one application operation.

A complete `PaymentStore`/unit-of-work redesign is larger than this slice. Native approval intentionally interleaves protocol work, public-enrollment updates and provisional SQL records inside one locked transaction. Moving it wholesale behind a backend facade would obscure these invariants. A coordinator must expose the necessary phases rather than manufacture independently committed grant, replay or reservation services.

## Decision and ownership

Add a concrete, stateless `NativeApplicationCoordinator` in the application package. It depends only on existing `CheckoutProtocol`, `AuthorityIssuer`, `AuthorityVerifier` and `SignedArtifactStore` responsibility contracts plus immutable values. It imports no JDBC, Jackson, native protocol implementation, signer implementation or infrastructure class. The existing ports remain the replaceable interfaces; the coordinator is application policy, not another adapter contract or service locator.

| Application coordinator owns | JDBC admission owner retains |
| --- | --- |
| Invoke checkout freezing and validation; stage and reload exact checkout/mandate bytes through the configured artifact store. | Current public `PaymentStore`/`PaymentService` commands and responses; authentication, object/merchant/grant scope and command authorization before replay. |
| Check stored checkout digest against the expected durable checkout digest before reading its artifacts. Verify each retrieved artifact's reference metadata and bytes, enforce the native artifact profile and strict UTF-8, and reconstruct typed protocol inputs. | Transaction/retry boundaries, run lock, virtual clock, idempotent command lookup/result storage and safe error translation. |
| Invoke issuance, stage its exact outputs, reload staged mandates and invoke the independently registered verifier. Signing never substitutes for verification. | Trusted provisioning and immutable enrollment validation/upsert; transaction-owned enrollment snapshots, resolver binding and the existing authoritative resolver wrapper. |
| Enforce current verified-role policy: exactly one MERCHANT, CREDENTIAL_PROVIDER and PAYMENT_PROCESSOR authorization; issuer, checkout hash and role/effective-expiry bounds match the trusted context. | Current intent/checkout/attempt/grant/budget facts; deadline and nonce generation; persisted authority metadata/expiry association checks; challenge/replay uniqueness and attempt ownership. |
| Calculate the existing authority reference-pair digest with the same newline separator and compare the expected stored digest at the same phase as today. | Atomic attempt, authority, challenge, replay, reservation, budget, audit and command writes; PREPARE/DISPATCH gates, termination/release policy, outbox fences, admission and recovery. |

The coordinator never admits, reserves, consumes a challenge/replay identity, changes payment state, acquires trust independently, or returns a financial-admission proof usable through a new bypass path. `VerifiedAuthority` remains intermediate verification output consumed only by the existing locked JDBC pipeline. The JDBC resolver wrapper retains current scope/time checks, enrolled-key equality, revocation and validity checks; it is not replaced by unchecked resolver output.

### Typed seam and persisted compatibility

Move the immutable shapes currently named `StoredCheckout` and `StoredAuthority` into application-owned evidence records. Preserve every existing JSON property name and representation: checkout references/hash/digest/session/issuer/handler/version fields; authority references/issuer/audiences/nonces and string-form issued/expiry instants. JDBC retains JSON serialization/deserialization and SQL row mapping. Do not add serialized derived fields, change stored JSON, or require a migration. Evidence records must redact their string representations.

Coordinator methods take `Scope`, `CheckoutFacts`, these evidence values, `AuthorityContext`, `SigningIdentity`, `KeyResolver`, `Instant` and the existing artifact/protocol records as appropriate. They never accept `Connection`, transaction objects, SQL maps, sessions, arbitrary callbacks or generic command dispatch. The intended phases are:

1. Freeze a checkout; validate it with the supplied transaction-bound resolver; stage it and return typed checkout evidence.
2. Reload a checkout from evidence and expected digest; validate the resulting exact `FrozenCheckout` with a subsequently supplied resolver.
3. Issue and stage authority for an already constructed trusted context/signer; return typed authority evidence.
4. Reload the exact authority artifacts; on re-verification compare the stored reference-pair digest; then verify with a subsequently supplied resolver and enforce role policy.

These are explicit method boundaries, not one method that captures the transaction. In particular, freeze, artifact reload/digest checks, and verification must be separable so JDBC can bind the resolver at its existing point. Passing a pre-bound resolver into a combined read-and-verify method would move snapshot binding earlier through Java argument evaluation. No lazy resolver, callback or cached transaction snapshot is introduced to hide that change. Shared private helpers may remove duplication within this small application class; avoid a public interface per helper.

`Composition.Ports`, registration capability/owner validation and the `PaymentServices.local(...)` injection signatures remain unchanged. Existing constructors, including legacy stores with no native ports, retain their behavior. Wire the coordinator from the already registered responsibility implementations. No new capability, protocol profile, dependency, HTTP API, schema, provider or external ACP adapter is introduced.

## Required operation order

The extraction preserves the current executable order, including failure precedence and artifact reads. It does not reorder the current implementation to match a more abstract flow in ADR 0006.

### Quote and approval

- Quote: validate the command/current version and profile; provision and persist public enrollment under the run lock; freeze checkout; bind a current resolver; validate; stage ACP payload then checkout JWT; write the checkout/native checkout rows and command result. Artifact staging remains independent immutable storage and may leave unreferenced bytes on rollback.
- Approval: authorization and accepted-command replay occur before native work. Validate intent/version/state, current grant, quote expiry/digest/revision, currency and budget; insert the provisional attempt as today.
- Check native capabilities; load the persisted checkout evidence and compare its digest before reading ACP payload then checkout JWT; bind a resolver from the **pre-provision** current enrollment snapshot and validate checkout.
- Provision and validate/upsert enrollment. Construct the same context with `min(now + 300 seconds, grant expiry, checkout expiry)`, current subject/grant/version, fixed synthetic audiences and fresh role nonces. Issue authority; stage Checkout Mandate then Payment Mandate. Calculate the unchanged pair digest.
- Insert the provisional authority row and all three challenge rows. Reload Checkout Mandate then Payment Mandate from staging; only then bind a **new** resolver snapshot reflecting the enrollment updates. Independently verify and apply role policy. Insert the three replay identities and replace persisted authority expiry with the verified effective expiry.
- Create HELD reservation, increase budget held, change state/audit and store the safe command response. All SQL mutations commit together. Issuance/staging/verification failure after provisional inserts rolls them back; neither leftover artifacts nor a returned verifier result independently grants spend.

An accepted identical command retry returns its stored response without freezing, issuing, staging or verifying new authority. Current authorization still runs before that replay lookup.

### Execution and worker gates

At each existing native gate, retain this order: native capability check; current grant/attempt eligibility; current checkout revision; persisted authority row's intent/revision/subject/grant/version association; persisted effective expiry; authority JSON decode; checkout evidence/digest and exact checkout artifact reload; bind a current resolver and validate checkout; reconstruct the original authority context; reload both mandates; compare the persisted reference-pair digest; bind a separate current resolver and independently verify/validate roles; compare verified effective expiry to the persisted effective expiry; check each replay identity and issued challenge belongs to this run/attempt and matches its complete current context/deadline.

Reverification is read-only and never reissues, stages replacement artifacts, renews expiry or consumes replay again. Preserve native gates before PREPARE, after preparation and immediately before DISPATCH admission, as well as execute validation. JDBC still owns the existing `nativeGate` decision and the same-lock revocation/admission order. Already admitted/UNKNOWN operation reconciliation does not acquire fresh authority or initiate another charge.

## Errors and security

Preserve existing fixed mappings: unsupported capability -> 422 `UNSUPPORTED_CAPABILITY`; expired authority -> 410 `AUTHORITY_EXPIRED`; signing/crypto/artifact unavailability -> 503 `TEMPORARILY_UNAVAILABLE` with `SAME_COMMAND`; other composition failures, malformed UTF-8 and policy/binding failures -> 422 `AUTHORITY_BINDING_INVALID`. Current trust revocation remains 409 `AUTHORITY_REVOKED`, durable uniqueness remains 409 `AUTHORITY_REPLAY`, and existing SQL retry rules remain unchanged. Do not broaden exception catches or translate raw adapter/runtime exceptions differently as part of this extraction.

The worker's `nativeGate` still propagates 5xx for retry with exposure retained; its existing non-5xx verification failures terminate through JDBC as EXPIRED for 410 or CANCELLED otherwise. Coordinator code must not perform release or interpret unavailable verification as proven no effect. Preserve current read/parser error handling rather than adding a new public error contract in this slice.

Raw ACP/JWT/mandate bytes remain transient application values or immutable stored artifacts, excluded from safe responses, logs, exceptions, graph state and reports. Use synthetic fixtures. The exact implemented profiles and existing external interoperability/certification exclusions in [durable composition](../durable-composition.md) remain unchanged.

## Acceptance and meaningful tests

Add coordinator tests that observe security behavior through existing responsibility ports, rather than merely asserting each helper was called:

- A decorated artifact store returns substituted bytes, inconsistent reference metadata/profile, or malformed UTF-8. Reads reject before protocol verification; checkout digest mismatch rejects before artifact retrieval. Signer/issuer return values cannot bypass reloading original staged bytes.
- A verifier returns missing/duplicate/extra roles, wrong issuer/hash, or invalid role/effective expiry. Each rejects before any consumer could treat it as valid authority. A valid independently implemented/decorated verifier and artifact store succeed through the same contracts.
- Read-only re-verification uses stored mandates without issuance/signing, preserves exact bytes/digest framing and does not mutate artifacts. Evidence values redact content, and pre-extraction stored JSON fixtures still deserialize/round-trip without property/type changes.

Retain real PostgreSQL tests in `DurableAp2AdmissionTest` and `CompositionRegistrationIntegrationTest`. Add focused integration coverage where existing tests do not establish these specific seams:

- Rotate/provision a new TAP enrollment during approval: checkout validation observes the pre-provision snapshot, mandate verification observes the new snapshot; substituted/revoked resolver material still rejects. Exercise separately bound checkout and authority resolvers at execution gates.
- Fault staged mandate retrieval or independent verification **after** provisional attempt/authority/challenge inserts. Assert rollback of all admission/replay/reservation/budget/command mutations; a same-key retry can complete once the fault clears. Harmless unreferenced staging grants no authority.
- Inject `CRYPTO_UNAVAILABLE` at a worker verification gate. Assert 503 `TEMPORARILY_UNAVAILABLE`/`SAME_COMMAND`, unchanged READY/HELD state and exposure, rollback of the worker's provisional claim fence/state, and no provider call. Clear the fault and retry the original operation successfully; artifact-outage coverage alone does not prove this classification across the extracted seam.
- Retain accepted-command retry/restart without signer reuse, duplicate/replay approval races, SQL rollback, expiry/revocation ordering, dispatch storage-unavailability retry, and admitted/UNKNOWN reconciliation with one operation. Maintain the legacy profile regression and registration/owner rejection tests.

Run the documented complete conformance command with isolated real PostgreSQL, documentation checks and `git diff --check` after implementation. Do not substitute in-memory tests for the financial transaction/race evidence. No additional migration or dependency test matrix is needed because this slice changes neither.

## Consequences and alternatives

This extraction is useful because one application-owned component can exercise exact artifact handling, independent verification and verified-role policy against replacement/decorated ports without infrastructure dependencies. The transaction owner stays visibly responsible for the interleaving that gives those operations financial meaning.

The tradeoff is several explicit phase calls in JDBC, plus typed evidence records crossing the boundary. Native context creation, provisioning/trust ordering, grant policy and overall use-case orchestration still live in the reference transaction owner. This is partial IMP-010 progress, not a completed `PaymentStore` decomposition or proof that another admission backend exists.

Keeping everything in JDBC avoids a class but leaves database-independent application policy inaccessible as a unit. Moving the entire transaction behind `NativeBackend`/generic callbacks or separate replay/grant CRUD ports adds a facade while weakening reviewability. Moving all orchestration to `PaymentService` now would require a separately reviewed transaction/admission contract. Those alternatives are deferred; complete financial-backend replacement and external ACP integration remain separate work.
