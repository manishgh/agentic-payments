# Durable synthetic provider events

[Documentation](../README.md) / [Advanced](README.md)
The opt-in `local-provider-interactions-1` execution route adds protected event retention, explicit replay and opt-in automatic delivery to the [provider journal](durable-provider-journal.md). It is a local synthetic profile. Its optional webhook transport is loopback-only; it does not implement a vendor signature profile, public listener or live-provider authenticity claim. Existing `local-provider-journal-admission-1` operations keep their original offer and route.

## Responsibility contracts

`ProviderEventOperations` exposes two authenticated application commands: `retain(session, intentId, rawBody, signature)` returns an opaque envelope reference; `replay(session, intentId, envelopeReference)` processes that retained envelope. Both require `runner:write`, the retained tenant/run scope, merchant permission and an already admitted financial operation before touching the event inbox. Retention alone does not verify authenticity or authorize a financial transition. A retained reference is not a public bearer credential.

| Contract | Responsibility |
| --- | --- |
| `ProviderEventIngress` | Protect exact bounded bytes and signature metadata under the complete expected operation. |
| `ProviderEventAuthenticator` | Authenticate the exact bytes under trusted key/policy configuration before decoding. |
| `ProviderEventDecoder` | Interpret authenticated bytes under the pinned profile, checking full operation, account, resource and money binding. |
| SDK `ProviderEventVerifier` | Resolve a retained envelope, authenticate/decode it, and durably establish event identity and provenance. |
| SDK `ProviderEventNormalizer` | Reload the verified provenance and return that event's original financial meaning. Constructing a Java reference cannot establish provenance. |
| `ProviderEventHealth` | Reject activity for an operation with a retained authenticated event dispute. |
| `ProviderEventProcessing` | Compose the configured verification, normalization, provider journal and canonical core projection. |

These interfaces support direct implementations, external adapters and forwarding decorators. An implementation must preserve their security and transaction behavior; matching method signatures is insufficient. The JDBC inbox, HMAC authenticator and synthetic decoder are reference implementations, not required backends. The host supplies the original immutable event policy and key references/versions across restart. A missing or changed original configuration fails closed.

## Signed synthetic profile

`synthetic-provider-event-1` signs the exact raw body with HMAC-SHA256. The tag is exactly 64 lowercase hexadecimal characters. The secret is supplied by trusted host configuration; the body cannot choose a key or algorithm. The reference composition exposes no signing endpoint. Keys and raw event bodies must stay outside model context, public status, workflow state and logs.

The body is a nonempty JSON object of at most 16 KiB, containing exactly `version`, `eventId`, `operationFingerprint`, `bindingDigest`, `accountRef`, `currency`, `exponent`, `minorUnits`, `providerResourceRef`, `observedAt` and `state`. Duplicate, unknown and missing fields, trailing tokens and invalid types are rejected. Minor units are canonical positive integer text matching the complete retained operation; the exponent is an integer. Resource identity must equal the configured synthetic peer mapping. The operation fingerprint binds its full scope, identities, selection, money and purpose. Only `CHARGE` with `UNKNOWN`, `PENDING`, `CAPTURED` or `NO_EFFECT_FENCED` is supported.

Authenticity is checked before the JSON decoder runs. A signed but wrong-account, wrong-operation, wrong-resource or wrong-money body is rejected without quarantining a legitimate operation. Synthetic authentication completion, a redirect result or a caller-constructed evidence DTO is not a financial event.

Event IDs belong to `(tenant, merchant, run, provider, account)`, independently of configuration versions. Within this bounded profile the complete signed body is immutable: even an authenticated whitespace change under the same event ID is a conflict. There are no delivery timestamps or delivery-specific headers in this signed profile. A different provider transport may define another explicit replay profile through the same responsibilities.

Identical signed duplicates return the original verified reference. Normalizing a retained PENDING event after a later CAPTURED event still returns the original PENDING meaning; provider-journal and core monotonicity rules decide whether that weaker fact changes the projection.

## Transactions and recovery

