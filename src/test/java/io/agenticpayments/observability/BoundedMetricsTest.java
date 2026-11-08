package io.agenticpayments.observability;

import io.agenticpayments.infrastructure.observability.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoundedMetricsTest {
    private static final Map<String, Set<String>> DIMENSIONS = Map.of("operation", Set.of("execute", "retrieve"), "outcome", Set.of("returned", "error"));
    private static final Map<String, String> TAGS = Map.of("operation", "execute", "outcome", "returned");
    private static final CounterDefinition CALLS = new CounterDefinition("service.calls", "calls", DIMENSIONS);
    private static final HistogramDefinition DURATION = new HistogramDefinition("service.duration", "ms", DIMENSIONS, List.of(1.0, 10.0, 100.0));
    private static final MeterDefinition METER = new MeterDefinition("service", "0.1", List.of(CALLS), List.of(DURATION));
    private static final TelemetryOptions OPTIONS = new TelemetryOptions(8, 2, 2, Duration.ofHours(1), Duration.ofMillis(100), LogLevel.INFO);
    private static BoundedMeterFactory factory(List<MetricProvider> providers) {
        return new BoundedMeterFactory(List.of(METER), providers, OPTIONS, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    @Test void aggregatesCumulativeFiniteDataAcrossThreadsWithoutHistoryOrRootLeakage() throws Exception {
        try (BoundedMeterFactory factory = factory(List.of()); BoundedMeterFactory other = factory(List.of())) {
            Meter meter = factory.create("service", "0.1"); Counter counter = meter.counter(CALLS); Histogram histogram = meter.histogram(DURATION);
            assertSame(counter, meter.counter(CALLS)); assertSame(histogram, meter.histogram(DURATION));
            List<Thread> threads = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                Thread thread = new Thread(() -> { for (int call = 0; call < 100; call++) counter.add(1, TAGS); });
                threads.add(thread); thread.start();
            }
            for (Thread thread : threads) { thread.join(2000); assertFalse(thread.isAlive()); }
            for (double value : new double[] {0, 1, 5, 10, 100, 101}) histogram.record(value, TAGS);
            MetricSnapshot snapshot = factory.snapshot();
            assertEquals(Instant.EPOCH, snapshot.timestamp());
            assertEquals(1, snapshot.counters().size()); assertEquals(400, snapshot.counters().getFirst().value());
            MetricSnapshot.HistogramPoint point = snapshot.histograms().getFirst();
            assertEquals(6, point.count()); assertEquals(217.0, point.sum()); assertEquals(List.of(2L, 2L, 1L, 1L), point.bucketCounts());
            assertTrue(other.snapshot().counters().isEmpty());
            counter.add(1, TAGS);
            assertEquals(400, snapshot.counters().getFirst().value(), "snapshots must not alias live accumulators");
            assertThrows(UnsupportedOperationException.class, () -> point.bucketCounts().add(1L));
        }
    }

    @Test void invalidDataCannotCreateSeriesAndOverflowCannotWrapOrBecomeNonfinite() {
        try (BoundedMeterFactory factory = factory(List.of())) {
            Meter meter = factory.create("service", "0.1"); Counter counter = meter.counter(CALLS); Histogram histogram = meter.histogram(DURATION);
            counter.add(-1, TAGS); counter.add(1, Map.of("operation", "peer-private-value", "outcome", "returned"));
            counter.add(1, Map.of()); counter.add(1, null);
            histogram.record(-1, TAGS); histogram.record(Double.NaN, TAGS); histogram.record(Double.POSITIVE_INFINITY, TAGS);
            assertTrue(factory.snapshot().counters().isEmpty()); assertTrue(factory.snapshot().histograms().isEmpty());
            assertEquals(7, factory.health().dropped());
            counter.add(Long.MAX_VALUE, TAGS); counter.add(1, TAGS);
            histogram.record(Double.MAX_VALUE, TAGS); histogram.record(Double.MAX_VALUE, TAGS);
            assertEquals(Long.MAX_VALUE, factory.snapshot().counters().getFirst().value());
            assertEquals(Double.MAX_VALUE, factory.snapshot().histograms().getFirst().sum());
            assertEquals(2, factory.snapshot().histograms().getFirst().count());
        }
    }

    @Test void startupRejectsConflictsUnsafeTagsAndUnboundedCartesianProducts() {
        assertThrows(IllegalArgumentException.class, () -> new MeterDefinition("service", "0.1", List.of(CALLS, CALLS), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new HistogramDefinition("duration", "ms", Map.of(), List.of(2.0, 1.0)));
        assertThrows(IllegalArgumentException.class, () -> new CounterDefinition("calls", "calls", Map.of("correlationId", Set.of("generated"))));
        Set<String> values = java.util.stream.IntStream.range(0, 33).mapToObj(index -> "value" + index).collect(java.util.stream.Collectors.toSet());
        assertThrows(IllegalArgumentException.class, () -> new CounterDefinition("calls", "calls", Map.of("first", values, "second", values)));
        try (BoundedMeterFactory factory = factory(List.of())) {
            Meter meter = factory.create("service", "0.1");
            assertThrows(IllegalArgumentException.class, () -> meter.counter(new CounterDefinition("service.calls", "different", DIMENSIONS)));
            assertThrows(IllegalArgumentException.class, () -> factory.create("service", "0.2"));
        }
    }

    @Test void metricProviderCallbacksAreOffApplicationThreadsAndCloseIsBoundedUnderStall() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), healthy = new CountDownLatch(1), providerClosed = new CountDownLatch(1);
        AtomicReference<Thread> exportThread = new AtomicReference<>(), closeThread = new AtomicReference<>();
        MetricProvider provider = new MetricProvider() {
            @Override public void export(MetricSnapshot snapshot) {
                exportThread.set(Thread.currentThread()); entered.countDown();
                boolean done = false;
                while (!done) {
                    try { release.await(); done = true; } catch (InterruptedException ignored) { }
                }
            }
            @Override public void close() { closeThread.set(Thread.currentThread()); providerClosed.countDown(); }
        };
        TelemetryOptions frequent = new TelemetryOptions(1, 1, 1, Duration.ofMillis(1), Duration.ofMillis(20), LogLevel.INFO);
        BoundedMeterFactory factory = new BoundedMeterFactory(List.of(METER), List.of(provider, snapshot -> healthy.countDown()), frequent);
        try {
            Counter counter = factory.create("service", "0.1").counter(CALLS); counter.add(1, TAGS);
            assertEquals(1, entered.getCount());
            factory.start(); assertTrue(entered.await(2, TimeUnit.SECONDS)); assertTrue(healthy.await(2, TimeUnit.SECONDS));
            assertNotSame(Thread.currentThread(), exportThread.get());
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> { for (int index = 0; index < 100; index++) counter.add(1, TAGS); });
            assertEquals(101, factory.snapshot().counters().getFirst().value());
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> factory.close(Duration.ofMillis(20)));
            assertThrows(IllegalStateException.class, factory::start);
        } finally { release.countDown(); factory.close(Duration.ZERO); }
        // The stalled provider eventually returns and closes on that same provider thread.
        assertTrue(providerClosed.await(2, TimeUnit.SECONDS));
        assertSame(exportThread.get(), closeThread.get());
    }

    @Test void memorySnapshotsAreBoundedAndConsoleExportsOnlyAggregatesAndHealth() {
        MemoryMetricProvider memory = new MemoryMetricProvider(2);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        JsonConsoleMetricProvider json = new JsonConsoleMetricProvider(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        try (BoundedMeterFactory factory = factory(List.of())) {
            factory.create("service", "0.1").counter(CALLS).add(3, TAGS);
            for (int index = 0; index < 4; index++) memory.export(factory.snapshot());
            assertEquals(2, memory.snapshots().size());
            json.export(memory.snapshots().getLast());
            String output = bytes.toString(StandardCharsets.UTF_8);
            assertEquals(2, output.lines().count());
            assertTrue(output.contains("\"value\":3")); assertTrue(output.contains("\"kind\":\"telemetry_health\""));
        }
    }

    @Test void closeBeforeStartIsBoundedEvenWhenOwnedProviderCleanupStalls() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        AtomicInteger exports = new AtomicInteger(), closes = new AtomicInteger();
        MetricProvider provider = new MetricProvider() {
            @Override public void export(MetricSnapshot snapshot) { exports.incrementAndGet(); }
            @Override public void close() {
                closes.incrementAndGet(); entered.countDown();
                boolean done = false;
                while (!done) {
                    try { release.await(); done = true; } catch (InterruptedException ignored) { }
                }
                finished.countDown();
            }
        };
        BoundedMeterFactory factory = factory(List.of(provider));
        factory.create("service", "0.1").counter(CALLS).add(1, TAGS);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> factory.close(Duration.ofMillis(20)));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            factory.close(Duration.ofMillis(20));
            assertEquals(0, exports.get()); assertEquals(1, closes.get());
        } finally { release.countDown(); factory.close(Duration.ZERO); }
        assertTrue(finished.await(2, TimeUnit.SECONDS));
    }
}
