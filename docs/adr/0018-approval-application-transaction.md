# ADR 0018: Extract approval policy through the atomic payment transaction

## Status

Accepted for the bounded reference profiles described below. See the platform guides and executable tests for current implementation limits.

## Context

[ADR 0010](0010-payment-transaction-boundary.md) separated execute-command policy from its PostgreSQL transaction. Approval still combines current authorization, command replay, checkout/grant/budget decisions and response construction with SQL in `JdbcPaymentStore.approve`. Extracting those decisions makes approval independently executable through direct implementations, adapters and wrappers of the transaction contract.

Approval is larger than execution because its provisional attempt precedes native issuance, staged authority and challenges precede independent verification, and replay identities precede reservation. Its grant, replay and reservation operations cannot become independently committing services. [ADR 0008](0008-native-application-coordination.md) already establishes the native coordinator and trust-snapshot sequence; this decision preserves that boundary.

The slice covers existing local-fake, native AP2/ACP and opt-in external-checkout synthetic approval. It adds no profile, wire field, schema, migration or dependency. Provider-journal wiring, worker dispatch/lease/fencing changes, remote financial coordination, broader instruments/delegation and full `PaymentStore` replacement remain out of scope.

## Decision

Add application-owned `ApprovePayment`. Keep `PaymentStore.approve`, `PaymentService`, existing constructors and registered service decorators compatible; the JDBC entry point delegates to the use case. The application imports application/domain/SDK contracts and Java values only, with no JDBC, Jackson, infrastructure or native implementation dependency.

Extend the existing `PaymentCommandTransactions` responsibility with an explicit approval transaction view and a separately named typed `approvalRequestDigest` method. Keeping the execute digest method unambiguous preserves existing source calls, including `requestDigest(null)`. Execution retains its existing view and behavior. The approval view belongs to the same atomic owner; its operations are not independently registered repositories or grant/replay services.

Existing execute-only implementations may retain source compatibility through default approval digest and transaction-entry methods that fail explicitly with `UNSUPPORTED_CAPABILITY` before invoking work or touching storage. There is no generic fallback or partial callback; supporting execution does not imply approval capability.

| Application policy owns | Transaction adapter owns |
| --- | --- |
| Role, expected-version, allowed-grant and adapter-capability checks in their existing order. | Historic canonical request serialization/digest and persisted response JSON. |
| Current merchant authorization before accepted-command lookup, command conflict/replay decisions. | Scoped run lock, replay-owner check, persisted virtual time, typed journal reads and decoding after digest comparison. |
| Intent state/version, grant subject/merchant/revocation/time, quote expiry/revision/digest and budget currency/frozen/exposure policy. | Typed reads scoped to tenant/run and row mapping with existing missing-row failures. |
| One attempt identity on a journal miss; ordered attempt staging, native bridge and final reservation/state write. | Provisional attempt, transaction-bound native issuance/verification integration, replay identities, reservation/held/state/audit and journal writes under one lock and commit. |
| Unchanged safe approval response and acceptance. | Transaction lifetime, rollback, retry and established error translation. |

### Typed approval transaction view

The intended operations are shown below; Java nesting may remain compact and reuse existing command identity/journal values.

```text
PaymentCommandTransactions.approvalRequestDigest(ApproveCommand) -> RequestDigest
PaymentCommandTransactions.runApproval(Session, ApprovalWork) -> Result
ApprovalWork.run(ApprovalTransaction) -> Result
ApprovalTransaction.now() -> Instant
ApprovalTransaction.intent(IntentId) -> ApprovalIntent
ApprovalTransaction.checkout(ApprovalIntent) -> ApprovalCheckout
ApprovalTransaction.grant(GrantId) -> ApprovalGrant
ApprovalTransaction.budget(BudgetId) -> ApprovalBudget
ApprovalTransaction.accepted(CommandIdentity) -> Optional<AcceptedCommand>
ApprovalTransaction.replay(AcceptedCommand) -> Result
ApprovalTransaction.stageAttempt(ApprovalWrite) -> void
ApprovalTransaction.issueAndVerifyNative(ApprovalIntent, ApprovalCheckout, ApprovalGrant, ApprovalWrite) -> void
ApprovalTransaction.reserveAndApprove(ApprovalWrite) -> void
ApprovalTransaction.accept(CommandIdentity, RequestDigest, Result) -> void
```

`ApprovalIntent`, `ApprovalCheckout`, `ApprovalGrant` and `ApprovalBudget` are immutable application facts. They carry the identifiers/associations and current profile, state/version, revision/digest, currency/exact minor units/expiry, grant subject/merchant/state/version/validity/budget and budget frozen/held/consumed/limit values needed by approval. Include only facts needed by policy and binding; do not pass SQL maps, serialized native artifacts, signing identities, private material or generic storage sessions. Sensitive string representations remain redacted.

