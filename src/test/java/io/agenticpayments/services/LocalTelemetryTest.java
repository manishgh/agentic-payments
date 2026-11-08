package io.agenticpayments.services;

import io.agenticpayments.application.instrumentation.ServiceTelemetry;
import io.agenticpayments.http.LocalTelemetry;
import io.agenticpayments.infrastructure.observability.TelemetryOptions;
import io.agenticpayments.observability.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class LocalTelemetryTest {
    @Test void bothStalledExportersShareOneShutdownDeadline() throws Exception {
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1); var closed = new CountDownLatch(2);
        var telemetry = LocalTelemetry.providers(List.of(new LogProvider() {
            public void export(List<LogRecord> records) { entered.countDown(); stall(release); }
            public void close() { closed.countDown(); }
        }), List.of(new MetricProvider() {
            public void export(MetricSnapshot snapshot) { entered.countDown(); stall(release); }
            public void close() { closed.countDown(); }
        }), new TelemetryOptions(16, 4, 16, Duration.ofMillis(10), Duration.ofSeconds(1), LogLevel.INFO));
        try {
            telemetry.start();
            new ServiceTelemetry(telemetry.loggers(), telemetry.meters(), System::nanoTime)
                .call(ServiceTelemetry.Operation.CREATE, () -> "result");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            long started = System.nanoTime();
            telemetry.close(); telemetry.close();
            assertTrue(System.nanoTime() - started < Duration.ofMillis(1800).toNanos(), "Both exporters must share one 1s deadline");
        } finally { release.countDown(); telemetry.close(); }
        assertTrue(closed.await(5, TimeUnit.SECONDS));
    }

    @Test void invalidMetricRegistrationUnwindsOwnedLogProviderOffThread() throws Exception {
        var closed = new CountDownLatch(1); var closeThread = new AtomicReference<Thread>();
        LogProvider logs = new LogProvider() {
            public void export(List<LogRecord> records) { fail("Must not export before startup"); }
            public void close() { closeThread.set(Thread.currentThread()); closed.countDown(); }
        };
        MetricProvider metrics = new MetricProvider() {
            public void export(MetricSnapshot snapshot) { fail("Invalid registration must not start"); }
            public void close() { fail("Failed metric construction did not acquire ownership"); }
        };
        assertThrows(IllegalArgumentException.class,
            () -> LocalTelemetry.providers(List.of(logs), List.of(metrics, metrics), TelemetryOptions.defaults()));
        assertTrue(closed.await(5, TimeUnit.SECONDS));
        assertNotSame(Thread.currentThread(), closeThread.get());
    }

    private static void stall(CountDownLatch release) {
        boolean done = false;
        while (!done) {
            try { release.await(); done = true; }
            catch (InterruptedException ignored) { /* Deliberately misbehaving exporter. */ }
        }
    }

    @Test void hostTelemetryOwnsProvidersAndExportsOnlyAfterExplicitStartup() throws Exception {
        var logBatches = new AtomicInteger(); var snapshots = new AtomicInteger();
        var closed = new CountDownLatch(2);
        var exported = new CountDownLatch(2);
        var telemetry = LocalTelemetry.providers(List.of(new LogProvider() {
            public void export(List<LogRecord> records) { if (logBatches.incrementAndGet() == 1) exported.countDown(); }
            public void close() { closed.countDown(); }
        }), List.of(new MetricProvider() {
            public void export(MetricSnapshot snapshot) { if (snapshots.incrementAndGet() == 1) exported.countDown(); }
            public void close() { closed.countDown(); }
        }), new TelemetryOptions(16, 4, 16, Duration.ofMillis(10), Duration.ofMillis(250), LogLevel.INFO));
        try {
            var services = new ServiceTelemetry(telemetry.loggers(), telemetry.meters(), System::nanoTime);
            assertEquals("result", services.call(ServiceTelemetry.Operation.CREATE, () -> "result"));
            assertEquals(0, logBatches.get()); assertEquals(0, snapshots.get());
            telemetry.start(); telemetry.start();
            assertTrue(exported.await(5, TimeUnit.SECONDS));
        } finally { telemetry.close(); telemetry.close(); }
        assertTrue(closed.await(5, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, telemetry::start);
    }
}
