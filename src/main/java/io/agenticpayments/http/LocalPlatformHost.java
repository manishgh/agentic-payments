package io.agenticpayments.http;

import io.agenticpayments.application.CheckoutLifecycle;
import io.agenticpayments.application.PaymentOperations;
import io.agenticpayments.application.PaymentService;
import io.agenticpayments.application.PaymentService.Session;
import io.agenticpayments.application.PaymentStore;
import io.agenticpayments.application.instrumentation.InstrumentedCheckoutLifecycle;
import io.agenticpayments.application.instrumentation.InstrumentedPaymentOperations;
import io.agenticpayments.application.instrumentation.MonotonicClock;
import io.agenticpayments.application.instrumentation.ServiceTelemetry;
import io.agenticpayments.composition.Composition.Ports;
import io.agenticpayments.composition.Composition.SignedArtifactStore;
import io.agenticpayments.composition.NativeComposition;
import io.agenticpayments.composition.di.Lifetime;
import io.agenticpayments.composition.di.Ownership;
import io.agenticpayments.composition.di.ServiceCollection;
import io.agenticpayments.composition.di.ServiceKey;
import io.agenticpayments.composition.di.ServiceProvider;
import io.agenticpayments.infrastructure.DatabaseMigrator;
import io.agenticpayments.infrastructure.JdbcPaymentStore;
import io.agenticpayments.infrastructure.JdbcSignedArtifactStore;
import io.agenticpayments.infrastructure.JdbcSimulatorAdapter;
import io.agenticpayments.infrastructure.LocalCheckoutRegistration;
import io.agenticpayments.observability.LoggerFactory;
import io.agenticpayments.observability.MeterFactory;
import io.agenticpayments.sdk.PaymentAdapter;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.sql.DataSource;

/** Local synthetic host: validate composition, initialize durable state, then start external work. */
public final class LocalPlatformHost implements AutoCloseable {
    public static final ServiceKey<DataSource> DATA_SOURCE = ServiceKey.of(DataSource.class);
    public static final ServiceKey<PaymentAdapter> ADAPTER = ServiceKey.of(PaymentAdapter.class);
    public static final ServiceKey<SignedArtifactStore> ARTIFACTS = ServiceKey.of(SignedArtifactStore.class);
    public static final ServiceKey<Ports> NATIVE_PORTS = ServiceKey.of(Ports.class, "native");
    public static final ServiceKey<Ports> EXTERNAL_PORTS = ServiceKey.of(Ports.class, "external");
    public static final ServiceKey<PaymentStore> STORE = ServiceKey.of(PaymentStore.class);
    public static final ServiceKey<PaymentOperations> OPERATIONS = ServiceKey.of(PaymentOperations.class);
    public static final ServiceKey<CheckoutLifecycle> CHECKOUT = ServiceKey.of(CheckoutLifecycle.class);
    private static final ServiceKey<LocalCheckoutRegistration> REGISTRATION = ServiceKey.of(LocalCheckoutRegistration.class);

    /** All values are trusted startup choices; scopes and configuration confer no caller authority. */
    public record Configuration(DataSource database, Session runner, Session graph, LocalSessions sessions,
                                int port, Path uiDirectory, String checkoutOrigin, boolean financialCheckout) {
        public Configuration {
            Objects.requireNonNull(database);
            Objects.requireNonNull(runner);
            Objects.requireNonNull(graph);
            Objects.requireNonNull(sessions);
            Objects.requireNonNull(uiDirectory);
            if (port < 0 || port > 65535) throw new IllegalArgumentException("INVALID_LOCAL_PORT");
            if (!runner.tenantId().equals(graph.tenantId()) || !runner.runId().equals(graph.runId())) {
                throw new IllegalArgumentException("INVALID_LOCAL_RUN_CONFIGURATION");
            }
            if ((checkoutOrigin != null && checkoutOrigin.isBlank()) || (financialCheckout && checkoutOrigin == null)) {
                throw new IllegalArgumentException("INVALID_EXTERNAL_CHECKOUT_FINANCE_CONFIGURATION");
            }
        }
        @Override public String toString() { return "Configuration[redacted]"; }
    }

    private final Configuration configuration;
    private final LocalTelemetry telemetry;
    private final Consumer<ServiceCollection> customize;
    private final ProviderWebhookHosting webhook;
    private ServiceProvider services;
    private PaymentHttpServer server;
    private PaymentWorker worker;
    private boolean started;
    private boolean closed;

    public LocalPlatformHost(Configuration configuration, LocalTelemetry telemetry) {
        this(configuration, telemetry, registrations -> { });
    }

    /** Applies explicit replacements and decorators before the graph is validated or initialized. */
    public LocalPlatformHost(Configuration configuration, LocalTelemetry telemetry, Consumer<ServiceCollection> customize) {
        this(configuration, telemetry, customize, null);
    }

    /** Adds explicitly configured webhook acceptance and automatic delivery to the local host. */
    public LocalPlatformHost(Configuration configuration, LocalTelemetry telemetry, Consumer<ServiceCollection> customize,
                             ProviderWebhookHosting webhook) {
        this.configuration = Objects.requireNonNull(configuration);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.customize = Objects.requireNonNull(customize);
        this.webhook = webhook;
    }

