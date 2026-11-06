# Review 0009: Pinned AP2 and ACP bridge boundaries

Status: independent design review completed, 2026-11-06. Accept the source-pinned research and disabled bridge candidate; do not enable a real bridge or claim native AP2 verification from this evidence.
Reviewer: independent protocol/security architect, distinct from the author of [the bridge design](../ap2-acp-bridge-design.md) and its source probes.
The concrete offline work recommended below is the reviewer's proposed next slice and requires integration-owner acceptance; it is not independently reviewed implementation.

## Verdict and composition

The proposed composition preserves the intended product: the platform PaymentIntent encloses AP2 authority, embedded ACP merchant checkout and separately verified provider authentication.
Neither standard defines this project aggregate or an official AP2-to-ACP bridge. The design correctly keeps internal IDs separate from native signed-artifact references.
One merchant/core execution owner remains essential; losing ACP completion must never cause a second platform charge.

Proceed now with an offline ACP snapshot/handler validator, exact merchant-checkout JWS verification/hash laboratory and synthetic sidecar/idempotency tests.
Those components need no provider accounts. A complete native AP2 mandate-chain verifier is a subsequent bounded slice after the cryptographic profile issues below are resolved.
Do not postpone all offline work because delegated-authentication or provider enrollment is blocked; those blocks apply to particular execution profiles.

## Source evidence independently checked

The reviewer read the actual cached AP2 v0.2.0 specification, agent-authorization, checkout/payment mandate documents and schema families,
and the ACP 2026-04-17 checkout/authentication/payment schemas and checkout OpenAPI contract.
The reviewer independently recomputed decoded-byte SHA-256, Git blob SHA-1 and byte length for all 53 artifacts in [the source lock](../../integrations/upstream/source-lock.json); all matched.
This establishes equality to the locked source identities. It does not by itself prove trust in a signing key, deployed peer acceptance or runtime interoperability.

The [probe source](../../integrations/upstream/probe_contracts.py) verifies the required artifacts before schema evaluation, prohibits remote schema retrieval,
and explicitly registers the pinned receipt-status file-location alias without modifying source bytes.
The reviewer inspected all 12 structural fixtures and independently executed them with `.venv/Scripts/python.exe integrations/upstream/probe_contracts.py`:
all expected valid/invalid outcomes matched, with six necessary source artifacts verified and signatures/interoperability correctly reported false.
The reviewer also executed `.venv/Scripts/python.exe -m unittest discover -s integrations/upstream -p test_probes.py`: four tests passed.
The initial WindowsApps Python alias was unavailable; using the repository's actual environment resolved that tooling issue without changing any test or schema.
The separate all-53 source-integrity check above executed successfully using PowerShell/.NET.

## Findings and disposition

| ID | Finding | Decision / exact effect |
| --- | --- | --- |
| UR-001 | New ACP supports open-string handler PSP identifiers, typed handler/instrument selection, authentication metadata/result and additional checkout states. | Confirmed in the pinned released schema. Reject the older blanket claim that ACP inherently accepts only a closed card/PSP list. Hosted partner acceptance remains a separate deployment fact. |
| UR-002 | DelegateAuthenticationSessionWithResult composes a closed base with an added authentication_result property not admitted by that base. | Confirmed BR-002. A result-bearing object fails the pinned allOf schema. Do not patch source or treat authenticated-without-result as sufficient 3DS evidence. Blocks that exact result exchange, not offline checkout parsing. |
| UR-003 | Checkout completion's 200 schema requires order, while broad session state enums do not define the complete asynchronous/authentication lifecycle. | Confirmed BR-003. A pending/authentication session without order is not this response shape. Keep unknown financial exposure; never invent an accepted completion response. |
| UR-004 | Released delegated payment is card-shaped; generic handler PSP strings and seller-backed gift-card RFC examples do not extend that endpoint automatically. | Confirmed BR-004. A profile needs its actual credential transport/instrument schema; metadata registration is insufficient. |
| UR-005 | AP2 successful Payment Receipt requires PSP and network confirmation IDs; generic authorization receipt prose and AP2 type-specific receipts use different status fields/casing. | Confirmed BR-005/006. Select type-specific semantics explicitly; never fabricate a real network confirmation. Preserve the exact reference-resolution alias and source contradiction evidence. |
| UR-006 | Native mandate verification depends on a moving Delegate SD-JWT reference whose current delegation request shape differs from AP2's examples/prose. | Blocking a native mandate-chain claim. Pin and reconcile an exact delegation profile, with positive/negative cryptographic vectors. See below; merchant JWS verification remains independently implementable. |
| UR-007 | checkout_jwt representation is not precise enough for an interoperable byte implementation. The schema describes an encoded serialized JWT while prose/examples indicate a JWT string. | Choose one explicit offline representation and reject alternatives; hash the exact chosen field-value bytes. Do not auto-detect or silently decode twice. Real peers must agree to that choice. |
| UR-008 | Choosing ES256 does not by itself establish randomized merchant signatures. Deterministic ECDSA signatures are valid under the same verifier. | Fixture signer must explicitly use fresh cryptographic randomness. Algorithm validation cannot prove an external signer's nonce policy. This is a signer/profile obligation, not a locally inferable claim. |
| UR-009 | JSON Schema is weaker than several normative semantic requirements, including receipt fields described as present only for a particular status. | Add semantic checks above the unchanged schema. AdditionalProperties acceptance must not permit conflicting success/error semantics or unverified authority. |