V013 adds protected raw envelopes, verified-event ownership, authenticated conflict receipts and per-operation quarantine. Exact raw bytes, full operation values, original evidence, and policy/key references are retained. An authenticated event-ID conflict atomically retains both sides and quarantines both exact owners when an ID is rebound to another operation in the same scope. Invalid unauthenticated input cannot create this dispute.

The inbox, provider journal and payment store each own separate short transactions. No provider I/O occurs inside those transactions. Replay verifies retained provenance, records the complete original evidence through `ProviderEvidenceVerifier`, then applies its canonical fact through the existing core transaction. UNKNOWN creates no financial fact or permission to send. The existing core inbox makes repeated financial application idempotent.

| Interruption | Recovery |
| --- | --- |
| Before raw-envelope commit | No reference is published; retain again. |
| After raw commit | Replay the original reference. |
| After verified-event commit | Replay returns the same verified identity and original meaning. |
| After provider-journal commit, before core commit | Replay the reference to apply the retained fact idempotently. |
| After core commit, before acknowledgement | Replay is safe; the core event inbox recognizes the original fact. |
| After event-conflict commit, before core quarantine | Replay or reconcile the affected original operation to propagate its retained dispute. |

The manual `retain/replay` path stays caller-driven. Retain its opaque reference until processing is acknowledged. The separate automatic path below closes these gaps with durable delivery jobs; neither path claims an atomic inbox-to-core transaction. Core/journal history and event history must be restored together; deleting one journal is not evidence that a payment never occurred.

`ProviderEventGuards` checks the same durable quarantine before preparation, submission, reconciliation and fencing, and before/after evidence verification. All those ports must be composed with the guard in this route. A sender already past the guard may still complete concurrently with a new conflict; this quarantine is not a provider no-effect fence. HELD or disputed exposure remains conservative, and late authentic evidence is still subject to the retained conflict. Core quarantine freezes the budget even if settlement happened earlier, without rewriting a settled outcome.

## Validation scope

The event tests cover HMAC-before-decoder ordering, signed misbinding rejection, forged references, exact-byte replay, original weaker-event meaning, concurrent duplicates, same-ID conflicts, cross-operation ownership, guard behavior and separate JVM recovery after raw and verified commits. Core integration tests cover the composed financial application and dispute boundary. These are synthetic PostgreSQL/process tests; database power-loss behavior, production retention/access control, vendor signatures, public event transport, production operations and vendor conformance remain separate obligations.

## Automatic acceptance and delivery

The additive automatic path is explicit. `ProviderEventAcceptance.accept(session, intentId, bytes, signature)` requires an authenticated runner, merchant permission and an admitted operation. `ProviderEventDeliveryIngress.accept` authenticates and decodes the full binding before atomically retaining the envelope and its EVENT job. HTTP `202` is returned only after that transaction commits; it means accepted for processing, not payment success. Invalid signatures or signed misbindings create no automatic job.

| Versioned responsibility | Replacement obligation |
| --- | --- |
| `provider-webhook-authentication-1` | Resolve trusted transport credentials into a scoped application session. |
| `provider-event-acceptance-1` | Authorize the admitted target and publish only committed acceptance. |
| `provider-event-delivery-ingress-1` | Preserve exact bytes, authenticated full binding and atomic envelope/job publication. |
| `provider-event-delivery-queue-1` | Atomic claims, storage-clock leases, fenced acknowledgement, persisted retry budget and final-attempt crash recovery. |
| `provider-event-delivery-handler-1` | Verify original evidence, journal it, then apply it idempotently to its original admitted operation. |
| `provider-event-delivery-targets-1` | Resolve the exact retained core/provider association, never the intent's latest attempt. |
| `provider-event-dispute-verifier-1` | Establish protected authenticated quarantine provenance; a constructed delivery value is insufficient. |
| `provider-event-delivery-work-1` | Execute one bounded claim/delivery/settlement step without financial dispatch. |
| `provider-event-delivery-runtime-1` | Own start/close and scheduling while leaving durable truth in the queue and journals. |

`DeliverProviderEvents` uses expiring claims and capped exponential retry delays. V015 persists an eight-attempt budget per job. A crash after any separate inbox, provider-journal or core commit is recovered by repeating verification/application; the worker never calls submit, reconcile or fencing ports. A stale or expired lease cannot acknowledge another worker's claim. Final-attempt process death is reaped into `DEAD`, and unknown exception text is replaced with fixed failure codes. Permanent invalid/conflicting delivery can end earlier. Missing historical configuration and unavailable dependencies retry until the persisted budget is exhausted.