    public synchronized void start() throws IOException {
        if (closed) throw new IllegalStateException("Local platform host is closed");
        if (started) return;
        try {
            ServiceCollection collection = registrations(configuration, telemetry);
            customize.accept(collection);
            services = collection.build();
            DatabaseMigrator.migrate(services.get(DATA_SOURCE));
            PaymentOperations operations = services.get(OPERATIONS);
            // Idempotent seed must never replenish authority or reset the virtual payment clock.
            operations.seedRun(configuration.runner(), Instant.parse("2026-11-06T10:00:00Z"), 100_000);
            CheckoutLifecycle checkout = null;
            if (configuration.checkoutOrigin() != null) {
                services.get(REGISTRATION).start();
                checkout = services.get(CHECKOUT);
            }
            telemetry.start();
            server = new PaymentHttpServer(operations, configuration.sessions(), configuration.port(), configuration.uiDirectory(), checkout,
                    webhook == null ? null : webhook.acceptance(), webhook == null ? null : webhook.authentication());
            if (webhook != null) webhook.runtime().start();
            server.start();
            worker = new PaymentWorker(operations, configuration.runner());
            worker.start();
            started = true;
        } catch (IOException | RuntimeException | Error failure) {
            closeInternal();
            throw failure;
        }
    }

    public synchronized int port() {
        if (!started || closed) throw new IllegalStateException("Local platform host is not running");
        return server.port();
    }

    static ServiceCollection registrations(Configuration config, LocalTelemetry telemetry) {
        ServiceKey<LoggerFactory> loggers = ServiceKey.of(LoggerFactory.class);
        ServiceKey<MeterFactory> meters = ServiceKey.of(MeterFactory.class);
        ServiceKey<MonotonicClock> clock = ServiceKey.of(MonotonicClock.class);
        var collection = ServiceCollection.create()
            .instance(DATA_SOURCE, config.database())
            .instance(loggers, telemetry.loggers()).instance(meters, telemetry.meters())
            .instance(clock, (MonotonicClock) System::nanoTime)
            .add(ServiceKey.of(ServiceTelemetry.class), ServiceTelemetry.class, Lifetime.SINGLETON, true)
            .add(ADAPTER, JdbcSimulatorAdapter.class, Lifetime.SINGLETON, true)
            .add(ARTIFACTS, JdbcSignedArtifactStore.class, Lifetime.SINGLETON, true)
            .factory(NATIVE_PORTS, Lifetime.SINGLETON, true, Ownership.BORROWED, List.of(ARTIFACTS),
                r -> NativeComposition.ports(r.get(ARTIFACTS)))
            .factory(EXTERNAL_PORTS, Lifetime.SINGLETON, true, Ownership.BORROWED, List.of(ARTIFACTS),
                r -> NativeComposition.externalPorts(r.get(ARTIFACTS)));
        if (config.checkoutOrigin() != null) {
            // The JDK client is an owned resource here; enrollment, network calls and business
            // workers remain deferred until after graph validation, migration and run seeding.
            collection.factory(REGISTRATION, Lifetime.SINGLETON, true, Ownership.OWNED, List.of(DATA_SOURCE),
                r -> new LocalCheckoutRegistration(r.get(DATA_SOURCE), config.graph(), config.checkoutOrigin(), config.financialCheckout()));
            collection.factory(CHECKOUT, Lifetime.SINGLETON, true, Ownership.BORROWED, List.of(REGISTRATION),
                r -> r.get(REGISTRATION).lifecycle());
            collection.decorate(CHECKOUT, InstrumentedCheckoutLifecycle.class, 100);
        }
        if (config.financialCheckout()) {
            collection.factory(STORE, Lifetime.SINGLETON, true, Ownership.OWNED, List.of(REGISTRATION, ADAPTER, NATIVE_PORTS, EXTERNAL_PORTS),
                r -> r.get(REGISTRATION).financialStore(r.get(ADAPTER), r.get(NATIVE_PORTS), r.get(EXTERNAL_PORTS)));
        } else {
            collection.factory(STORE, Lifetime.SINGLETON, true, Ownership.OWNED, List.of(DATA_SOURCE, ADAPTER, NATIVE_PORTS),
                r -> new JdbcPaymentStore(r.get(DATA_SOURCE), r.get(ADAPTER), null, r.get(NATIVE_PORTS)));
        }
        return collection.add(OPERATIONS, PaymentService.class, Lifetime.SINGLETON, true)
            .decorate(OPERATIONS, InstrumentedPaymentOperations.class, 100);
    }

    @Override
    public synchronized void close() {
        if (closeInternal()) throw new IllegalStateException("Local platform shutdown failed");
    }

    private boolean closeInternal() {
        if (closed) return false;
        closed = true;
        boolean failed = false;
        // Stop ingress before stopping its consumer; any already committed delivery remains durable.
        if (server != null) {
            try { server.close(); } catch (RuntimeException | LinkageError | AssertionError ignored) { failed = true; }
        }
        if (webhook != null) {
            try { webhook.runtime().close(); } catch (RuntimeException | LinkageError | AssertionError ignored) { failed = true; }
        }
        if (worker != null) {
            try { worker.close(); } catch (RuntimeException | LinkageError | AssertionError ignored) { failed = true; }
        }
        if (services != null) {
            try { services.close(); } catch (RuntimeException | LinkageError | AssertionError ignored) { failed = true; }
        }
        try { telemetry.close(); } catch (RuntimeException | LinkageError | AssertionError ignored) { failed = true; }
        return failed;
    }
}
