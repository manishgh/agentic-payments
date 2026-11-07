# Review 0016: Independent AP2 direct engine design

Status: independent design review completed, 2026-11-07. Recommend integration-owner acceptance of the bounded design at the exact identities below. No blocking design finding remains. This review does not accept implementation or authorize financial execution.

Reviewer: independent protocol/security architect. The reviewer supplied findings and authored this review only; separate authors own [ADR 0005](../adr/0005-independent-ap2-engine.md) and the [AP2 requirements](../ap2-requirements.md). Baseline: `95b5ac8276601f41c5f9d8e0380eb57c9892e9c7`.

## Scope and protocol assessment

An independently authored Java 21 implementation can honestly implement the bounded `ap2-v0.2-direct-tap-synthetic-0.1` profile without Google's SDK, generated models or implementation code. Existing Jackson and JCA suffice for its strict claims, standard ES256 signatures, exact-byte SHA-256 references, role trust and synthetic exchange. Standard primitives are used through JCA; this is not approval to implement elliptic-curve mathematics or a general cryptographic library.

The selected authority model is AP2's direct Trusted Agent Provider trust list. It does not implement the different User Credential issuer/holder path. A trusted synthetic provider signature proves that fixture issuer's assertion; it cannot prove actual informed human consent, protected production key custody or instrument ownership.

The mandatory final tilde, SD-JWT VC header and credential envelope matter. `issuerJWT~` with no disclosures and no key binding is a valid restricted SD-JWT VC representation. The profile rejects a bare JWT in its place and rejects unsupported disclosures, hidden digests, holder keys and delegation chains. One inline closed mandate under `delegate_payload` follows the AP2 provider envelope pattern. The outer credential type is a project identifier; the inner closed mandate types remain the exact AP2 types. This is a negotiated bounded profile, not a claim that every AP2 peer uses this representation.

The reviewer inspected the pinned AP2 authorization, specification, security, implementation, mandate and receipt artifacts in the existing ignored research cache, including the published schema requirements. The following independent standards checks support the packaging decision:

