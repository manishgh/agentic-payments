# Durable AP2 authority and embedded ACP checkout

The explicit `local-ap2-acp-single-tender-0.1` profile connects the independently authored AP2 direct TAP engine, an embedded ACP checkout, and durable financial admission. All identities, instruments and provider effects remain synthetic. The existing `local-fake-single-tender-0.1` profile remains available with its original semantics.

The native authority profile is `ap2-v0.2-direct-tap-synthetic-0.1`; checkout uses the pinned ACP `2026-04-17` schema. This is a bounded in-process composition, not a complete ACP merchant HTTP server, real provider connector or broad AP2 compatibility claim. [ADR 0006](adr/0006-durable-ap2-admission.md) defines the reviewed admission design.

## Purchase and recovery

1. Create an intent with the native profile. Capability negotiation rejects incompatible implementations before financial work.
2. Quote the server-owned synthetic cart. Freeze the exact ACP payload in a signed merchant checkout, and persist immutable artifact references. The public checkout digest identifies the exact signed checkout bytes.
3. The trusted UI approves that digest/revision. The server issues synthetic mandates; independent verification checks the checkout, signatures, parties, challenges, grant version and deadlines.
4. One PostgreSQL transaction consumes replay identities, binds verified authority to the attempt, reserves budget and saves the accepted command result. A failed transaction cannot consume authority without creating its corresponding admission records.
5. Execution rechecks current authority and exact evidence before preparation and dispatch. A challenge is completed only through the trusted UI; a graph wake cannot approve or authenticate a purchase.
6. After financial admission, uncertain outcomes retain their original operation and exposure. Expiry or revocation does not erase an admitted payment or permit a replacement charge. Reconciliation determines its outcome.

Identical accepted command retries return their stored result without signing new authority. Verification after service restart uses persisted public enrollment and original artifacts; private signing keys are ephemeral in the reference adapter. A fresh quote/approval after restart can use newly provisioned identities. The synthetic virtual clock is separate from wall-clock workflow timers.

## Replaceable responsibilities

The [composition contracts](../src/main/java/io/agenticpayments/composition/Composition.java) are registered through `Composition.Ports` and injected into `PaymentServices.local(...)`. A component may implement the port directly, adapt an existing service, or decorate/delegate to another implementation.

| Responsibility | Stable boundary | Reference implementation |
| --- | --- | --- |
| Frozen merchant checkout | `CheckoutProtocol` | Native ACP payload and signed merchant checkout |
| Authority issuance | `AuthorityIssuer` | Synthetic direct TAP mandates |
| Authority verification | `AuthorityVerifier` | Independently authored AP2 engine |
| Signing and verification primitive | `SignatureProvider` | JCA ES256 implementation |
| Key provisioning | `KeyProvisioner` | Synthetic ephemeral private keys and durable public enrollments |
| Enrolled key resolution | `KeyResolverFactory` / `KeyResolver` | Resolver bound to the current immutable enrollment snapshot |
| Immutable signed artifacts | `SignedArtifactStore` | PostgreSQL, with an in-memory contract-test implementation |
| Financial admission | `PaymentStore` | PostgreSQL atomic grant/replay/reservation coordination |
| Workflow scheduling | `WorkflowRuntime` | Local SQLite and Temporal adapters; see [runtime guide](durable-runtime.md) |

Descriptors declare contract versions, implemented profiles, capabilities and responsibility owners. Registration does not confer support automatically: the core checks required capabilities and owner consistency. A different crypto implementation can replace JCA, but this profile explicitly requires ES256 and ephemeral private-key provisioning. A KMS or long-lived enrollment service needs a compatible ephemeral mode or a separately reviewed profile/capability change. Supporting another algorithm requires a reviewed profile and trusted policy; untrusted algorithm headers never select a fallback.

Native authority lasts at most 300 seconds from trusted virtual approval time, shortened by grant and checkout expiry. Current key validity and revocation are checked separately. These restrictions belong to this bounded profile, not to generic storage or crypto interfaces.

Key resolution is injectable without exposing JDBC through the public interface. The core supplies an immutable enrollment snapshot taken under the transaction's run lock and checks the returned key against it. A resolver cannot substitute another scope, key, version or revoked enrollment.