The initial [disabled constraints](../../integrations/upstream/bridge-profile.constraints.json) appropriately advertise no real combinations and keep both runtime and cryptographic implementation disabled.
Do not silently flip those flags when building a separate offline laboratory; maintain a distinct profile and evidence record.

## Native authority and byte boundaries

The tagged AP2 [specification](https://github.com/google-agentic-commerce/AP2/blob/b4587ac1d055888a73b4b21750973cffba961793/docs/ap2/specification.md)
requires deterministic verification by the relevant role. Direct mode trusts a user credential or recognized Agent Provider; autonomous mode additionally verifies the delegated key and constraints.
Checkout and Payment Mandates remain distinct authorizations. Customer authentication cannot replace either one.

The [Checkout Mandate rules](https://github.com/google-agentic-commerce/AP2/blob/b4587ac1d055888a73b4b21750973cffba961793/docs/ap2/checkout_mandate.md)
bind checkout_hash to the checkout_jwt field value. The Payment Mandate transaction_id refers to that checkout hash, not an intent UUID.
Re-signing an identical payload can therefore change the transaction binding. Preserve the actual signed artifact and obtain new authority when that binding changes.
Do not hash a reserialized ACP map or the existing local-fake checkout encoding.

Receipts refer to the appropriate closed mandate presentation using the SD-JWT hash construction.
This is a different byte domain from the merchant checkout hash: disclosure order, separators and key-binding boundaries matter.
A helper that hashes arbitrary decoded mandate JSON is not an AP2 receipt verifier. [RFC 9901](https://www.rfc-editor.org/rfc/rfc9901.html) defines the SD-JWT processing and binding rules; it was published in November 2025 despite the earlier date in AP2's bibliography.

AP2's [agent-authorization document](https://github.com/google-agentic-commerce/AP2/blob/b4587ac1d055888a73b4b21750973cffba961793/docs/ap2/agent_authorization.md)
points to an individual-draft repository without an immutable revision. During this review that URL redirected to the OAuth working-group repository.
The reviewer read [draft source at commit 0c1d6863e4c3fc2baffc9cd8246eca902b49d9e1](https://github.com/oauth-wg/oauth-delegate-sd-jwt/blob/0c1d6863e4c3fc2baffc9cd8246eca902b49d9e1/draft-ietf-oauth-delegate-sd-jwt.md).
Its OpenID4VP delegation section uses delegate_payload_disclosure and dSD-JWT/dSD-JWT+KB format values, while AP2's direct-delegation description uses delegate_payload arrays and a dc+sd-jwt example.
This observation does not select the current draft as AP2 v0.2's intended dependency; it demonstrates why a mutable link cannot serve as a compatibility lock.

Before a native verifier slice, record exact RFC/draft versions and hashes for SD-JWT, any SD-JWT VC profile, Delegate SD-JWT and any OpenID4VP transport actually implemented.
Choose one direct trusted-Agent-Provider presentation shape first; omit autonomous/open constraints and wallet delegation transport until separately supported.
Specify issuer/subject/key trust, role-specific audiences, freshness/nonces, revocation, replay ownership, exact typ/vct values, disclosed-claim processing and receipt references.
Require complete signatures and chain bindings, reject unknown constraints and missing proofs, and verify that the closed claims preserve required inherited values.
Locally generated keys make valid synthetic signatures, not automatically recognized AP2 user or merchant authority.

The [JWS standard](https://www.rfc-editor.org/rfc/rfc7515) defines signing input and compact representation;
[JWA section 3.4](https://datatracker.ietf.org/doc/html/rfc7518#section-3.4) defines ES256's signature representation.
[JWT best practices](https://www.rfc-editor.org/rfc/rfc8725.html) require explicit algorithm/key validation and protection against cross-token substitution.
The separate [deterministic ECDSA specification](https://www.rfc-editor.org/rfc/rfc6979.html) explains why a normal verifier cannot determine whether the signer used randomness.

## Concrete offline slice available now

Suggested isolated profile: `offline-ap2-acp-artifact-lab-0.1`. It is test-only, contains synthetic keys/data and cannot authorize a live or local payment-core dispatch.
It leaves `local-fake-single-tender-0.1` and the disabled real-bridge candidate unchanged. Its report distinguishes merchant signature validity, content binding and unimplemented AP2 authority verification.
The integration owner can approve the following implementation contract without partner credentials:

| Component | Minimum implementable contract |
| --- | --- |
| PinnedSchemaRegistry | Load only the locked ACP/AP2 bytes and known local aliases; verify SHA-256/length; reject unlisted refs and network retrieval. Never repair upstream schemas silently. |
| FrozenAcpCheckout | Strict UTF-8/duplicate-key/size-limited parse; validate the exact CheckoutSession schema; require version 2026-04-17, one full positive EUR/USD total and a profile-valid expiry. Preserve original bytes and immutable revision. |
| OfflineHandlerRegistry | Match handler ID/name/version/PSP/config plus instrument-schema identities to explicit local pinned fixtures. Validate config/instrument with those fixtures; never fetch a URL carried in a checkout. |
| MerchantCheckoutJwsVerifier | Verify a compact JWS using an explicitly pinned merchant role/key/origin and exact expected payload bytes; return a safe verified artifact handle and checkout hash. It does not verify AP2 mandates. |
| MandateContentBinder | Validate closed-mandate content shapes and compare checkout_hash/transaction_id, merchant/payee, exact currency/amount and instrument to the frozen context. Return content-bound-but-authority-unverified. Never manufacture spend approval from this result. |
| SyntheticSidecarLedger | Bind merchant/session/revision/artifact digest and test attempt/owner; scoped same-key replay/conflict; changed checkout invalidates prior binding. An in-process trusted test boundary substitutes for deployment authentication only in this lab. |
| StateProjection | Test ACP ready/completed/authentication/pending/canceled mappings without financial inference. Completed order is commerce evidence; timeout/checkout cancellation is not no-effect payment evidence. |

For this first lab choose exactly the three-segment ASCII compact JWS as checkout_jwt, with no outer base64url layer.
Compute base64url-without-padding SHA-256 over that exact ASCII field value. This is an explicit candidate profile choice resolving UR-007 locally, not proof of peer agreement.
Reject whitespace, padding variants, extra segments, double-encoded compact strings and alternative serialization rather than normalizing them into another signed artifact.
Require the decoded payload bytes to equal the frozen ACP snapshot bytes; verify the signature over original protected-header/payload segments.
No API receives a precomputed trusted digest or a caller-supplied signature-valid boolean.

Use a dedicated merchant-checkout verification path, ES256/P-256 only initially, and an explicitly expected JWT type for this lab.
Resolve kid through a preconfigured merchant-role trust map; reject token-controlled jwk/jku/x5u retrieval, none/HMAC/algorithm substitution, unknown critical headers and unsupported detached/unencoded payload forms.
An unchanged ACP payload has no invented AP2/issuer members: signer/merchant/origin trust is provided by the verifier's external expected context, not a new undeclared checkout field.
Require and check the profile's checkout expiry against an injected clock. Subject/merchant/session binding, payload shape and dedicated key usage prevent a mandate/receipt token from being accepted as a merchant checkout.
A fixture signer uses fresh cryptographic randomness; two signatures over the same snapshot should verify but produce independently bound artifact hashes.
Use a maintained reviewed JOSE implementation or the JDK's documented signature APIs; do not implement elliptic-curve arithmetic, ad hoc ASN.1 conversion or a partial SD-JWT parser.
Pin the chosen implementation/API behavior and independent golden vectors before accepting its code.

The lab may use the existing schema-valid synthetic handler/instrument shape from the structural probes, with requires_delegate_payment=false and no real credential.
It does not claim that a real card, wallet, voucher or PSP implements that handler. No 3DS/delegated-payment endpoint is called.
Treat authentication_required as blocked-for-this-lab; do not remove authentication requirements to force ready status.
A ready synthetic merchant fixture can test the completion request and a completed response with a schema-valid synthetic order.
Keep this mock completion isolated from actual core admission and require one ledger owner; missing/timeout mock results remain unresolved.

## Exact acceptance tests for that slice

1. Locked schema/alias resolution succeeds offline; modified bytes, absent dependency, arbitrary URL/ref and duplicate JSON keys fail before binding.
2. Validate ready ACP fixtures, current handler/instrument completion shapes and order-bearing completed responses; retain BR-002/003/004 negative probes unchanged.
3. Valid synthetic merchant JWS verifies; changed header/payload/signature, wrong merchant key/kid, wrong curve/algorithm/type, stale checkout and unsupported serialization fail.
4. Verify both compact-signature segment bytes and exact decoded snapshot-byte equality. Reordered/reserialized payloads require their own signature/artifact hash and cannot reuse prior content binding.
5. Alter checkout_hash, Payment Mandate transaction_id, merchant/payee, amount/currency, instrument, session, revision or selected handler; every mismatch refuses binding.
6. Re-sign the same frozen checkout with independently randomized signatures; hash changes and old authorization content does not bind the new artifact. Do not label this statistical fixture check proof about arbitrary external signers.
7. Sidecar same-key/same-input replay preserves owner/result, changed input conflicts, competing owner rejects, timeout does not allocate a second operation and changed checkout requires new binding.
8. Reports contain only opaque artifact references and safe assertions; synthetic private keys, compact credentials/mandates, tokens and customer-action material never appear in graph/status/log output.
9. Successful merchant-JWS/content checks still report native AP2 mandate verification=false and provider interoperability=false; no code path calls the local payment facade or a network provider.

An offline native mandate verifier can follow after the exact delegation/VC profile is selected and reviewed; this is feasible local work, not blocked on merchant accounts.
Its required negative vectors include damaged issuer/delegate/holder signatures, altered disclosure digests, repeated or unbound disclosures, swapped chain components,
missing key binding, wrong nonce/audience, expired/revoked trust, inherited-claim changes, unknown constraints and wrong receipt-reference byte domains.
No hosted participation is needed to write those vectors, but choosing their precise valid envelope requires closing UR-006/007 first.

## External gates that remain concrete

Real operation still requires merchant acceptance of the ACP version and negotiated AP2 checkout-object/sidecar contract,
recognized merchant/user/Agent-Provider trust and revocation policy, supported credential/authentication/provider accounts, and verified financial idempotency/query/event behavior.
The 3DS result-schema conflict and post-completion continuation agreement apply before enabling those routes.
Networkless receipts and seller-backed/voucher flows need their own negotiated semantics and tests; do not synthesize missing real evidence.
Hosted ChatGPT participation is separate from public ACP schema availability. None of these external gates erase the offline work above.

## Reviewed artifact identities

| Artifact | SHA-256 |
| --- | --- |
| `docs/ap2-acp-bridge-design.md` | `4746f582fadccd726f814f0d4006fc70bc6ddfe3261708395aebe71d977cc8b5` |
| `integrations/upstream/source-lock.json` | `85f03bea619c68d35442916ab05c31d067c0dbfe30ce43e5bd520e6e9805b28a` |
| `integrations/upstream/bridge-profile.constraints.json` | `4f72e76a6a52a12ffbeb94af624fa122b526596d00bde7a5b79590f183c2c4d7` |
| `integrations/upstream/fixtures/structural-probes.json` | `0c23beb57ee8f8dc80fc4e96ccb716d98bd638e45cf8090d0809440e78b2b689` |
| `integrations/upstream/probe_contracts.py` | `2b8cdd58e4d05788c99da1ef95e8b7497781443da4d142c59bfda45a653a6fc7` |
| `integrations/upstream/test_probes.py` | `f2a4a57a59d725fae7063028e4fed835565c607ec51dbc959a41613f4472674b` |

Only this review document was authored by the reviewer in this task; no application source, upstream cache, source lock or probe was changed, and no commit was created.
The integration owner records acceptance of the exact offline subset and any subsequently reviewed cryptographic profile. This review leaves G2-P interoperability and live-operation gates open.
