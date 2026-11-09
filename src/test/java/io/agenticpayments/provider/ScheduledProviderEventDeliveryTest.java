package io.agenticpayments.provider;

import io.agenticpayments.application.instrumentation.ServiceTelemetry;
import io.agenticpayments.infrastructure.provider.ScheduledProviderEventDelivery;
import io.agenticpayments.observability.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ScheduledProviderEventDeliveryTest {
    @Test void startsExplicitlyAndContinuesAfterFailureWithOnlyCatalogTelemetry() throws Exception {
        var calls = new AtomicInteger();
        var completed = new CountDownLatch(1);
        var observations = new CopyOnWriteArrayList<LogFields>();
        var dimensions = new CopyOnWriteArrayList<Map<String, String>>();
        var telemetry = new ServiceTelemetry(loggers(observations), (name, version) -> new Meter() {
            @Override public Counter counter(CounterDefinition definition) { return (value, labels) -> dimensions.add(labels); }
            @Override public Histogram histogram(HistogramDefinition definition) { return (value, labels) -> dimensions.add(labels); }
        }, System::nanoTime);
        String secret = "private-event-payload-key-canary";
        var runtime = new ScheduledProviderEventDelivery(() -> {
            if (calls.incrementAndGet() == 1) throw new IllegalArgumentException(secret);
            completed.countDown();
            return true;
        }, telemetry, Duration.ofMillis(10), Duration.ofSeconds(1));
        assertEquals(0, calls.get());
        try {
            runtime.start();
            runtime.start();
            assertTrue(completed.await(3, TimeUnit.SECONDS));
        } finally { runtime.close(); }
        int stoppedAt = calls.get();
        runtime.close();
        assertEquals(stoppedAt, calls.get());
        assertThrows(IllegalStateException.class, runtime::start);
        assertTrue(observations.size() >= 2);
        assertTrue(dimensions.size() >= 4);
        assertTrue(observations.stream().anyMatch(f -> f.values().get("failure").equals(LogValue.finite("INTERNAL"))));
        for (var fields : observations) {
            assertEquals(LogValue.finite("PROVIDER_EVENT_DELIVERY"), fields.values().get("operation"));
            assertEquals(java.util.Set.of("operation", "outcome", "failure"), fields.values().keySet());
            assertFalse(fields.toString().contains(secret));
        }
        assertFalse(dimensions.toString().contains(secret));
    }

    @Test void interruptsActiveWorkAndNeverOverlapsPolls() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var runtime = new ScheduledProviderEventDelivery(() -> {
            calls.incrementAndGet();
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException expected) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            return false;
        }, telemetry(), Duration.ofMillis(10), Duration.ofSeconds(1));
        try {
            runtime.start();
            runtime.start();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
        } finally { runtime.close(); }
        assertEquals(0, interrupted.getCount());
        assertEquals(1, calls.get());
    }

    @Test void boundsCloseWhenReplacementIgnoresInterruption() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var returned = new CountDownLatch(1);
        var runtime = new ScheduledProviderEventDelivery(() -> {
            entered.countDown();
            while (release.getCount() != 0) {
                try { release.await(); } catch (InterruptedException ignored) { }
            }
            returned.countDown();
            return false;
        }, telemetry(), Duration.ofMillis(10), Duration.ofMillis(50));
        try {
            runtime.start();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            long start = System.nanoTime();
            runtime.close();
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1));
            assertEquals(1, returned.getCount());
        } finally {
            release.countDown();
            runtime.close();
        }
        assertTrue(returned.await(3, TimeUnit.SECONDS));
    }

    @Test void telemetryReplacementFailuresCannotStopDurablePolling() throws Exception {
        var completed = new CountDownLatch(2);
        var telemetry = new ServiceTelemetry(new LoggerFactory() {
            @Override public Logger create(String category) { throw new AssertionError(); }
            @Override public ScopeSnapshot captureScope() { throw new AssertionError(); }
            @Override public LogScope beginScope(ScopeSnapshot snapshot) { throw new AssertionError(); }
        },
                (name, version) -> { throw new AssertionError(); }, () -> { throw new AssertionError(); });
        try (var runtime = new ScheduledProviderEventDelivery(() -> { completed.countDown(); return false; },
                telemetry, Duration.ofMillis(10), Duration.ofSeconds(1))) {
            runtime.start();
            assertTrue(completed.await(3, TimeUnit.SECONDS));
        }
    }

    @Test void closeBeforeStartAndInvalidTimingFailWithoutStartingWork() {
        var calls = new AtomicInteger();
        var runtime = new ScheduledProviderEventDelivery(() -> { calls.incrementAndGet(); return false; }, telemetry());
        runtime.close();
        runtime.close();
        assertThrows(IllegalStateException.class, runtime::start);
        assertEquals(0, calls.get());
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofMillis(9), Duration.ofSeconds(61)))
            assertThrows(IllegalArgumentException.class, () -> new ScheduledProviderEventDelivery(() -> false, telemetry(), invalid, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new ScheduledProviderEventDelivery(() -> false, telemetry(), Duration.ofMillis(10), Duration.ofSeconds(3)));
    }

    private static ServiceTelemetry telemetry() {
        return new ServiceTelemetry(NoopTelemetry.loggers(), NoopTelemetry.meters(), System::nanoTime);
    }
    private static LoggerFactory loggers(List<LogFields> observations) {
        return new LoggerFactory() {
            @Override public Logger create(String category) {
                return new Logger() {
                    @Override public boolean isEnabled(LogLevel level) { return true; }
                    @Override public void log(LogLevel level, EventDefinition event, LogFields fields) { observations.add(fields); }
                    @Override public LogScope beginScope(LogFields fields) { return () -> { }; }
                };
            }
            @Override public ScopeSnapshot captureScope() { return new ScopeSnapshot(LogFields.EMPTY); }
            @Override public LogScope beginScope(ScopeSnapshot snapshot) { return () -> { }; }
        };
    }
}
