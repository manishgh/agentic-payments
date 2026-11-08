package io.agenticpayments.infrastructure.observability;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** One bounded queue and daemon per provider. Provider code never runs under a queue lock. */
final class ProviderDispatcher<T> {
    private final ArrayBlockingQueue<T> queue;
    private final Consumer<List<T>> export;
    private final Runnable closeProvider;
    private final HealthCounters health;
    private final int batchSize;
    private volatile boolean closing;
    private volatile boolean stopped;
    private Thread worker;

    ProviderDispatcher(TelemetryOptions options, Consumer<List<T>> export, Runnable closeProvider, HealthCounters health) {
        queue = new ArrayBlockingQueue<>(options.queueCapacity()); this.batchSize = options.batchSize();
        this.export = export; this.closeProvider = closeProvider; this.health = health;
    }
    synchronized void start() {
        if (closing) throw new IllegalStateException("Telemetry dispatcher is closed");
        if (worker != null) return;
        worker = new Thread(this::run, "telemetry-provider");
        worker.setDaemon(true); worker.start();
    }
    void offer(T item) {
        if (closing || !queue.offer(item)) HealthCounters.add(health.dropped, 1);
        else HealthCounters.add(health.enqueued, 1);
    }
    private void run() {
        try {
            while (!stopped && (!closing || !queue.isEmpty())) {
                T first;
                try { first = queue.poll(25, TimeUnit.MILLISECONDS); }
                catch (InterruptedException interrupted) { continue; }
                if (first == null) continue;
                List<T> batch = new ArrayList<>(batchSize); batch.add(first);
                queue.drainTo(batch, batchSize - 1);
                try { export.accept(List.copyOf(batch)); }
                catch (Throwable failure) { TelemetryFaults.rethrowFatal(failure); HealthCounters.add(health.failures, 1); }
            }
        } finally {
            cleanup();
        }
    }
    private void cleanup() {
        try { closeProvider.run(); }
        catch (Throwable failure) { TelemetryFaults.rethrowFatal(failure); HealthCounters.add(health.failures, 1); }
    }
    synchronized void beginClose() { closing = true; }
    void awaitClose(long deadline) {
        Thread active;
        synchronized (this) {
            if (worker == null) {
                // Startup validation may fail before dispatch starts. Own cleanup still runs in
                // isolation, but queued application records must never be exported in this path.
                stopped = true;
                int discarded = queue.size(); queue.clear(); HealthCounters.add(health.dropped, discarded);
                worker = new Thread(this::cleanup, "telemetry-provider-cleanup");
                worker.setDaemon(true); worker.start();
            }
            active = worker;
        }
        if (active != null && active != Thread.currentThread()) {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                try { TimeUnit.NANOSECONDS.timedJoin(active, remaining); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }
        stopped = true;
        int discarded = queue.size(); queue.clear(); HealthCounters.add(health.dropped, discarded);
        if (active != null && active.isAlive()) active.interrupt();
    }
    static long deadline(Duration timeout) {
        if (timeout == null || timeout.isNegative()) return System.nanoTime();
        long nanos;
        try { nanos = timeout.toNanos(); } catch (ArithmeticException overflow) { nanos = Long.MAX_VALUE / 2; }
        return System.nanoTime() + Math.min(nanos, Long.MAX_VALUE / 2);
    }
}
