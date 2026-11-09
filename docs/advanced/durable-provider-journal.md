# Durable synthetic provider journal

[Documentation](../README.md) / [Advanced](README.md)
`JdbcCompatibilityBindingJournal` implements the existing `CompatibilityBindingJournal` interface using PostgreSQL. It preserves complete provider operations, legacy mappings, submission ownership and evidence across process restarts. Applications can replace it with another backend implementing the same responsibility and behavioral guarantees. The portable SDK gains no SQL or infrastructure dependency.

This is the bounded synthetic compatibility profile from [ADR 0013](../adr/0013-durable-provider-journal.md). It composes the existing `LocalFakeExecutionAdapter` and durable `JdbcSimulatorAdapter`. An opt-in synthetic core-admission route is now available under [ADR 0020](../adr/0020-provider-journal-admission.md); the default HTTP composition and merchant checkout completion remain unchanged. The [provider execution specification](../../specification/provider-execution-1/README.md) remains the general adapter contract; this journal does not enable Stripe, Adyen or another vendor.

## Composition

Trusted application infrastructure constructs the configuration and injects the journal into the existing bridge:

```java
var configuration = new ProviderJournalConfiguration(
    tenant, merchant, run, binding,
    ProviderJournalConfiguration.MAPPING_POLICY_VERSION,
    "synthetic-scenario-policy", "1", operation -> "success");
CompatibilityBindingJournal journal =
    new JdbcCompatibilityBindingJournal(database, configuration);
var bridge = new LocalFakeExecutionAdapter(
    new JdbcSimulatorAdapter(database), journal, binding,
    tenant, merchant, run, observedAt, actionExpiresAt,
    configuration::scenarioFor);
```

The infrastructure classes live in `io.agenticpayments.infrastructure.provider`; the journal contract and bridge remain in `io.agenticpayments.sdk.execution.v1.compat`. `database` is an application-owned `DataSource`. Scope, binding, policy revisions and observation/action times come from trusted retained configuration. The example uses a synthetic success policy; a changed policy must not rebind an existing operation. The scenario resolver is pure and cannot perform provider I/O.

Supply a freshly allocated, dedicated synthetic scope and ensure this bridge exclusively owns its legacy keys. Preserve both compatibility and synthetic-peer histories through restoration. An empty or partially restored journal is not proof that an old provider operation never executed. This profile supplies no automatic adoption of existing legacy operations, reconstruction of missing ownership history or reset/purge operation.

Run identifiers in the new SDK are bounded text. The bridge maps them into a legacy UUID using the pinned mapping policy; the journal does not create a financial intent or simulation-run row to invent that relationship. Validate the complete retained mapping, not just a digest. Configuration, credentials, account and region are immutable bound references outside the scoped caller-key namespace. Changing any of them cannot escape a conflict by selecting a different key scope.

## Opt in to core admission

The `local-provider-journal-admission-1` route connects this journal to the existing AP2/ACP approval and financial-admission gates. Execution routing is independent of the authority profile. Trusted hosts explicitly compose it for a retained tenant, merchant and run:

```java
var binding = new ExecutionBinding("simulator", "account-ref", "eu-test",
    "config-ref", "1", "fixture-credential-1", "1", "platform-owner");
var registration = new SyntheticProviderRegistration(
    tenant, merchant, runUuid, binding, observedAt, actionExpiresAt);
PaymentService payments = PaymentServices.providerJournal(database, registration);
```

Keep those exact registration values across restarts. V011 records a complete core-to-provider binding and route in the same transaction that publishes the financial operation and PREPARE work. Existing operations receive a legacy-route marker. A worker with a missing or changed registration cannot adopt an operation, select another credential or fall back to the old executor. The approved checkout credential must match the pinned `fixture-credential-1` reference. Provider-side identifiers deliberately differ from core identifiers under the retained mapping policy; they must not be interchanged.

