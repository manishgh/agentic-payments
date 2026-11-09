# Executable local-fake contracts

[Documentation](../README.md) / [Reference](README.md)
The [schema directory](../../specification/0.1.0-draft/schemas) materializes the field rules in [the reviewed internal API](api-contracts.md) as JSON Schema 2020-12. These are internal simulator contracts for `simulator-api-0.1-draft` and `local-fake-single-tender-0.1`, not AP2 or ACP schemas. Schema availability alone does not establish runtime enforcement or a passing conformance result.

`contracts.schema.json` contains shared definitions. Each command and resource response has a separate entry-point schema. Every object is closed, every declared field is required, and null is permitted only for the explicitly nullable status fields. Money is a positive decimal string in `1..999999999999`, with EUR/USD exponent 2. An integer or floating-point JSON amount is invalid. Version and checkout revision are positive signed 32-bit integers.

The [profile descriptor](../../specification/0.1.0-draft/profile.json) identifies the exact synthetic offer, separate provider/method/credential/action dimensions, unsupported operations and exclusions. It starts with no conformance evidence and `not-assessed`; its existence is no certification decision. The [catalog](../../specification/0.1.0-draft/fixtures/catalog.json) pins server-owned fixture values to `fixtures-0.1`. IDs have restricted lowercase ASCII syntax; runtime lookup must also require membership in the authenticated tenant/run's catalog. The scenario list is `success`, `challenge-then-success`, `response-loss-after-acceptance`, `delayed-success`, and `definitive-no-effect`.

## Validation order and semantic boundaries

The transport must enforce the 16 KiB UTF-8 body limit, reject duplicate JSON member names before materializing objects, authenticate the bearer session, validate the endpoint schema, then apply catalog membership and authorization. A schema validator must enable `uuid` and `date-time` format assertions. Timestamp patterns additionally require UTC `Z`; parser validation must reject impossible dates. Correlation IDs permit safe printable ASCII up to 128 characters; the implementation can generate UUID correlation IDs. Authority references and the revoke response's `authorityId` are fixture IDs, while generated resource IDs and run IDs are UUIDs.

JSON Schema cannot establish tenant/run authorization, expected-version equality, quote/action/authority expiry, grant subject/scope, allowed state transitions, catalog referential integrity, item-total arithmetic, sorted revoked-attempt IDs, or the financial invariants. Application transactions must enforce those rules. The action-response state catalog includes `CANCELLED` from the reviewed data model; only OPEN has approve/deny choices. Successful completion maps VERIFIED to READY and DENIED to FAILED. Expiry and authority loss follow the reviewed failure/error rules.

The `Idempotency-Key` is a required HTTP header on each POST: 16..128 printable ASCII characters. It is deliberately absent from command bodies and schemas. Command replay identity is `(tenant,run,principal,method,route-template,target,key)` and the validated request digest includes expected version/profile. The financial adapter key is a separate immutable operation identity. A valid request schema grants neither replay access nor spend authority; authorize before returning a stored response.

## Additive trusted checkout review contract

The implementation adds `GET /internal/v0.1/intents/{id}/checkout` so the trusted approval surface can review the server-owned checkout without trusting a graph-supplied digest. It requires `approval:write`, the authenticated grant subject, and the normal tenant/run/merchant scope. The closed response contains exactly `contractVersion,correlationId,intentId,version,state,merchantId,checkoutRevision,checkoutDigest,money,quoteExpiresAt`; state is QUOTED or APPROVED. It exposes no credential reference, authority payload or action secret. [The response schema](../../specification/0.1.0-draft/schemas/get-checkout-response.schema.json) records this additive draft contract; graph-safe status remains the separate projection from the reviewed API. This draft addition requires inclusion in the implementation review, not a claim that the original design already contained it.

## Golden checkout and negative fixtures

The [golden checkout](../../specification/0.1.0-draft/fixtures/checkout-canonical.json) is exactly 402 UTF-8 bytes with no BOM or trailing newline. Field order is part of the local encoding. Its SHA-256 is `c1f63c9e22cb8953af008e171b42d17fa188e027651ff07fc8620fceb6c238ad`; the [metadata](../../specification/0.1.0-draft/fixtures/checkout-canonical.metadata.json) records that identity. All other JSON artifacts end in a newline. The canonical serializer must reconstruct ordered fields from validated typed catalog values, rather than hash caller JSON or an unordered map.

The [fixture manifest](../../specification/0.1.0-draft/fixtures/manifest.json) separates:

- Schema-valid endpoint examples with UUID resource IDs and the exact golden digest.
- Schema-invalid input/output shapes, including unknown fields, forbidden providers/capabilities, split tender, amount encodings, placeholder digests, credential leakage and payment success asserted by action completion.
- Structurally valid mutations of merchant, amount, currency, revision, credential and items. These must fail the immutable approved checkout binding even though they satisfy the snapshot's structural schema. Schema validation alone is insufficient for these cases.
- Raw parser fixtures that require rejection before schema evaluation.

These fixtures are synthetic test inputs. They do not contain real payment credentials, signed AP2 mandates, vendor callbacks or evidence of real authentication. Runtime contract tests, canonical serialization tests and independent conformance evidence must report their actual results separately.

Run `python scripts/validate_contracts.py` with the pinned development dependency `jsonschema==4.26.0`. The validator checks each Draft 2020-12 schema, enables format assertions, resolves references from the versioned local registry without network retrieval, validates the profile/catalog/examples, rejects negative/parser fixtures, and checks canonical bytes plus altered bindings. Java SDK/golden tests run with `mvn test` after the verified toolchain bootstrap.

## Java adapter boundary

The dependency-free `io.agenticpayments.sdk` package provides immutable `Money`, `ExecutionScope`, `OperationIdentity`, `ChargeOperation`, capability combinations/offers, preparation, facts and lookup results. `Money.parse` accepts the same bounded decimal strings as the wire contract; its internal `long` never substitutes floating-point arithmetic. Scope includes tenant/run/merchant; operation identity separately binds intent/attempt/operation/adapter key and immutable request digest. It contains no API command idempotency key or raw credential.

`PaymentAdapter` exposes `capabilities`, effect-free `prepare`, `execute`, `lookup` and atomic local `sealNoEffect`. Facts distinguish PENDING, SUCCEEDED and authoritative NO_EFFECT. Lookup distinguishes FOUND, NOT_FOUND and UNAVAILABLE, with a fact only for FOUND; neither absence nor transport uncertainty is evidence that exposure can be released. `AdapterUnavailableException` has a fixed safe message and no provider payload. The core retains responsibility for authentication, grant binding, reservations, action verification, dispatch admission, fact correlation and financial transitions.

Capability negotiation requires an explicit version/profile, known required capabilities and a permitted provider/method/credential/action combination. Its collections are immutable snapshots. Current merchant configuration, authority and action availability still require core checks at selection and admission. The SDK carries no real-provider connector or AP2/ACP projection and makes no external certification claim.