Authenticated same-ID conflicts atomically retain their dispute and publish QUARANTINE jobs for both owners if either operation has automatic delivery history. A completed original EVENT job does not prevent quarantine propagation. Manual-only histories remain manual. Quarantine processing verifies the protected dispute and exact original admitted route without requiring a current signing key; it freezes disputed exposure without changing an already settled outcome. A journal-only contradiction also freezes its current owner before that delivery becomes terminal.

Migration V015 adds `provider_event_delivery` without changing old envelopes, verified identities or route rows and without backfilling jobs. Restore queue, inbox, provider journal and core together. `DONE` records successful delivery processing, which may be an inconclusive UNKNOWN event; payment status remains authoritative. `DEAD` is attention-required, not evidence of no effect. Inspect only scoped status, attempts and fixed failure codes in operator tooling; operation JSON, raw envelopes, signatures and key references are protected data. This slice supplies no automatic redrive/reset or dispute-resolution API.

## Loopback webhook and host composition

The optional route is `POST /synthetic/provider-events/v1/{canonical-intent-uuid}`. Supply exactly one `Authorization: Bearer ...`, `Content-Type: application/json` and `X-Synthetic-Event-Signature` (64 lowercase hex) header. The body is the exact signed JSON bytes, 1–16,384 bytes; compression, query strings, ambiguous headers and noncanonical paths are rejected. Existing loopback Host/Origin checks apply. Responses contain no envelope reference, raw payload or credentials. The route is absent unless explicitly configured.

Construct one coherent set of services and supply the same store to the host:

```java
// database, runner, registration, eventConfiguration and secrets come from trusted,
// persisted host configuration. They must be stable across restart.
var events = PaymentServices.providerEventDelivery(database, registration,
    eventConfiguration, new HmacProviderEventAuthenticator(eventKey), runner);
var telemetry = LocalTelemetry.disabled();
var observations = new ServiceTelemetry(telemetry.loggers(), telemetry.meters(), System::nanoTime);
var runtime = new ScheduledProviderEventDelivery(events.delivery(), observations);
var webhookSessions = new LocalSessions(List.of(new LocalSessions.Entry(webhookToken, runner)));
var hosting = new ProviderWebhookHosting(events.acceptance(), webhookSessions::authenticate, runtime);
var host = new LocalPlatformHost(hostConfiguration, telemetry,
    services -> services.replace(LocalPlatformHost.STORE)
        .instance(LocalPlatformHost.STORE, events.store()), hosting);
host.start(); // migrates, seeds idempotently, starts delivery, HTTP and payment work
// host.close() stops HTTP ingress before delivery and other owned resources.
```

Use a dedicated webhook token rather than graph/UI credentials. Registration must be the opt-in `SyntheticProviderRegistration.interactions(...)`; the event configuration must match its scope, full binding and offer. The runnable CLI remains unchanged unless a host explicitly supplies this composition. See the executable `ProviderWebhookDeliveryIntegrationTest` for a complete synthetic setup and signed request.

The reference factory serves **one configured provider/policy per tenant/merchant/run**. Multiple workers may share that same configuration. This compatibility factory remains restricted to that scope. Use the routed composition below to share a queue across providers/accounts and historical policies; competing one-provider workers must not consume that shared queue. Missing original policy never selects a newer policy automatically.

The scheduled runtime defaults to a 200 ms delay between bounded steps and at most two seconds of shutdown waiting. It stops new polling and interrupts in-flight work; an uncooperative injected handler can outlive that wait, and durable leases permit later recovery. Existing pluggable telemetry emits fixed invocation categories; it does not substitute for queue status or expose payloads. LangGraph need not be running for delivery, and its checkpoints do not acknowledge a provider event.

Validation includes raw HTTP acceptance boundaries, concurrent/fenced queue claims, final-attempt crash recovery, child-process restart checkpoints, composed financial application/quarantine, and bounded host lifecycle tests. These establish the named synthetic behavior only. See [ADR 0022](../adr/0022-automatic-provider-event-delivery.md).

