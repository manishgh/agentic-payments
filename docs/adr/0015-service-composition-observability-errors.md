# ADR 0015: Service composition, observability and application failures

## Status

Accepted design for generic Java DI, logging, metrics and transport-independent failures, preserving payment/checkout/AP2/provider behavior. Implementation remains outstanding; no new dependency is selected.

## Composition and lifetimes

PaymentService implements `PaymentOperations` with unchanged operations/DTOs. Keep `CheckoutLifecycle`. HTTP/typed workers use constructor-injected interfaces; decorators need no downcasts.

Host uses DI; PaymentServices.local preserves direct construction. No business locators or constructor I/O/executors. Host validates/migrates/seeds before starting HTTP/workers.

`service-composition-0.1`: startup ServiceCollection, immutable root ServiceProvider, ServiceScope, ServiceKey(type, qualifier). Explicit interface/class bindings resolve the sole public constructor; ambiguity needs explicit selection/parameter bindings. Options/factories supply values; native/external qualifiers are trusted startup choices. Factories declare dependencies and receive restricted resolution. No scanning, unregistered activation, field injection, annotations, global locator, implicit optionals or automatic discovery.

Build validates all nodes: duplicates/missing bindings, types/constructors/qualifiers, factory/decorator cycles/lifetimes. Explicit replacement, no hot reload. Singleton construction publishes once; startup failure unwinds.

SINGLETON depends transitively only on thread-safe singletons; SCOPED may depend on any lifetime; TRANSIENT requires a scope. Root resolves singletons only. Scopes are thread-confined and confer no authorization; async work opens a new scope with approved immutable context.

`decorate(key,class,order)` identifies one same-contract inner parameter; ascending order means 10 wraps 20 wraps base. Inner edges select the next layer; other dependencies resolve normally. Chains share lifetime/cache/validation. Wrappers invoke once, without retries/financial interceptors or silent capability changes.

Container-created AutoCloseable values are owned; factories declare ownership, instances default borrowed. Close each identity once in reverse construction order, including startup failure. Decorators never close dependencies. Scopes own scoped/transient values; root owns singletons. Idempotent shutdown rejects resolution, stops HTTP/workers, closes scopes/services/telemetry. Cleanup continues after sanitized failure, preserving outcomes.

DI preserves default-disabled external opt-in, trusted enrollment/configuration, shared-database coordination token, seals, atomic admission and explicit authorization/current store checks.

## Logging and metrics contracts

Standard-library contracts: logging-0.1/metrics-0.1 (observability), DI (composition.di), application-failure-0.1 (application). Providers stay infrastructure-only; SDK/AP2 packaging stays independent.

Logging: LoggerFactory.create(category), isEnabled, log(level,eventDefinition,fields), beginScope, LogProvider batches. TRACE/DEBUG/INFO/WARN/ERROR levels and fixed category/event/template/schema catalogs. Copy bounded typed fields; prohibit Object/Throwable/serializers and peer-text concatenation. Invalid fields drop.

Logging scopes are factory-instance/thread-local, LIFO, non-inheritable; async propagation uses validated snapshots. Close in finally; idempotent/nonthrowing close clears state on thread/order misuse.

Metrics: MeterFactory.create(name,version), Counter.add(long,dimensions), Histogram.record(double,dimensions), MetricProvider snapshots. Thread-safe instruments reuse definitions; conflicts reject, roots isolate. Counters are nonnegative; histograms finite/nonnegative with fixed buckets/count/sum. Invalid inputs drop; no static registry/unbounded history/application-thread callbacks.

Definitions fix names/units/buckets, finite tags and Cartesian-product caps. Initial metrics: calls/duration/telemetry health; tags: operation, returned/replayed/rejected/error, closed failure category. Inject monotonic elapsed time, never payment virtual time. Invocation success is not settlement.

Only logs allow generated correlation IDs. Exclude identities/digests/amounts/grants/credentials/URLs/headers/exceptions/bodies/JWTs/OTPs/customer data. Incoming correlation text is untrusted; hashing grants no permission. No Session/transaction/connection/application objects reach exporters.