Artifact references are content addressed: `ArtifactReference.id` is the canonical digest identity required by the port. Cloud object IDs or vendor record identifiers stay inside the adapter. Both adapter and admission boundary verify the original bytes and reference metadata; signed text must decode as strict UTF-8. Artifact staging uses a separate immutable write and can leave harmless unreferenced artifacts after failed admission. It is not a distributed financial transaction.

Azure Storage, DynamoDB or an existing grant service can implement the same artifact-storage responsibility without introducing new vendor-specific interfaces. A service used for authoritative grant consumption must separately satisfy the admission contract's atomic replay, grant and reservation guarantees. A generic CRUD store does not qualify. No cloud backend adapter is included yet.

## Application coordination and transaction ownership

`NativeApplicationCoordinator` owns the native artifact and verification policy through the existing composition ports. It stages and reloads exact checkout/mandate bytes, checks artifact identity and strict UTF-8, compares stored digests and validates the verifier's role/expiry output. It has no JDBC, JSON codec, HTTP or workflow dependency. Replacing or decorating a protocol or artifact implementation still uses `Composition.Ports`; this extraction adds no backend-specific interface.

`JdbcPaymentStore` calls those application phases while retaining the transaction and run lock. It owns trusted enrollment, current resolver snapshots, command-journal persistence, challenge/replay uniqueness and atomic grant/reservation/admission records. Approval first verifies the existing checkout, then provisions enrollment, stages authority and inserts provisional records. Only afterward does it reload the mandates and bind a fresh resolver for independent verification. A failed verification rolls back financial mutations; independently staged, unreferenced bytes cannot authorize a payment.

The stored checkout/authority JSON shapes and public API remain unchanged. Accepted retries reuse their command result; execution reloads retained authority without signing again. Already admitted UNKNOWN recovery still reconciles the original operation. [ADR 0008](adr/0008-native-application-coordination.md) records the exact phase order and compatibility requirements.

`ApprovePayment` now owns approval authorization, command replay, checkout/grant/budget policy and response construction. `ExecutePayment` owns execution-command policy. Both accept a `PaymentCommandTransactions` implementation and a trusted adapter-capability supplier through constructor injection. Direct implementations, adapters to another transaction API and delegating wrappers use the same application callers. Independent non-JDBC approval tests exercise each form; execution retains its independent application tests. PostgreSQL tests separately establish persistence and locking guarantees.

The contract has execution and approval views on one atomic owner. `runApproval(session, work)` supplies typed facts and three ordered writes: stage the attempt, issue and independently verify native authority where required, then reserve and mark APPROVED. Accepted-command persistence belongs to that same transaction. No callback receives SQL, a database connection or a separately committing grant/reservation service. The native bridge still integrates provisioning, enrollment snapshots, challenges and replay identities with the existing coordinator under the lock; this portion has not been extracted into a new backend.

Scopes are synchronous, thread-confined and invalid after the callback returns. Nested calls across either view are rejected. Serialization retries may repeat signing and immutable artifact staging, leaving unreferenced artifacts, but never authorize another financial effect. A lost commit acknowledgement is resolved with the original command key. Matching historical approval replays retain their original response even after expiry; current role, allowed-grant, capability and merchant checks still apply before replay.

Existing execute-only transaction implementations remain source-compatible: the added approval entry methods default to typed `UNSUPPORTED_CAPABILITY`, without invoking approval work. Implement approval explicitly to enable it; a forwarding wrapper must delegate both its digest and transaction entry methods. Typed write records alone confer no authority, and implementing the interface does not establish distributed atomicity. See [ADR 0018](adr/0018-approval-application-transaction.md) for the contract and acceptance obligations.

### Worker coordination and recovery

`RunPaymentWork` accepts `PaymentWorkerTransactions`, the existing payment adapter, a `PaymentFactVerifier` and `SyntheticPendingProgression` through constructor injection. Each responsibility can be implemented directly, adapted or decorated. The application coordinates preparation, admission, lookup and uncertainty; the transaction owner preserves indivisible state/outbox/reservation changes. See [ADR 0019](adr/0019-worker-transaction-ownership.md) for the versioned behavioral contract and exact ordering.