## Multi-provider and historical routing

`provider-event-delivery-routes-1` resolves EVENT work by **tenant + merchant + run + complete execution binding + exact event policy/key tuple**. This includes provider, account, region, configuration reference/version, credential reference/version and execution owner. `RouteProviderEventDelivery` verifies runner scope before resolution and compares the returned route with the requested route before calling its handler. A replacement resolver can consult trusted configuration services; callers never depend on a particular secret store or registry backend.

`ImmutableProviderEventDeliveryRoutes` copies its trusted registrations, rejects duplicate owners and resolves only exact matches. Register complete historical handlers, including their original transaction registration, offer and verifier. A current handler with an old signing key is insufficient. All workers sharing a tenant/merchant/run queue must receive coherent catalogs; separate incomplete provider catalogs may otherwise consume each other's retry budgets.

`provider-event-configuration-store-1` independently defines atomic immutable `register` and read-only `verify`. The JDBC adapter uses V016 to retain the complete route identity and a nonsecret descriptor digest. The synthetic descriptor commits to the offer, profile, mapping/scenario policies and original registration timestamps. Re-registering the same values is idempotent; changing a descriptor under the same identity fails with `CONFIGURATION_CHANGED`. Use a new explicit configuration/policy version for new semantics. Acceptance and delivery verify the commitment before authentication/processing. No key bytes, signed bodies or executable code are stored in this table.

The host reconstructs historical authenticators and handlers from trusted configuration on restart. It must preserve key-reference/version meanings; this contract cannot detect changed opaque key material or altered custom adapter code. A registered digest does not supply an authenticator, and registration does not prove vendor authenticity. Missing routes or commitments and descriptor mismatch fail closed with bounded `CONFIGURATION_UNAVAILABLE` delivery retries; healthy routes remain independently eligible. Restore the exact missing configuration before its existing attempt budget is exhausted. There is no automatic reset or redrive after `DEAD`.

```java
// Migrate first; constructors below only compose services. Keep all original values stable.
DatabaseMigrator.migrate(database);
var oldRoute = PaymentServices.providerEventDeliveryRoute(database, oldRegistration,
    oldEventConfiguration, oldAuthenticator, runner);
var currentRoute = PaymentServices.providerEventDeliveryRoute(database, currentRegistration,
    currentEventConfiguration, currentAuthenticator, runner);
oldRoute.registerConfiguration();
currentRoute.registerConfiguration();

// One keyless quarantine owner per event-ID namespace, independent of signing history.
var namespace = new ProviderEventDeliveryRoutes.Namespace(tenant, merchant,
    runner.runId().toString(), providerId, accountReference);
var quarantine = PaymentServices.providerEventQuarantine(database, runner, namespace);
var routes = new ImmutableProviderEventDeliveryRoutes(
    List.of(oldRoute.route(), currentRoute.route()), List.of(quarantine));
ProviderEventDeliveryWork work = PaymentServices.providerEventDeliveryWorker(
    database, runner, merchant, routes);
var runtime = new ScheduledProviderEventDelivery(work, observations);
runtime.start();
// Feed each explicitly configured acceptance port from its trusted ingress.
// oldRoute.acceptance() and currentRoute.acceptance() enqueue to the same durable scope.
```

Add a separate quarantine registration for each provider/account namespace. Its keyless reader verifies protected authenticated dispute provenance and the core rechecks the original admitted route. All EVENT configurations may be removed from the catalog while quarantine processing remains available. A caller-created quarantine value still cannot authorize a freeze.

The existing webhook remains explicitly bound to its injected acceptance configuration. Incoming event bytes do not choose a provider, historical key or algorithm. Multi-provider *financial admission selection* and production ingress credential routing are separate capabilities. Generic distinct-provider PostgreSQL inbox/queue tests prove dispatch isolation; synthetic financial tests prove mixed accounts, historical binding revisions, independent policy/key rotation, concurrency and JVM recovery without another submission. V016 migration tests preserve populated V015 history without enrollment or backfill. See [ADR 0023](../adr/0023-historical-event-routing.md).
