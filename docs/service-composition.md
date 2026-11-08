# Service composition, telemetry and application failures

The local Java host composes payment and checkout services through explicit constructor injection. `PaymentService` implements `PaymentOperations`; HTTP and worker callers depend on that interface and `CheckoutLifecycle`. Direct construction remains available through `PaymentServices.local(...)`. This implements the bounded reference scope of [ADR 0015](adr/0015-service-composition-observability-errors.md), with no new dependency.

## Register and replace services

The `service-composition-0.1` interfaces live in `io.agenticpayments.composition.di`: a startup `ServiceCollection`, immutable root `ServiceProvider`, thread-confined `ServiceScope`, and `ServiceKey<T>` containing a contract type and optional trusted qualifier. Register interfaces or concrete classes explicitly. There is no scanning, unregistered activation, annotation/field injection, implicit optional resolution or global service locator.

Class bindings select the sole public constructor. For ambiguous constructors or qualified parameters, use the overload accepting a `Constructor` and ordered parameter keys. Factories declare dependency keys and receive a resolver restricted to those keys. Supply options and primitive values through explicit instances or factories. Constructors and factories construct objects; they do not start I/O, workers or exporters.

For example, this registers the real service instrumentation constructor and its three dependencies:

```java
import io.agenticpayments.application.instrumentation.MonotonicClock;
import io.agenticpayments.application.instrumentation.ServiceTelemetry;
import io.agenticpayments.composition.di.*;
import io.agenticpayments.observability.*;

ServiceCollection services = ServiceCollection.create();
services.instance(ServiceKey.of(LoggerFactory.class), NoopTelemetry.loggers());
services.instance(ServiceKey.of(MeterFactory.class), NoopTelemetry.meters());
services.instance(ServiceKey.of(MonotonicClock.class), System::nanoTime);
services.add(ServiceKey.of(ServiceTelemetry.class), ServiceTelemetry.class,
        Lifetime.SINGLETON, true);
try (ServiceProvider root = services.build()) {
    ServiceTelemetry telemetry = root.get(ServiceKey.of(ServiceTelemetry.class));
    // Inject telemetry into a service wrapper; do not pass root into business code.
}
```

`build()` validates the entire graph, including unused bindings, missing dependencies, duplicate registrations, constructor/type mismatches, cycles and lifetime violations. It then constructs singletons once. A singleton must be explicitly declared thread-safe and depend transitively only on thread-safe singletons. Root resolution permits singletons only. `SCOPED` services may depend on any lifetime; `TRANSIENT` resolution requires a scope. Resolve scoped/transient values inside `try (ServiceScope scope = root.createScope()) { ... }`. Scopes confer no authorization; async work opens a new scope and receives only approved immutable context.

Use `replace(key)` before adding a replacement. It removes the old binding and its decorators; there is no hot reload. Trusted native/external qualifiers select composition ports at startup and never come from request input. Replacements still must preserve shared-database coordination, enrollment, seals, current authorization checks and atomic admission.

`decorate(key, Wrapper.class, order)` requires exactly one constructor parameter of the same contract for the inner service. Other dependencies resolve normally. Order 10 wraps order 20, which wraps the base. Decorators share the binding's lifetime and caching. They invoke the inner operation once, preserve its result or original exception, and never close dependencies, retry financial work or change capabilities. Container-created values are owned; factory registrations declare `Ownership`, and instance registrations are borrowed by default. Shutdown and startup-failure cleanup close each owned identity once in reverse construction order, continuing after sanitized cleanup failures. Resolution after shutdown is rejected.

## Customize the local host

`io.agenticpayments.http.LocalPlatformHost` accepts a trusted `Configuration`, `LocalTelemetry`, and optional `Consumer<ServiceCollection>`. The consumer changes registrations before graph validation and initialization. The configuration contains the database, runner/graph sessions, authenticated local sessions, port/UI directory and explicit checkout-origin/financial opt-in choices. No request chooses a binding, qualifier or exporter.

For example, this startup method replaces the synthetic adapter through its existing contract. The caller retains and closes the returned host at shutdown. The adapter must be thread-safe and satisfy the selected synthetic profile; supplying an implementation does not enable a real provider.

