# ADR 0021: Durable provider authentication and verified events

Status: accepted for the opt-in synthetic `local-provider-interactions-1` route.

## Decision

Compose customer authentication and external event processing through independently injected responsibilities. Preserve the original AP2/ACP authority, financial operation, provider selection and permanent submission claim. Historical legacy and provider-journal routes retain their original behavior and offer digests; changing a host configuration cannot adopt an existing operation into this route.

Preparation carries an exact opaque provider action through `PaymentPreparation` into the core transaction. `ProviderActionRegistry` durably binds its complete operation, reference, type and expiry before the core publishes WAITING_ACTION. The core retains the same association and caps its deadline at the earlier of the provider expiry and its local action deadline. Repeated preparation cannot replace the descriptor.

`CustomerActionTransactions` reserves the exact principal, action, command key, version, decision and immutable provider binding before an external interaction. A different request cannot occupy that action's reservation. `SyntheticAuthenticationDecisions` is expressly a synthetic interaction issuer; `ProviderAuthenticationResolver` separately resolves a retained authentic result. A UI decision or caller-created observation is not a general provider-authentication proof. The completion coordinator performs external work outside the core transaction, then rechecks the original reservation, authenticated subject, authority, expiry, action state/version and binding before atomically recording the response and READY/DISPATCH or denial. Admission independently requires the retained verified authentication association and the existing AP2/grant/reservation gates. Authentication does not enter the financial evidence journal.

`ProviderEventIngress` retains bounded exact bytes and signature metadata under the expected original operation. `ProviderEventVerifier` authenticates them before decoding; `ProviderEventNormalizer` resolves durable verified provenance rather than trusting a constructed Java reference. The synthetic profile uses a configured HMAC-SHA256 key and strict body schema. The key and policy references/versions are retained; payloads never select algorithms or keys. Account, operation, binding, resource, money and original financial meaning must match before an event can enter the journal.

Authenticated event identity is scoped independently of mutable configuration/key revisions. Identical events replay their original meaning. Authentic conflicting reuse retains both sides and quarantines all affected owners; unauthenticated or misbound input cannot poison a legitimate payment. `ProviderEventHealth` gates preparation, submission, reconciliation, fencing and evidence verification. Quarantine is a dispute barrier, not a provider no-effect fence against an already in-flight sender.

Normalized events pass through the existing complete journal conflict matrix and canonical core projection. UNKNOWN does not fabricate a financial fact; late PENDING cannot erase CAPTURED. Events never create an operation, admit a payment or submit money. The application only accepts events for an already admitted operation in an authorized scope. Core conflicts freeze the budget and retain disputed exposure even after settlement without rewriting terminal truth.

## Storage and recovery

V012 adds the independent authentication ledger, V013 the protected event inbox/verified identities/conflicts/quarantine, and V014 core action associations and reserved commands plus the new route marker. Prior migration bytes and populated financial/provider history remain unchanged. These stores have separate commits; no distributed transaction is implied.

A lost authentication response resumes the same reserved request and resolves its retained result. Expired or revoked core authority still prevents readiness/admission. Explicit event replay by retained envelope reference closes inbox, journal and core commit gaps without resubmission. A conflict committed outside the core may require a later replay/reconciliation to propagate core quarantine. There is no autonomous delivery scheduler or automatic conflict clearing in this slice.

## Alternatives and limits

Reusing legacy UI approval as provider proof would bypass the provider boundary. Applying webhook-shaped DTOs directly would bypass authenticity and durable provenance. Widening the old offer would silently change retained selection identity. These approaches are rejected.

Direct implementations, adapters and wrappers use the same responsibility interfaces and must preserve their behavioral guarantees. Only the explicitly composed synthetic challenge and signed synthetic event profile is implemented. Resource-creating preparation, post-submission authentication, real 3DS/redirect/SDK handoff, vendor transports, refunds, automatic event delivery and production key/retention operations remain future profiles. Existing default HTTP/UI composition is unchanged. Tests establish named synthetic boundaries, not provider or production certification.