For custom composition, `PaymentServices.providerJournal(database, registration, preparer, submitter, reconciler, fencer, verifier, nativePorts)` accepts the same responsibility interfaces for direct implementations, external adapters and decorators. The factory assembles the route-aware facade and its verifier together. This synthetic profile declares atomic no-effect fencing, so a missing fencer is rejected before activity. The generic provider SDK still permits other profiles without that capability. `ProviderExecutionBindings` resolves retained mappings, and `ProviderEvidenceVerifier` retains/checks complete trusted-port observations and journal quarantine before projection; neither is a vendor-specific interface.

The bounded route supports effect-free preparation and synthetic success, delayed success, response loss and definitive no effect. Customer challenges, resource-creating preparation, provider authentication, external events, authorization and refunds are explicitly unsupported in this route; existing legacy challenge workflows remain available. The convenience helper explicitly progresses the synthetic peer's pending scenario on a scheduled reconciliation step. The advanced composition overload performs no automatic synthetic progression; manual reconciliation never progresses the peer.

Core admission atomically completes DISPATCH and publishes RECONCILE before submit can acquire the journal's permanent claim. No provider or journal call occurs inside that core transaction. A lost admission acknowledgement, a crash before journal claim, or a lost claim acknowledgement never permits a reconstructed send. Reconciliation uses the original retained operation; UNKNOWN keeps exposure HELD. An observation before the initial provider claim can permanently block that claim. Only a genuine provider no-effect fence can resolve an absent acceptance safely.

Provider evidence is projected through a named synthetic state mapping: PENDING has ordinal 1; CAPTURED and NO_EFFECT_FENCED have ordinal 2. These are semantic ordinals, not provider event sequences. Repeated observations of the same financial state have the same canonical core event, while full original evidence remains in the journal. UNKNOWN creates no financial fact. Journal conflicts freeze the core budget and retain disputed exposure, including after terminal settlement, without rewriting the settled state. Conflicts can commit in the journal before the core sees them; explicit reconciliation propagates quarantine. Retained terminal replay does not automatically discover every later peer contradiction.

Do not remove route, journal or peer history independently during restoration. There is no automatic migration of historical legacy operations into this route, claim reset, configuration rebinding or live-provider guarantee.

For durable synthetic customer authentication and signed event ingress, use the separate opt-in [interactions route](provider-authentication.md). Its offer and route are distinct; this guide's earlier admission profile and historical bindings remain unchanged.

## Ownership and evidence

Each journal call uses a short independent database transaction. Provider execution, lookup and genuine fencing occur after those transactions; the peer owns its separate durable journal.

| Operation | Durable behavior |
| --- | --- |
| `bind` | Insert or replay the identical complete operation, trusted policy and legacy mapping. Changed requests and operation/legacy aliases are rejected without rebinding a legitimate operation. |
| `claim` | Persist the complete submission association and grant one initial owner only when no previous claim or evidence exists. A committed claim is never reset or leased to a replacement worker. |
| `record` | Retain complete facts, preserve a conservative projection and commit conflicting evidence/quarantine before returning failure. |
| `read` | Return the retained projection for the exact binding, or a fixed conflict/unavailability result. It cannot hide a previously recorded conflict behind an older clean terminal fact. |

**Any prior evidence blocks a fresh initial owner, including `UNKNOWN`.** A fresh bind followed directly by claim is usable. A pre-submit reconciliation that records uncertainty may permanently sacrifice that path's liveness; it does not prove that an earlier sender existed, and it cannot prove that sending is safe. Callers must not use lookup absence, a timeout, process death or expired provider idempotency keys to work around this rule.

The concrete financial projection supports `UNKNOWN`, `PENDING`, `CAPTURED` and `NO_EFFECT_FENCED`. Later uncertainty cannot erase pending/captured history. Compatible duplicates preserve the original terminal projection. Pending/captured and a winning no-effect fence contradict in this synthetic profile. Unsupported authorization, definitive-refusal or financial-action observations are retained as violations and quarantined rather than mapped to invented success/failure meanings. Preparation's synthetic customer action remains separate from financial evidence.

