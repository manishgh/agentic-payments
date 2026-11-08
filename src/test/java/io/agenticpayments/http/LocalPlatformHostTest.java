package io.agenticpayments.http;

import io.agenticpayments.application.PaymentOperations;
import io.agenticpayments.application.PaymentService.Result;
import io.agenticpayments.application.PaymentService.Session;
import io.agenticpayments.application.PaymentStore;
import io.agenticpayments.composition.di.Lifetime;
import io.agenticpayments.composition.di.Ownership;
import io.agenticpayments.composition.di.ServiceCompositionException;
import io.agenticpayments.composition.di.ServiceKey;
import io.agenticpayments.observability.LogProvider;
import io.agenticpayments.observability.LogRecord;
import io.agenticpayments.infrastructure.observability.TelemetryOptions;
import io.agenticpayments.infrastructure.LocalCheckoutRegistration;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

class LocalPlatformHostTest {
    @Test
    void defaultGraphDoesNoDatabaseWorkAndUsesReplacementStoreThroughTheDecoratedContract() {
        var connections = new AtomicInteger();
        var calls = new AtomicInteger();
        var response = new Result(200, Map.of("state", "DRAFT"), false);
        var replacement = (PaymentStore) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PaymentStore.class},
            (proxy, method, args) -> {
                if (method.getName().equals("status")) { calls.incrementAndGet(); return response; }
                throw new AssertionError("Unexpected store invocation");
            });
        var config = config(database(connections), null, false);
        try (var telemetry = LocalTelemetry.disabled()) {
            var registrations = LocalPlatformHost.registrations(config, telemetry);
            registrations.replace(LocalPlatformHost.STORE).instance(LocalPlatformHost.STORE, replacement);
            try (var services = registrations.build()) {
                assertSame(response, services.get(LocalPlatformHost.OPERATIONS).status(config.graph(), UUID.randomUUID()));
                assertEquals(1, calls.get());
                assertEquals(0, connections.get());
                assertThrows(ServiceCompositionException.class, () -> services.get(LocalPlatformHost.CHECKOUT));
                assertNotSame(services.get(LocalPlatformHost.NATIVE_PORTS), services.get(LocalPlatformHost.EXTERNAL_PORTS));
            }
        }
    }

    @Test
    void financialCapabilityRequiresExplicitOptInAndGraphBuildNeverEnrollsCheckout() {
        var connections = new AtomicInteger();
        for (boolean financial : List.of(false, true)) {
            var config = config(database(connections), "http://127.0.0.1:9099", financial);
            try (var telemetry = LocalTelemetry.disabled(); var services = LocalPlatformHost.registrations(config, telemetry).build()) {
                assertEquals(financial, services.get(LocalPlatformHost.STORE).admissionCapabilities()
                    .contains("atomic-external-checkout-binding-0.1"));
                assertNotNull(services.get(LocalPlatformHost.CHECKOUT));
                assertEquals(0, connections.get());
            }
        }
        assertThrows(IllegalArgumentException.class, () -> config(database(connections), null, true));
        var config = config(database(connections), "http://127.0.0.1:9099", false);
        var registration = new LocalCheckoutRegistration(config.database(), config.graph(), config.checkoutOrigin());
        registration.close();
        registration.close();
        assertThrows(IllegalStateException.class, registration::start);
        assertEquals(0, connections.get());
    }

    @Test
    void invalidGraphFailsBeforeMigrationAndStillReleasesTelemetryOwnership() throws Exception {
        var connections = new AtomicInteger();
        var released = new CountDownLatch(1);
        var exports = new AtomicInteger();
        LogProvider provider = new LogProvider() {
            @Override public void export(List<LogRecord> batch) { exports.incrementAndGet(); }
            @Override public void close() { released.countDown(); }
        };
        var telemetry = LocalTelemetry.providers(List.of(provider), List.of(), TelemetryOptions.defaults());
        var host = new LocalPlatformHost(config(database(connections), null, false), telemetry,
            services -> services.factory(ServiceKey.of(String.class), Lifetime.SINGLETON, true,
                Ownership.BORROWED, List.of(ServiceKey.of(Integer.class)), resolver -> "unreachable"));
        assertEquals(0, connections.get());
        assertThrows(ServiceCompositionException.class, host::start);
        assertTrue(released.await(1, TimeUnit.SECONDS));
        assertEquals(0, exports.get());
        assertEquals(0, connections.get());
        assertThrows(IllegalStateException.class, host::port);
        assertThrows(IllegalStateException.class, host::start);
        host.close();
    }

    @Test
    void migrationFailureUnwindsInitializedGraphWithoutStartingWorkerOrHttp() {
        var connections = new AtomicInteger();
        var closed = new AtomicInteger();
        var host = new LocalPlatformHost(config(database(connections), null, false), LocalTelemetry.disabled(),
            services -> services.factory(ServiceKey.of(OwnedProbe.class), Lifetime.SINGLETON, true,
                Ownership.OWNED, List.of(), resolver -> new OwnedProbe(closed)));
        var failure = assertThrows(IllegalStateException.class, host::start);
        assertEquals("Database migration failed", failure.getMessage());
        assertEquals(1, connections.get());
        assertEquals(1, closed.get());
        host.close();
        assertEquals(1, closed.get());
        assertThrows(IllegalStateException.class, host::port);
    }

    @Test
    void workerIsExplicitlyStartedAndContinuesPollingAfterAnApplicationFailure() throws Exception {
        var calls = new AtomicInteger();
        var completedSecond = new CountDownLatch(1);
        var session = session(Set.of("runner:write"));
        PaymentOperations operations = operations((s) -> {
            assertSame(session, s);
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("private-worker-error");
            completedSecond.countDown();
            return false;
        });
        var worker = new PaymentWorker(operations, session);
        assertEquals(0, calls.get());
        worker.start();
        worker.start();
        assertTrue(completedSecond.await(2, TimeUnit.SECONDS));
        worker.close();
        int stoppedAt = calls.get();
        worker.close();
        assertEquals(stoppedAt, calls.get());
        assertThrows(IllegalStateException.class, worker::start);
    }

    @Test
    void workerShutdownInterruptsAnActiveStepBeforeReturning() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var operations = operations(session -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException expected) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            return false;
        });
        var worker = new PaymentWorker(operations, session(Set.of("runner:write")));
        worker.start();
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        worker.close();
        assertEquals(0, interrupted.getCount());
    }

    private static DataSource database(AtomicInteger connections) {
        return (DataSource) Proxy.newProxyInstance(LocalPlatformHostTest.class.getClassLoader(), new Class<?>[]{DataSource.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getConnection")) {
                    connections.incrementAndGet();
                    throw new SQLException("synthetic unavailable database");
                }
                throw new AssertionError("Unexpected database invocation");
            });
    }

    private static LocalPlatformHost.Configuration config(DataSource database, String origin, boolean financial) {
        var runner = session(Set.of("runner:write"));
        var graph = new Session(runner.tenantId(), runner.runId(), "agent-demo", Set.of("intent:read"), Set.of("merchant-demo"), Set.of());
        return new LocalPlatformHost.Configuration(database, runner, graph, new LocalSessions(List.of()), 0, Path.of("ui"), origin, financial);
    }

    private static Session session(Set<String> roles) {
        return new Session("tenant-local", UUID.randomUUID(), "customer-demo", roles, Set.of("merchant-demo"), Set.of());
    }

    @FunctionalInterface private interface Step { boolean run(Session session); }
    private static PaymentOperations operations(Step step) {
        return (PaymentOperations) Proxy.newProxyInstance(LocalPlatformHostTest.class.getClassLoader(), new Class<?>[]{PaymentOperations.class},
            (proxy, method, args) -> {
                if (method.getName().equals("runNext")) return step.run((Session) args[0]);
                throw new AssertionError("Unexpected worker invocation");
            });
    }

    private record OwnedProbe(AtomicInteger closed) implements AutoCloseable {
        @Override public void close() { closed.incrementAndGet(); }
    }
}