A successful admission commit permanently completes DISPATCH and publishes RECONCILE before returning the original operation for one provider call. A crash or ambiguous commit acknowledgement does not authorize another send. Recovery looks up that same operation and retains HELD exposure until verified financial evidence or a genuine provider no-effect seal resolves it. The local simulator supplies that seal; arbitrary external adapters cannot assume the same guarantee.

Outbox generations are claim counters, not timed leases. Preparation completion and missing/unavailable reconciliation observations must still own their claim. Manual reconciliation also claims the existing RECONCILE generation, so it can supersede an older scheduled observation without creating new work. Uncertainty can change ADMITTED to UNKNOWN but cannot erase PENDING or a terminal result. Authentic late facts still pass verification and the existing conflict/settlement rules regardless of claim age.

Worker, approval and execution callbacks share the reference owner's nesting guard and reject use after return or on another thread. Transaction callbacks never call provider preparation, submission, lookup, verification or progression. Wrappers must preserve the commit-before-send boundary and must not retry an admitted send. Worker receipts retain the historical selected identity, counter and committed activity range; concurrent activity may contribute to that range.

The default infrastructure wiring retains the exact simulator's event-history verification and explicit scheduled pending progression. Other legacy adapters retain lookup/equality verification and no synthetic progression. Hosts retaining JDBC can use `PaymentServices.local(dataSource, adapter, ports, verifier, progression)` to inject these collaborators. Manual reconciliation never invokes synthetic progression. Further extraction of the existing expiry, native and evidence-settlement phases remains separate work, as do timed leases and provider-journal admission wiring.

## Run the native reference

Start the platform and load the private graph session as described in the [README](../README.md). Select the native profile explicitly when starting a new workflow:

```powershell
.venv/Scripts/python.exe -m workflows.payment_workflow start --workflow-id $workflowId --scenario challenge-then-success --profile local-ap2-acp-single-tender-0.1
```

Retain the original run/workflow IDs. Approve the returned intent in the trusted UI and resume the same workflow with the same native profile. Complete any synthetic challenge in that UI, then resume again:

```powershell
.venv/Scripts/python.exe -m workflows.payment_workflow resume --workflow-id $workflowId --profile local-ap2-acp-single-tender-0.1
.venv/Scripts/python.exe -m workflows.payment_workflow status --workflow-id $workflowId --profile local-ap2-acp-single-tender-0.1
```

For automatic durable timers and activity retries, follow the [Temporal runtime guide](durable-runtime.md).

The graph sees validated status and artifact digests, not raw signed authority, private keys or authentication secrets. The public approval route accepts the expected digest/revision and never accepts caller-supplied mandates. Requoting before approval invalidates an earlier reviewed digest; approved attempts cannot be requoted. Replayed issuer/mandate identity fails with `409 AUTHORITY_REPLAY` and `NEVER` retry advice; ordinary command idempotency remains separate.

## Storage and evidence

Migration `V007` adds the explicit profile and native checkout, public trust, challenge, authority, replay and artifact records. Existing intents receive the legacy profile; earlier migrations and their checksums remain unchanged. A real PostgreSQL upgrade test preserves historical attempt, operation and reservation records across migration and repeated startup. Apply migrations through the existing checksum-verifying migrator; no destructive downgrade is supplied.

Verification includes concurrent approval/replay races, exact expiry, key revocation/admission ordering, SQL rollback, process restart, malformed artifacts, replacement stores/resolvers and owner conflicts. Java-generated native checkout is independently validated against the pinned ACP schema during the aggregate run. Temporal tests separately cover authentication pause/restart and response-loss recovery; they do not claim those two scenarios were combined into one test.

These checks do not close the full certification matrix. Real PSPs, externally hosted checkout interoperability, delegated/User Credential AP2 profiles, additional crypto profiles, real 3DS and refunds remain separately reviewed work.

The optional [external authority binding profile](external-checkout-authority.md) now joins retained observations to synthetic AP2 approval and financial admission through a permanent atomic seal. It requires separate trusted opt-in, leaves the commerce-only profile unchanged, and does not enable vendor execution or merchant checkout completion.
