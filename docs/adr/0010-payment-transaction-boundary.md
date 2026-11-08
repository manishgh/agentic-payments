# ADR 0010: Extract execution-command policy from the transaction adapter

## Status

Proposed. This is a bounded follow-on to IMP-010 and [ADR 0008](0008-native-application-coordination.md). Implementation requires independent Astra design review and integration-owner acceptance. [ADR 0006](0006-durable-ap2-admission.md) remains authoritative for financial admission. No API, schema, dependency, native profile or admission capability changes are proposed.

## Context and scope

`NativeApplicationCoordinator` now owns artifact and protocol-verification policy independently of JDBC, but `JdbcPaymentStore.execute` still decides when an approved payment may acquire its one financial operation. It validates state/version and current grant facts, invokes native verification, binds the requested attempt, constructs the operation identity/digest and records PREPARE work, PREPARING state and the accepted response atomically. Those are application responsibilities rather than SQL row mapping.

Extract this one command first. Approval, issuance/provisioning, replay-identity consumption, reservation, provider dispatch admission, worker claim fences, fact ingestion, expiry, release and reconciliation remain in their current owners. In particular, this command schedules preparation; it does not admit or execute a charge. A full `PaymentStore` replacement, distributed transaction system, generic repository framework or worker rewrite is out of scope.

## Decision and ownership

Add an application-owned `ExecutePayment` use case and a responsibility-based command transaction contract. `JdbcPaymentStore.execute` becomes a compatibility entry point that delegates to the use case. `PaymentService`, existing constructors/composition roots and other `PaymentStore` operations retain their observable behavior.

| Application policy owns | Transaction/repository adapter owns |
| --- | --- |
| Role, command/version and existing adapter-capability checks in their current order. | Acquire the scoped run row lock, validate replay execution ownership and obtain the current persisted virtual time before exposing transaction facts. |
| Current merchant/object authorization before accepted-command lookup; scoped command identity and replay/conflict decisions. | Command serialization/digest compatibility, typed journal lookup and response serialization with unchanged stored JSON. |
| Expected version/state, current grant subject/merchant/validity/revocation/version checks and attempt association. | Typed reads of intent, attempt, checkout and grant, scoped to the locked tenant/run; SQL and result mapping. |
| Invoke the existing native re-verification responsibility at the same point; construct the unchanged `OperationBinding` and one operation/adapter identity. | Execute native verification through the existing transaction-bound implementation, including fresh enrollment snapshots and replay/challenge reads under the same lock. |
| Construct the typed preparation write and unchanged safe HTTP response; append its accepted-command result in the same transaction. | Persist financial operation, PREPARE outbox work, PREPARING state/version and audit; commit these and the command result together, or roll all back. |
| No provider calls and no new charge/reconciliation policy. | Retain existing rollback, SQL serialization/deadlock retries, uniqueness and unavailable-error translation. |

The application class imports application/domain/SDK contracts and Java values only; it imports no SQL, Jackson, infrastructure, AP2 implementation or concrete adapter type. Native protocol verification remains in `NativeApplicationCoordinator`; the transaction-native bridge does not copy that policy back into the new use case.

### Typed transaction seam

The command transaction responsibility executes bounded application work while retaining one transaction, lock and clock snapshot. Its callback receives a typed transaction scope, not a `Connection`, JDBC transaction handle, SQL map, arbitrary storage session, service locator or backend-specific type. The application work contains the decisions above; the adapter does not expose an `executePayment` method that runs the whole old command behind another name.

One atomic transaction responsibility owns the following typed operations. Its methods are not independently registered repositories, journals or verifier services:

```text
PaymentCommandTransactions.run(Session, PaymentCommandWork) -> Result
PaymentCommandTransactions.requestDigest(ExecuteCommand) -> RequestDigest
PaymentCommandWork.run(PaymentCommandTransaction) -> Result
PaymentCommandTransaction.now() -> Instant
PaymentCommandTransaction.intent(IntentId) -> ExecutionIntent
PaymentCommandTransaction.attempt(IntentId) -> ExecutionAttempt
PaymentCommandTransaction.checkout(ExecutionIntent) -> ExecutionCheckout
PaymentCommandTransaction.grant(GrantId) -> ExecutionGrant
PaymentCommandTransaction.accepted(CommandIdentity) -> Optional<AcceptedCommand>
PaymentCommandTransaction.verifyNative(ExecutionIntent, ExecutionAttempt, ExecutionGrant) -> void
PaymentCommandTransaction.prepare(PreparationWrite) -> void
PaymentCommandTransaction.accept(CommandIdentity, RequestDigest, Result) -> void
```