```java
import io.agenticpayments.http.LocalPlatformHost;
import io.agenticpayments.http.LocalTelemetry;
import io.agenticpayments.sdk.PaymentAdapter;

// Place this method in your startup class; config comes from trusted startup values.
static LocalPlatformHost startHost(LocalPlatformHost.Configuration config,
                                   PaymentAdapter adapter) throws Exception {
    LocalPlatformHost host = new LocalPlatformHost(config, LocalTelemetry.disabled(),
            services -> services.replace(LocalPlatformHost.ADAPTER)
                    .instance(LocalPlatformHost.ADAPTER, adapter));
    host.start();
    return host;
}
```

Public host keys are `DATA_SOURCE`, `ADAPTER`, `ARTIFACTS`, `NATIVE_PORTS`, `EXTERNAL_PORTS`, `STORE`, `OPERATIONS` and optional `CHECKOUT`. The two port keys have trusted `native`/`external` qualifiers. The default service-observation decorators have order 100; `services.decorate(LocalPlatformHost.OPERATIONS, MyWrapper.class, 10)` adds an outer wrapper when `MyWrapper` implements `PaymentOperations` and has the required constructor. Replacing a whole binding removes its observation decorator too; re-register approved wrappers deliberately.

Host construction starts no work. `start()` builds/validates the graph, migrates storage and performs the existing idempotent seed before starting checkout resources, telemetry, HTTP and the worker. Startup failure unwinds ownership. Shutdown stops worker/HTTP entry points, closes the service root and then closes telemetry. Direct `PaymentServices.local(...)` callers retain their own composition and lifecycle responsibilities.

The local worker allows up to two seconds for an interrupted step to finish. A replacement that ignores interruption may remain in flight while owned resources close. Shutdown is not proof of payment cancellation or absence of effects; durable operation ownership and reconciliation remain authoritative. Replacement services must make their cleanup bounded. The external checkout registration owns its HTTP client from graph construction and closes it on startup failure; enrollment and checkout requests remain explicit operations.

## Logs and metrics

The contracts depend only on `java.base` and live in `io.agenticpayments.observability`: `logging-0.1` uses `LoggerFactory`, `Logger`, `LogScope` and batched `LogProvider`; `metrics-0.1` uses `MeterFactory`, `Meter`, `Counter`, `Histogram` and snapshot `MetricProvider`. Applications can implement, adapt or decorate these interfaces. Exporter implementations remain in infrastructure, outside the standalone adapter SDK and AP2 engine packages.

Logging uses fixed startup categories, event IDs, templates and field schemas. `LogFields` copies bounded values from the closed `LogValue` model: finite catalog values, bounded integers/decimals, booleans, or locally generated `CorrelationId` values. Invalid runtime fields drop. There is no arbitrary `Object`, exception, serializer or free-text field API. Scope fields are validated separately; scopes are factory-local, thread-local, non-inheritable and LIFO. Explicit `captureScope()`/`beginScope(snapshot)` propagation revalidates copied fields. Close scopes in `finally` or try-with-resources; repeated close is harmless, while thread/order misuse clears affected state.

Metrics reuse equivalent definitions and reject conflicting registrations. Definitions fix names, units, finite dimension values and histogram buckets. Each instrument allows at most 1,024 Cartesian-product series; a bounded factory allows at most 16,384 total potential series. Counter inputs are nonnegative; histogram measurements must be finite and nonnegative. Aggregation retains cumulative counts/sums and disjoint fixed-bucket counts, including an overflow bucket, rather than measurement history. Overflow saturates sums instead of wrapping or producing infinity. Invalid values or dimensions drop. Registries and aggregates are isolated between roots; metric dimensions never contain correlation IDs.

The outer `InstrumentedPaymentOperations` and `InstrumentedCheckoutLifecycle` decorators record `service.completed`, `service.calls` and `service.duration`. Their only dimensions are fixed operation, `returned`/`replayed`/`rejected`/`error`, and a closed failure category or `NONE`. Durations use injected `MonotonicClock`, never payment virtual time. A returned invocation does not prove settlement. No arguments, success bodies, transaction/connection/session objects, exception text or financial transaction callbacks reach exporters.