Same evidence ID with any altered complete content is a conflict, even before a terminal state. The journal retains both sides without replacing the original row. Known provider-resource ownership cannot move to another operation or be erased by an absent reference. Once quarantined, binding replay, read, claim and record cannot return a clean outcome or fresh dispatch owner. No automatic conflict-clearing operation exists.

The journal accepts evidence from configured trusted ports; constructing an `ExecutionEvidence` value is not signature verification or financial authority. External verification and application admission remain distinct responsibilities.

## Recovery boundaries

| Interruption | Recovery of the original operation |
| --- | --- |
| Before bind commit | No new binding is published. Exact retry can bind normally. |
| After bind commit, before claim | Recover the complete mapping and claim it once. |
| After claim commit, before provider I/O | Retain ownership uncertainty; do not issue a replacement send. |
| After peer acceptance, before local evidence commit | Query the same mapped operation and retain its original result. |
| After terminal evidence commit, before response | Replay the original terminal evidence without executing again. |
| After peer fence, before local evidence commit | Read the original peer fact and record the genuine fence. |
| After conflict commit, before response | Reopen into the same quarantine, with both conflicting facts retained. |

A lost commit acknowledgment does not entitle the caller to assume it acquired ownership. Subsequent calls observe the committed claim or fail conservatively. The local claim does not stop an already admitted worker from calling the peer: only the peer's declared atomic acceptance-versus-seal guarantee can fence its financial effect. The tests distinguish another Java method invocation from another accepted synthetic charge. No distributed transaction across the two journals is assumed.

If storage is unavailable, it cannot promise to persist an additional error row. Previously committed claims and observations remain authoritative; failed writes cannot reset them. SQL/provider exception text and raw payloads are excluded from safe failures and receipts.

## Storage and codec

V009 adds scoped binding, evidence, conflict-receipt and provider-resource ownership tables, separate from financial state and signed AP2 artifact storage. Scoped operation IDs and complete legacy keys have unique owners. Rejection receipts can name an unbound requested namespace and an existing owner, allowing alias conflicts to commit without inventing a new binding. Identity/policy fields are insert-only through restricted SQL paths; submission associations are published once under the row lock. This is not protection against a database administrator altering rows directly.

`provider-compatibility-json-1` explicitly encodes complete values, null optionals, canonical numbers and exact nanosecond instants using the existing infrastructure JSON dependency. Strict decoding rejects duplicate/unknown/missing fields, invalid types, unsupported versions, oversized payloads and inconsistent fingerprints. It validates redundant storage columns against decoded identities. Corruption never defaults a claim to unclaimed or a missing fact to no effect. Credentials and private keys are not stored in these rows; bound references and full evidence still require protected database access.

Apply all migrations, including V009 and V011, with the standard migrator. It preserves all previous migrations and populated V008 state and requires no procedural SQL parser change. Keep unresolved bindings, claims and evidence through rollback/restoration; removing the new tables would erase essential history. Production retention, encryption, operational access and incident workflows remain separately reviewed deployment work.

## Validation scope

Independent PostgreSQL tests inspect both journals directly and exercise real connection contention, changed identities, aliases, write rollback, complete duplicate/conflicting facts, quarantine, scoped access and unrelated-row progress. Separate child JVMs stop at committed/uncommitted boundaries and fresh JVMs recover through new connections and adapter instances. A finite test cache may expire while the durable acceptance ledger stays intact; absent/unavailable lookup still cannot cause resubmission.

These tests establish the named synthetic process-recovery boundaries. They do not simulate database-server/power loss, a real vendor network or production operations. The additional core-integration tests establish only the named synthetic admission profile. The existing reusable provider TCK's memory fixtures remain labelled `IN_PROCESS_RECONSTRUCTION`; adding JDBC does not retroactively give every TCK case process-persistence evidence. See [verification guide](../reference/requirement-evidence.md) for obtaining source-bound results, and [the backlog](../project/backlog.md) for remaining provider profiles and production work.