These are design shapes; exact Java nesting may be compact. The transaction's repository operations are deliberately execution-specific, with a single invariant-preserving write rather than table CRUD or arbitrary state setters. `PreparationWrite` names the authorized intent/expected version, existing attempt, new operation/adapter identity, request digest and PREPARE scheduling facts. It cannot write arbitrary states, release funds or mark admission. Accepted-command lookup and append belong to this same atomic owner; there are no independently committing journal/repository/native registrations. The pure request serializer is exposed on the transaction factory so the application computes its digest before acquiring the lock, as today. It remains adapter-owned to preserve the historic canonical Jackson representation and stored digest; it cannot invoke business work or access storage. Initially only the execute request shape is needed; extend the typed digest surface with subsequent reviewed command slices, rather than accepting arbitrary objects or inventing a new canonical format.

`ExecutionIntent`, `ExecutionAttempt`, `ExecutionCheckout` and `ExecutionGrant` are immutable application records containing the actual fields required by the current rules. Include scoped identifiers/associations, profile, merchant, state/version/revision/scenario, subject/grant/version, checkout digest/currency/exact minor units/credential reference, and current grant state/validity. No SQL names, raw JSON, signing material or mutable maps cross the boundary. Redact identifiers/credential-bearing facts in string representations where existing privacy rules require it. The native bridge reloads persisted evidence and binds resolvers at the existing phases; typed record construction must not pre-bind, cache or replace either resolver snapshot.

Transactions are synchronous and valid only during the callback. Implementations must invalidate use after completion and must not let callers independently commit, release the run lock, open nested transactions or retain a usable transaction handle. Reads, native bridge operations and writes all share the same transaction. The callback may be retried; it cannot perform externally visible effects beyond the owner's transactional writes and existing read-only immutable artifact/verification operations. There is no public HTTP endpoint accepting typed records, a `VerifiedAuthority`, boolean verification result or preparation write. Ports are trusted in-process composition registrations; records alone grant no authority.

The callback is a transaction-lifetime mechanism, not the business abstraction. Acceptance requires the same application use case to run against a deterministic non-JDBC implementation exercising its policy, and the PostgreSQL implementation to prove persistence guarantees separately. This seam is reusable by later command extraction through additional explicit responsibility views, rather than one interface per table or a generic dynamic command dispatcher.

### Exact executable sequence

Preserve the existing order and error precedence:

1. Require `execution:write`; reject null/invalid expected version; check the existing adapter capabilities. Validate the existing printable 16..128-character idempotency key and compute the unchanged execute-request digest.
2. Begin the run-locked transaction, check replay execution ownership and capture persisted virtual time. Read the requested intent and enforce current session merchant access, including the existing configured-merchant restriction. This happens even for a previously accepted command.
3. Read the journal by tenant/run/principal, exact existing route, intent resource and key. A different digest returns 409 `IDEMPOTENCY_CONFLICT`; a match returns the original safe status/body with `replayed=true` and performs no new policy verification or operation write.
4. On a miss, load the intent as currently done; require the expected version and APPROVED state. Load its attempt, current checkout and current grant in their existing order. Preserve existing missing-row/error behavior rather than broadening failure translation.
5. Preserve `authority` exactly: require grant subject equal the attempt subject and grant merchant equal the intent merchant, reject REVOKED, then enforce valid-from and expiry. Preserve `eligible` exactly: ACTIVE grant, grant version equal attempt grant version, current grant validity, grant subject equal attempt subject and grant merchant equal intent merchant. Do not add a new association rule or strengthen legacy policy during extraction. Run native re-verification for the persisted native profile. Only then compare the requested attempt ID with the authoritative attempt ID, returning the same 422 binding error on mismatch.
6. Generate a new operation UUID only inside the transactional work on a journal miss. Use it for both operation ID and adapter key, and calculate `OperationBinding` from the same tenant/run/merchant/intent/attempt/checkout/money/grant/subject/scenario/credential facts as today. A SQL transaction retry may create a different uncommitted UUID; no provider sees it and only the committed identity survives.
7. Persist the operation, enqueue PREPARE through the existing work-sequence allocation, change the intent to PREPARING with its version/audit behavior and build the unchanged 202 response. Append the command response and commit once. Every failure rolls back the complete unit.

Keep the current three-attempt SQLSTATE class-40 retry behavior, 23505 mapping to 409 `AUTHORITY_REPLAY`, 503 `TEMPORARILY_UNAVAILABLE` with `SAME_COMMAND`, and composition-error mapping. Do not catch arbitrary runtime failures and relabel them unavailable. Return a successful response only after commit; commit uncertainty retains the same command identity for recovery. Accepted replay is subject to current object authorization, but must not start applying current version/state/grant/native policy to historical accepted commands.