- [RFC 9901](https://www.rfc-editor.org/rfc/rfc9901.html), sections 4 and 7: compact SD-JWT framing, original encoded signing input and presentation verification.
- [SD-JWT VC draft 15](https://www.ietf.org/archive/id/draft-ietf-oauth-sd-jwt-vc-15.html), sections 3.2.1 and 3.2.2.4: `dc+sd-jwt` and the expressly supported no-disclosure case. This is a fixed work-in-progress revision selected by the project, not a final credential standard selected transitively by AP2.
- [Delegate SD-JWT draft 00](https://www.ietf.org/archive/id/draft-ietf-oauth-delegate-sd-jwt-00.html): examined to distinguish a one-item payload from delegated key-binding chains. Chain processing is excluded. AP2's mutable draft link now redirects to the OAuth working-group repository; its current contents must not silently become the AP2 release's normative revision.

No copied SDK implementation or general User Credential/Delegate SD-JWT claim is needed for this slice. A future implementation of those excluded paths requires separate standards pins, chain/disclosure/key-binding tests and design acceptance.

## Findings resolved in the final design

| ID | Finding | Accepted disposition |
| --- | --- | --- |
| AD-001 | A plain signed JSON mandate or arbitrary trusted public key is not native AP2 spending authority. | Use the strict SD-JWT VC TAP envelope, configured expected issuer and role-specific trust, and label the trusted-surface assertion synthetic. Reject other authority forms. |
| AD-002 | Parsed JSON equivalence does not preserve a checkout or receipt's cryptographic identity. | Verify original JWS components; hash the exact compact `checkout_jwt` field value and exact received `issuerJWT~` for the receipt. Document the schema-description ambiguity and test quotes, outer base64, reserialization and re-signing as different inputs. |
| AD-003 | Generic AP2 authorization receipts and type-specific receipt schemas use different result members. | Require both consistent `result` and `status`, retain all type-specific fields, forbid branch mixing, and use explicit synthetic PSP/network confirmations for synthetic success. Receipt signatures and signer roles are independently verified. |
| AD-004 | Two payment verifier roles can accidentally reject or reinterpret the same single-audience mandate. | Explicitly define the shared logical payment audience/challenge context for the synthetic CP/MPP domain, while preserving separate role trust and authorization consumption. This is profile configuration, not audience inference from the token. |
| AD-005 | Raw signature hashes are insufficient replay keys; ECDSA admits another valid signature representation. Unissued or unbound challenges also cannot establish freshness. | Register trusted challenges bound to issuer, audience and transaction before authorization; atomically consume nonce and issuer/JWT identity within the appropriate role namespace. Keep pure verification separate from authorization. No process-local guard may claim durable payment replay protection. |
| AD-006 | Successful parsing or local signatures can be overstated as provider interoperability or financial admission. | Isolate protocol code from PaymentIntent dispatch, keep synthetic credential bindings explicitly non-negotiable, and require independent cross-language artifact verification. Named-peer interoperability and all real credentials/providers remain open. |
| AD-007 | CP verification failures also require a signed AP2 Payment Receipt; assigning every payment receipt exclusively to the MPP omits that obligation. | CP signs only Payment Receipt Error under separately configured trust. Its required payment ID identifies the synthetic decision. CP-signed Success is rejected; MPP retains payment-success/error responsibility. |

## Implementation acceptance conditions

The later source review and executed evidence must establish the following; they are not assertions that tests already pass:

1. Strict framing rejects plain-JWT mandate substitution, extra tilde/JWS segments, noncanonical base64url, non-64-byte ES256 signatures, malformed UTF-8, duplicate JSON members, excessive depth/size and unsupported headers. Verification uses only configured P-256 keys, with no embedded or network-discovered key trust.
2. Issuer, key identifier, artifact role, exact audience, registered nonce, configured key validity/revocation and bounded authority lifetime are independent predicates. A merchant key cannot become a TAP or receipt key merely because a token names it.
3. Native closed mandate types and complete payment scope are checked against trusted expected inputs. Independently signed wrong payee, amount, currency, instrument or checkout digest must fail. A correct hash of an old checkout must fail against the merchant's current frozen bytes.
4. Merchant checkout signing uses randomized ES256. Two signatures over identical payload bytes produce distinct checkout identities. No verifier claims it can determine a remote signer's randomness policy from a single valid signature.
5. CP and MPP each perform verification with the documented shared payment audience and independently scoped role context. MPP also checks the full synthetic credential binding. Neither caller-supplied decoded claims nor the binding alone is proof of authorization.
6. Concurrent presentations of one registered authorization permit at most one success per role. Unissued, expired, mismatched-transaction and replayed challenges fail; failed content verification does not consume a valid authorization. A changed valid ES256 signature cannot bypass the identity guard.
7. Signed success and rejection receipts obey both AP2 result representations, exact mandate references, issuer roles and receipt freshness. Missing success IDs, success/error field mixing, swapped mandate references and wrong signer roles fail. CP rejection is verified only when explicitly expected; a CP-signed payment Success always fails. Error text is fixed and cannot reflect untrusted payloads.
8. Independent Python signatures are verified by Java and Java-emitted signatures are verified by Python. Semantic negative fixtures have valid signatures. Static expected outcomes must not be generated by the Java implementation under test. Public fixture artifacts may be retained; private keys remain transient and unlogged.
9. Synthetic exchange output contains safe summaries, hashes and opaque identifiers. Parser exceptions, object stringification and negative-test assertions must not print compact mandates, private keys or canary values. No protocol decision changes existing financial state or provider admission.
10. Documentation and evidence name the exact AP2/profile/standards revisions and retain every deferred capability. A passing local exchange is neither external interoperability nor AP2, provider, network, PCI or regulatory certification.

## Reviewed identities and decision

| Artifact | SHA-256 |
| --- | --- |
| `docs/adr/0005-independent-ap2-engine.md` | `1fe1530cad49d89b6a4293efb0e81b4bd1050dc82e375b8589d3e00643a59513` |
| `docs/ap2-requirements.md` | `90183f90f4440776fc50e2baed7239dd39ff89040fe2e67a5a6c2f3f91016b23` |

The reviewer read the final changed paragraphs and independently recomputed these identities. The excluded OpenID4VP endpoint's retrieval problem does not block this TAP-only implementation; it must stay recorded and cannot qualify support for an unimplemented presentation path. No application build or Maven execution was performed for this design review. Integration-owner acceptance is a separate decision in the gate record, followed by implementation and an independent source review.