Identities, digests, amounts, grants, credentials, URLs, headers, bodies, JWTs, OTPs and customer data are excluded. Incoming correlation text remains untrusted; hashing it does not make it approved telemetry. The initial host catalog exports no correlation field. Invalid catalog registration fails explicitly rather than silently selecting a fallback.

## Export and shutdown behavior

No-op telemetry is the default. `BoundedLoggerFactory` and `BoundedMeterFactory` support explicit memory providers and JSON-console providers. Constructors create no workers; `start()` starts dispatch only after startup validation. Application threads validate/copy/enqueue or update finite aggregates. They never wait for provider export, console/network I/O or flush. Each provider has its own bounded queue and daemon dispatcher, so a blocked provider cannot stall another provider or business work. Metric snapshots are published by a separate started worker.

`Main` selects the optional local JSON console with `PAYMENTS_TELEMETRY=json-console`; leave the variable unset for no-op telemetry. Embedders use `LocalTelemetry.disabled()`, `LocalTelemetry.console(PrintStream)` or `LocalTelemetry.providers(logProviders, metricProviders, options)`. The host owns that bundle and starts/closes it. Streams passed to console providers remain borrowed. To inspect diagnostics in tests or local tools, construct bounded factories with `MemoryLogProvider(capacity)`/`MemoryMetricProvider(capacity)` and retain those bounded provider references.

Queues drop overflow without business retry. Delivery is per-provider FIFO/best effort, with no durable or exactly-once guarantee. Memory providers retain bounded ring buffers. Each bounded factory's `.health()` exposes cumulative local enqueue, drop and export-failure counters without recursively logging failures; metric snapshots also carry metric-dispatch health. Counts saturate and retain no exception details.

Provider export/close faults, including nonfatal `Error` values, are contained; JVM-fatal failures remain fatal. Providers owned by a successfully constructed factory are closed once on isolated threads, including cleanup before dispatch ever starts. A host deadline bounds waiting even when export or close ignores interruption; remaining queued data drops. A stalled daemon may outlive shutdown. Replacement logger/meter callbacks themselves must be bounded and nonblocking: guards preserve business outcomes after thrown faults, but cannot isolate arbitrary synchronous stalls inside a replacement.

The local console configuration uses 256 queue entries per provider, batches up to 32 records, metric publication every 10 seconds and one shared 250 ms telemetry shutdown budget. Telemetry is diagnostic; it does not replace audit records, accounting, durable evidence or recovery state.

## Application failures and HTTP compatibility

`ApplicationException` contains a typed immutable `ApplicationFailure` catalog entry with ID, existing code, fixed safe message, category, retry advice and effect certainty. It contains no HTTP status, arbitrary detail or raw cause. `PaymentException` remains a deprecated typed alias. Translate failures only at the boundary responsible for their meaning; an arbitrary `IllegalArgumentException` is not evidence of a bad caller request.

`HttpFailureMapper` maps catalog IDs to the established HTTP status/code/message/retry representation. Separate IDs retain the different `TEMPORARILY_UNAVAILABLE` and `CHECKOUT_STORE_UNAVAILABLE` retry variants. Malformed decode/path/header/body input maps explicitly to `400 INVALID_REQUEST` before application invocation. Unexpected failures retain `503 TEMPORARILY_UNAVAILABLE / SAME_COMMAND`; the compatibility wire value for reconciliation advice is `READ_STATUS`. Response-write failure never reruns business work. Existing accepted responses and `Result.status` remain unchanged.

`NEVER`, `SAME_COMMAND` and `RECONCILE_SAME_OPERATION` are advice about preserving operation identity, not authorization to retry a charge. Certainty concerns this invocation only. `NOT_STARTED_THIS_INVOCATION` says nothing about earlier or concurrent effects; ambiguous storage, commit and transport failures retain `MAY_HAVE_OCCURRED`. No failure or telemetry result permits releasing exposure, changing the operation/idempotency key or bypassing reconciliation.

Remote collectors, tracing, production retention and managed-service operations remain outside this slice. Real payment providers remain outstanding; these local interfaces and checks establish no regulatory, PCI, network or provider certification.