`ApprovalWrite` binds the authoritative intent/version, new attempt, reviewed checkout, grant/version, subject and reservation budget/amount. The adapter binds writes to facts read within that transaction and to the same staged attempt; native finalization requires successful native bridge completion for that attempt. A supplied record, boolean or `VerifiedAuthority` cannot independently bypass native verification. The final write only creates the HELD reservation, increases held exposure and changes/audits APPROVED; it cannot choose arbitrary state, create an operation, schedule PREPARE, release funds or admit a charge.

The native bridge preserves the existing `approveNative` transaction integration and delegates artifact/protocol policy to `NativeApplicationCoordinator`. It is an explicit remaining infrastructure-bound orchestration seam, not a claim that native issuance/provisioning orchestration has been fully extracted. It does not hide the whole approval command: merchant/grant/quote/budget policy, journal decisions and response construction are independently run by `ApprovePayment`. A future extraction of native transaction phases requires its own bounded review.

All scope operations reject use after callback completion or from another thread. Nested transaction entry is rejected; execution and approval views on the same owner must not permit cross-view nesting to bypass this restriction. Callers cannot commit, unlock or retain a usable scope. Direct implementations, external adapters and delegating wrappers implement this same contract without changing application callers. They must preserve atomicity, snapshot sequencing, retry and lifetime guarantees; interface compatibility alone does not qualify a distributed backend.

The callback may be retried on the existing SQL serialization/deadlock rules. Approval's established signing and immutable artifact staging can repeat and leave unreferenced immutable artifacts after rollback; those artifacts grant no authority. No new externally visible payment/provider or checkout-completion effect is allowed. Do not describe approval callbacks as entirely side-effect-free or promise transactional rollback of independent artifact storage.

### Exact approval sequence and compatibility

1. Require `approval:write`; validate non-null command/positive expected version; enforce the session's allowed-grant set; check existing synthetic adapter capabilities. Preserve their precedence even for accepted retries. Form the existing intent resource and route `POST /intents/{id}/approve`, validate the printable 16..128-character key, and compute the unchanged Jackson request digest before acquiring the lock.
2. Acquire the tenant/run lock, check replay execution ownership and capture persisted virtual time. Load the intent and enforce current allowed-merchant/configured-merchant restrictions before journal lookup.
3. Look up tenant/run/principal/route/resource/key. On a digest conflict return `IDEMPOTENCY_CONFLICT` before response decoding. On a match decode and return the original status/body with `replayed=true`. Current role, allowed-grant, capabilities and object access still apply; current state/version/grant validity/quote/native policy do not rerun for a matching accepted command.
4. On a miss, load/authorize the intent as before, compare expected version, require QUOTED, then load current quote followed by the requested grant. Check grant subject equals current principal and merchant equals intent merchant; reject REVOKED, then check valid-from/expiry against transaction time. Approval currently does not require ACTIVE or apply execute's grant-version eligibility rule; preserve that behavior.
5. Check quote expiry, then reviewed revision and digest, then load the grant's budget. Check budget currency, then frozen or `held + consumed + total > limit`. Preserve existing exact minor-unit values, comparison and failure precedence; arithmetic hardening or policy changes are separate work.
6. Create a new attempt UUID only on a journal miss, stage the attempt with current grant version, principal and `simulator-core` owner, then invoke the native bridge only for either existing native profile. No reservation or APPROVED state is written before that bridge succeeds.
7. Create one HELD reservation, increase budget held, transition/audit APPROVED and construct the existing 200 response. Preserve `contractVersion`, correlation identity, intent/state/version/attempt, `executionOwner`, `authorityProfile`, `authorityVersion` and reserved money including string-form minor units. Persist the accepted result in the same transaction and return only after commit.

Historic request JSON/property order/digest and safe response JSON/types are unchanged. Existing accepted responses remain readable after restart. SQLSTATE class-40 retains three attempts, 23505 retains `AUTHORITY_REPLAY`, other SQL unavailability retains `TEMPORARILY_UNAVAILABLE`/`SAME_COMMAND`, and existing composition errors retain their exact mapping. Runtime/encoding failures are not broadly relabeled unavailable. A lost commit response is recovered using the same command identity.

### Native bridge invariant

Retain the executable native order rather than substituting signing success for authority:

