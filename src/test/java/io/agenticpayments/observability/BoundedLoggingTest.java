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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoundedLoggingTest {
    private static final FieldDefinition OPERATION = FieldDefinition.finite(Set.of("execute", "retrieve"));
    private static final EventDefinition EVENT = new EventDefinition("completed", "Service completed", Map.of("operation", OPERATION));
    private static final TelemetryOptions OPTIONS = new TelemetryOptions(16, 4, 8, Duration.ofHours(1), Duration.ofMillis(100), LogLevel.INFO);
    private static BoundedLoggerFactory factory(List<LogProvider> providers) {
        return new BoundedLoggerFactory(Set.of("service"), List.of(EVENT), Map.of("correlation", FieldDefinition.correlation()), providers, OPTIONS,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    }
    private static void complete(Logger logger) { logger.log(LogLevel.INFO, EVENT, LogFields.of(Map.of("operation", LogValue.finite("execute")))); }

    @Test void snapshotsOnlyApprovedFieldsAndStartsExportExplicitly() throws Exception {
        MemoryLogProvider memory = new MemoryLogProvider(3);
        CountDownLatch exported = new CountDownLatch(1);
        AtomicReference<Thread> exporterThread = new AtomicReference<>();
        try (BoundedLoggerFactory factory = factory(List.of(batch -> { exporterThread.set(Thread.currentThread()); memory.export(batch); exported.countDown(); }))) {
            Logger logger = factory.create("service");
            Map<String, LogValue> input = new HashMap<>();
            input.put("operation", LogValue.finite("execute"));
            input.put("body", LogValue.finite("private-peer-body"));
            LogFields fields = LogFields.of(input);
            input.put("operation", LogValue.finite("retrieve"));
            logger.log(LogLevel.INFO, EVENT, fields);
            assertEquals(1, exported.getCount(), "constructing and enqueueing must not call providers");
            factory.start();
            assertTrue(exported.await(2, TimeUnit.SECONDS));
            assertNotSame(Thread.currentThread(), exporterThread.get());
            LogRecord record = memory.records().getFirst();
            assertEquals(Map.of("operation", LogValue.finite("execute")), record.fields().values());
            assertEquals(Instant.parse("2026-01-01T00:00:00Z"), record.timestamp());
            assertEquals(1, factory.health().dropped());
            assertThrows(UnsupportedOperationException.class, () -> record.fields().values().put("x", LogValue.flag(true)));
            assertThrows(IllegalArgumentException.class, () -> factory.create("unregistered"));
            logger.log(LogLevel.INFO, new EventDefinition("completed", "Peer text", Map.of()), LogFields.EMPTY);
            assertEquals(2, factory.health().dropped());
        }
    }

    @Test void scopesAreRootLocalNoninheritableAndRequireExplicitValidatedPropagation() throws Exception {
        try (BoundedLoggerFactory first = factory(List.of()); BoundedLoggerFactory second = factory(List.of())) {
            Logger logger = first.create("service");
            CorrelationId correlation = CorrelationId.generate();
            try (LogScope ignored = logger.beginScope(LogFields.of(Map.of("correlation", LogValue.correlation(correlation))))) {
                ScopeSnapshot snapshot = first.captureScope();
                assertEquals(1, snapshot.fields().values().size());
                assertTrue(second.captureScope().fields().values().isEmpty());
                AtomicReference<ScopeSnapshot> before = new AtomicReference<>(), during = new AtomicReference<>(), after = new AtomicReference<>();
                Thread thread = new Thread(() -> {
                    before.set(first.captureScope());
                    try (LogScope restored = first.beginScope(snapshot)) { during.set(first.captureScope()); }
                    after.set(first.captureScope());
                });
                thread.start(); thread.join(2000);
                assertFalse(thread.isAlive());
                assertTrue(before.get().fields().values().isEmpty());
                assertEquals(snapshot, during.get());
                assertTrue(after.get().fields().values().isEmpty());
            }
            assertTrue(first.captureScope().fields().values().isEmpty());
            try (LogScope ignored = first.beginScope(new ScopeSnapshot(LogFields.of(Map.of("body", LogValue.finite("secret")))))) {
                assertTrue(first.captureScope().fields().values().isEmpty());
            }
        }
    }

    @Test void outOfOrderAndWrongThreadDisposalClearOwnerStateWithoutThrowing() throws Exception {
        try (BoundedLoggerFactory factory = factory(List.of())) {
            Logger logger = factory.create("service");
            LogFields fields = LogFields.of(Map.of("correlation", LogValue.correlation(CorrelationId.generate())));
            LogScope outer = logger.beginScope(fields), inner = logger.beginScope(fields);
            outer.close(); assertTrue(factory.captureScope().fields().values().isEmpty());
            inner.close(); inner.close();
            LogScope wrongThread = logger.beginScope(fields);
            Thread thread = new Thread(wrongThread::close); thread.start(); thread.join(2000);
            assertFalse(thread.isAlive());
            assertTrue(factory.captureScope().fields().values().isEmpty());
        }
    }

    @Test void stalledAndBrokenProvidersAreIsolatedAndShutdownSharesOneDeadline() throws Exception {
        CountDownLatch stalled = new CountDownLatch(2), release = new CountDownLatch(1), healthy = new CountDownLatch(1);
        LogProvider stall = batch -> {
            stalled.countDown();
            boolean done = false;
            while (!done) {
                try { release.await(); done = true; } catch (InterruptedException ignored) { }
            }
        };
        LogProvider secondStall = batch -> stall.export(batch);
        TelemetryOptions tiny = new TelemetryOptions(1, 1, 1, Duration.ofHours(1), Duration.ofMillis(25), LogLevel.INFO);
        BoundedLoggerFactory factory = new BoundedLoggerFactory(Set.of("service"), List.of(EVENT), Map.of(),
                List.of(stall, secondStall, batch -> { throw new IllegalStateException("private failure"); }, batch -> healthy.countDown()), tiny);
        try {
            Logger logger = factory.create("service"); factory.start(); complete(logger);
            assertTrue(stalled.await(2, TimeUnit.SECONDS)); assertTrue(healthy.await(2, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> { for (int index = 0; index < 100; index++) complete(logger); });
            assertTrue(factory.health().dropped() > 0);
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> factory.close(Duration.ofMillis(25)));
            factory.close();
            assertThrows(IllegalStateException.class, factory::start);
        } finally { release.countDown(); factory.close(Duration.ZERO); }
    }

    @Test void memoryRetentionAndJsonContainOnlyBoundedCatalogData() throws Exception {
        MemoryLogProvider memory = new MemoryLogProvider(2);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream stream = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        JsonConsoleLogProvider json = new JsonConsoleLogProvider(stream);
        List<LogRecord> records = new ArrayList<>();
        for (int index = 0; index < 4; index++) records.add(new LogRecord(Instant.EPOCH, "service", LogLevel.INFO, EVENT,
                LogFields.of(Map.of("operation", LogValue.finite("execute")))));
        memory.export(records); json.export(memory.records());
        assertEquals(2, memory.records().size());
        assertEquals(2, bytes.toString(StandardCharsets.UTF_8).lines().count());
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("\"operation\":\"execute\""));
        assertThrows(IllegalArgumentException.class, () -> new EventDefinition("event", "template", Map.of("customerId", OPERATION)));
    }

    @Test void closeBeforeStartDiscardsQueuedLogsAndClosesOwnedProvidersOnceOffThread() throws Exception {
        AtomicInteger exports = new AtomicInteger(), closes = new AtomicInteger();
        AtomicReference<Thread> closeThread = new AtomicReference<>();
        CountDownLatch closed = new CountDownLatch(1);
        LogProvider provider = new LogProvider() {
            @Override public void export(List<LogRecord> batch) { exports.incrementAndGet(); }
            @Override public void close() {
                closeThread.set(Thread.currentThread()); closes.incrementAndGet(); closed.countDown();
            }
        };
        BoundedLoggerFactory factory = factory(List.of(provider));
        complete(factory.create("service"));
        factory.close(Duration.ofSeconds(1)); factory.close(Duration.ofSeconds(1));
        assertTrue(closed.await(2, TimeUnit.SECONDS));
        assertEquals(0, exports.get()); assertEquals(1, closes.get());
        assertNotSame(Thread.currentThread(), closeThread.get());
        assertEquals(1, factory.health().dropped());
    }

    @Test void nonfatalErrorsAreCountedWithoutKillingTheExporterOrCleanup() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch failed = new CountDownLatch(1), recovered = new CountDownLatch(1);
        LogProvider provider = new LogProvider() {
            @Override public void export(List<LogRecord> batch) {
                if (attempts.getAndIncrement() == 0) {
                    failed.countDown(); throw new AssertionError("private exporter error");
                }
                recovered.countDown();
            }
            @Override public void close() { throw new LinkageError("private cleanup error"); }
        };
        BoundedLoggerFactory factory = factory(List.of(provider));
        try {
            Logger logger = factory.create("service"); factory.start(); complete(logger);
            assertTrue(failed.await(2, TimeUnit.SECONDS));
            complete(logger); assertTrue(recovered.await(2, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> factory.close(Duration.ofSeconds(1)));
            assertEquals(2, factory.health().exportFailures());
            assertEquals(2, attempts.get());
        } finally { factory.close(Duration.ZERO); }
    }
}