### Native and external boundaries

The typed native bridge invokes the existing read-only `verifyNative` path. Its capability check, current checkout revision, authority association/effective expiry, exact artifact reloads, digest checks, independently bound checkout/authority resolver snapshots, independent verifier, and replay/challenge ownership checks retain their existing order. It neither signs nor stages replacement mandates. The bridge does not expose a caller-supplied proof or an independently committing admission path.

Existing independent immutable artifact retrieval can still occur through the registered artifact contract at its established phases. This extraction does not authorize new external checkout/provider networking inside a financial transaction. The external ACP lifecycle adapter under separate design remains outside `JdbcPaymentStore`; remote freeze/version-CAS and completion/payment ownership need their own reviewed agreement before wiring. No remote response, checkout completion, provider preparation or payment fact can authorize `PreparationWrite` or a second operation by itself.

Worker PREPARE/DISPATCH gates and same-lock revocation/admission order are unchanged. ADMITTED/PENDING/UNKNOWN attempts continue reconciling the original operation; execution-command retries neither create another operation nor release held exposure. No timed lease, distributed ownership or external backend equivalence is claimed.

## Acceptance and tests

Application tests must demonstrate independent policy rather than merely count repository calls:

- Run `ExecutePayment` against a deterministic typed transaction/repository/journal implementation with no JDBC, native protocol engine or JSON mapper. A valid local-fake and native-profile command produces the same bound operation/preparation write and safe result. Independently vary state/version, grant scope/state/time/version, attempt ID and native-verification result; each rejects without writes and with the existing error precedence.
- Seed an accepted command and prove a matching replay returns its original result despite subsequent state/grant/native changes, while a lost current merchant permission rejects before journal replay. Conflicting input rejects without a new operation. A native verification outage retains `SAME_COMMAND` and emits no preparation write.
- Verify the exact operation digest against the established `OperationBinding` inputs, immutable values and privacy-safe string representations. The test adapter is policy evidence, not proof of database atomicity.

Real PostgreSQL tests must exercise the new seam through public `PaymentService` commands:

- Inject failure after operation/outbox/state/audit provisional writes and before accepted-command insert, plus failure at the command insert. Observe the provisional records on the active connection, then prove all writes/sequence changes roll back, the original reservation remains HELD and the same key can succeed after the fault clears. A post-commit retry/restarted store returns the persisted result with exactly one operation and PREPARE row.
- Race two same-key executions using separate connections and an actual run-lock barrier; both return one accepted identity/result, with one replay and no duplicate operation/outbox. Race different keys against the same expected version and prove exactly one success and the existing losing error. No sleep-based concurrency assertion.
- Race execute against grant revocation under the same run lock. If execute wins, revocation still prevents later DISPATCH admission; if revocation wins, execute rejects and creates no operation. Verify no provider execute call and retained/released exposure only according to the existing worker/revocation policy.
- Replay after removing current merchant access and after rebuilding the store, and compare historical execute-request digest/response JSON fixtures with the pre-extraction representation. Exercise existing REPLAY execution-owner rejection through the new transaction seam.
- Inject native artifact/crypto unavailability during execute: no operation/PREPARE/state/version/audit/command partial writes; reservation/budget remain unchanged. Clear the fault and retry the same key. Retain existing approval rollback, duplicate native replay, fresh trust snapshot, worker fence rollback and crypto/storage availability tests.
- Run the existing response-loss/UNKNOWN/reconciliation and process-crash coverage unchanged. Assert one operation/provider identity and held exposure until verified final/no-effect evidence. This regression evidence establishes the untouched worker boundary rather than claiming worker extraction.

Run the complete documented conformance command against isolated real PostgreSQL, documentation checks and `git diff --check` after implementation. Record new independent application and integration evidence. Existing certification exclusions remain.

## Consequences and alternatives

The slice provides a real application transaction seam and independently executable financial-command policy while leaving a manageable review surface. JDBC remains responsible for SQL, atomicity, native transaction integration and the unextracted use cases; this is substantive partial IMP-010 progress, not full transaction-layer replacement.

Moving SQL and its business branches into a class called a repository, accepting generic maps/callbacks that perform SQL, or wrapping the old whole command behind a new interface would not meet this decision. Separately committed operation/outbox/state/journal services would break atomic replay and are rejected. Extracting approval first would require a larger design for provisional attempt/authority/challenge writes and enrollment snapshot sequencing; extracting worker dispatch would add provider-effect and recovery risks. Those are subsequent slices after this seam has independent application and PostgreSQL evidence.
