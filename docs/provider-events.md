# Durable synthetic provider events

The opt-in `local-provider-interactions-1` execution route adds protected event retention and explicit replay to the [provider journal](durable-provider-journal.md). It is a local synthetic profile. It does not implement a vendor webhook, configure a public HTTP listener, or make a live-provider authenticity claim. Existing `local-provider-journal-admission-1` operations keep their original offer and route.

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

Delivery is explicitly caller-driven: this slice does not claim an autonomous inbox worker, delivery retries, or an atomic inbox-to-core transaction. Retain the opaque reference until processing is acknowledged. Core/journal history and event history must be restored together; deleting one journal is not evidence that a payment never occurred.

`ProviderEventGuards` checks the same durable quarantine before preparation, submission, reconciliation and fencing, and before/after evidence verification. All those ports must be composed with the guard in this route. A sender already past the guard may still complete concurrently with a new conflict; this quarantine is not a provider no-effect fence. HELD or disputed exposure remains conservative, and late authentic evidence is still subject to the retained conflict. Core quarantine freezes the budget even if settlement happened earlier, without rewriting a settled outcome.

## Validation scope

The event tests cover HMAC-before-decoder ordering, signed misbinding rejection, forged references, exact-byte replay, original weaker-event meaning, concurrent duplicates, same-ID conflicts, cross-operation ownership, guard behavior and separate JVM recovery after raw and verified commits. Core integration tests cover the composed financial application and dispute boundary. These are synthetic PostgreSQL/process tests; database power-loss behavior, production retention/access control, vendor signatures, public event transport and operational delivery remain separate obligations.