Factories/providers support implementation/adaptation/decoration. Explicit no-op default; bounded memory/JSON console options. No invalid-registration fallback, request-selected exporter or recursive failure logging.

## Isolation and transactions

Telemetry dispatch workers start explicitly after graph validation, never in constructors. Application threads validate/snapshot/enqueue without waiting or exporter/console/network I/O/flush. Isolated bounded provider queues drop overflow; no business retry. FIFO is per-provider/best effort, never durable/exactly-once.

Instrument outer PaymentOperations/CheckoutLifecycle decorators only: monotonic start, one invocation, completion after return/throw. Record approved replay/failure classes, never arguments/results. No JDBC/AP2/provider transaction callbacks or exporter financial context. Uncertain commits never imply rollback/no effect.

Nonthrowing dispatchers contain runtime/provider faults; decorators separately guard setup/record/close, preserving results/original exceptions even with broken replacements. JVM failure retains durable recovery. Replacement logger/meter callbacks must be bounded and nonblocking; guards contain thrown faults, not arbitrary synchronous stalls. Exporters run only behind isolated queues. A host-wide shutdown deadline bounds waiting even when an exporter never returns; remaining data drops. Telemetry never replaces audit/evidence/accounting.

## Application failures and compatibility

ApplicationException holds catalog-defined immutable ApplicationFailure(id, existing code, fixed safe message, category, retry, certainty). No HTTP status/raw cause/arbitrary details or strings. Categories: VALIDATION, UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, CONFLICT, UNSUPPORTED, EXPIRED, UNAVAILABLE, INTERNAL.

Retry: NEVER/SAME_COMMAND/RECONCILE_SAME_OPERATION. Certainty: NOT_STARTED_THIS_INVOCATION/MAY_HAVE_OCCURRED/NOT_APPLICABLE. This never proves absence of earlier/stale-worker effects or permits exposure release/key changes. Ambiguous storage/commit/transport failures retain MAY_HAVE_OCCURRED; retry preserves identity, not certainty.

Use typed entries; PaymentException may remain a deprecated typed alias, without production numeric constructors/status branching. Workers preserve expiry/unavailability behavior through categories. Distinct entries retain TEMPORARILY_UNAVAILABLE/CHECKOUT_STORE_UNAVAILABLE retry variants. Preserve SQL replay/ownership, AP2/Composition, checkout and provider-certainty contracts. Translate only at responsible boundaries; arbitrary IllegalArgumentException is not caller error.

HttpFailureMapper maps each catalog ID to existing status/code/message/retry/correlation behavior exactly; unexpected failures remain `503 TEMPORARILY_UNAVAILABLE / SAME_COMMAND`. Decode/path/header/body failures map explicitly to `400 INVALID_REQUEST` before application calls; remove broad programming/protocol-error catches. Response-write failure never reruns work. Accepted responses/Result.status remain; no database/envelope/success-DTO migration.

## Verification and consequences

Test graph/lifetimes, concurrent singletons, isolated scopes, decorator order/one call and ownership. Verify privacy/bounds/snapshots and exporter/overflow/shutdown faults. PostgreSQL/HTTP journeys must preserve replay/reservations/seals/approval/challenges/UNKNOWN recovery. Pin errors and SDK/AP2 packaging.

Defer remote exporters, tracing, hot reload, general annotation/generic injection, production retention and neutral success DTOs.

Inspired by Microsoft [logging](https://learn.microsoft.com/en-us/dotnet/core/extensions/logging/overview), [metrics](https://learn.microsoft.com/en-us/dotnet/core/diagnostics/metrics-instrumentation), [DI ownership](https://learn.microsoft.com/en-us/dotnet/core/extensions/dependency-injection/guidelines) and [lifetimes](https://learn.microsoft.com/en-us/dotnet/core/extensions/dependency-injection/service-lifetimes); independently authored Java contracts claim no .NET compatibility or implementation reuse.