1. After provisional attempt insertion, check native capabilities, reload current persisted checkout evidence/digest and exact artifact bytes; for the external profile revalidate the protected observation/binding through the existing registration.
2. Bind the pre-provision enrollment snapshot and validate checkout. Only then provision and validate/persist enrollment, preserving trusted resolver enforcement of scope/time, enrolled-key equality, revocation and validity.
3. Build the existing authority context with current subject/grant/version, trusted signer, synthetic audiences, fresh checkout/payment nonces, transaction time and `min(now + 300 seconds, grant expiry, checkout expiry)`. Issue and retain Checkout Mandate then Payment Mandate and calculate the unchanged reference-pair digest.
4. Insert authority metadata and all three provisional challenges. Reload exact Checkout Mandate then Payment Mandate bytes. Only afterward bind a fresh enrollment snapshot, independently verify, and enforce existing issuer/role/hash/expiry policy in `NativeApplicationCoordinator`.
5. Insert verified replay identities with their current tenant/role/issuer/JTI uniqueness and run/attempt association, then persist verified effective expiry. Only then finalize reservation/held/state/audit/journal.

SQL failures or authority/signing/crypto/artifact failures roll back attempt, enrollment, authority, challenge, replay, reservation, budget, state, audit and command mutations together. Existing immutable artifacts may remain unreferenced. No public API accepts these transaction facts or intermediate proof values. Raw mandates, checkout bytes and signing material remain excluded from safe responses, telemetry and workflow state.

## Acceptance and tests

Application evidence must run `ApprovePayment` without JDBC, a native protocol implementation or a JSON mapper:

- A deterministic typed transaction implementation exercises local-fake and both native-profile branches, producing a correctly bound attempt/reservation and unchanged safe result. Independently vary state/version, grant scope/revocation/time, quote expiry/revision/digest, and budget currency/frozen/held/consumed/limit; assert current failures and competing-error precedence before any staged attempt.
- A seeded accepted result replays despite later state/expiry/budget/native changes, but current role/grant allowlist/capability/merchant access still rejects in its current position. A conflict never decodes the historical response or issues new authority. Native failure cannot finalize reservation or accept the command.
- Exercise a direct implementation, an adapter translating a differently shaped backend/interface, and delegating wrappers through the same use case with identical success/replay/failure outcomes. These must preserve one transaction and failure identity, rather than be aliases of one double. The doubles demonstrate policy/substitutability, not SQL rollback. Check scope expiry/thread/nesting misuse and redacted facts against the responsible implementation; execute-only defaults reject before callback/storage effects.

Real PostgreSQL evidence must invoke approval through public service composition:

- Inject a failure at command insertion after witnessing provisional attempt/native rows/replay/reservation/budget/state/audit on the active connection. Prove complete rollback including audit sequence and that a same-key retry succeeds. Retain staged-artifact/independent-verifier failure after provisional authority/challenge tests; leftover artifacts never authorize execution.
- Use separate connections and an actual run-lock barrier to race identical keys: exactly one attempt, reservation, native replay set and held increment survive; both return the same original result and one is replayed. Race distinct keys against one expected version and confirm one success and the existing loser. Retain competing-intent budget and cross-run replay-identity races; do not rely on sleeps.
- Rebuild services and replay the same key without reissuing authority or reserving again, including after expiry. Revoke current merchant/grant access and prove authorization precedes replay. Compare hard-coded historic approval request/digest and response representations, and retain native checkout/authority stored-JSON compatibility fixtures.
- Preserve old merchant snapshot before provisioning and fresh TAP snapshot after enrollment, authoritative resolver substitution/revocation rejection, replay-owner rejection, and external protected-observation binding. No external checkout network completion is introduced.
- Preserve same-lock revocation behavior: revocation before a fresh approval rejects without reservation; approval before revocation leaves the existing revocation/release/dispatch policy in force. Retain execution, worker admission/fence rollback, unavailable-native-gate, UNKNOWN/reconciliation and crash/restart regressions without another provider identity.

Run the complete documented conformance command against isolated real PostgreSQL, documentation checks and `git diff --check`. No embedded substitute or omitted integration check proves this boundary. Certification and interoperability exclusions remain unchanged.

## Consequences and alternatives

Approval policy becomes independently testable and replaceable through a typed atomic owner while keeping one transaction for replay, grant facts, verified native authority and reservation. The explicit staged-attempt/native/final-write phases are necessary to retain current security and error order. JDBC still integrates native transaction phases and owns unextracted commands and workers; this is another bounded IMP-010 slice.

Moving the old whole command behind an `approve` repository method would leave the application policy in infrastructure. Splitting grant, replay, authority and reservation into independently committing backends would weaken the invariant. Moving native issuance outside the lock, prebinding one resolver or returning a reusable verification proof would change authority/trust semantics. A simultaneous provider-journal/worker rewrite would enlarge the payment-effect/recovery review surface. These alternatives are not part of this decision.
