package io.agenticpayments.http;

import io.agenticpayments.application.PaymentOperations;
import io.agenticpayments.application.PaymentService.Session;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Explicitly started local poller. Durable application work owns retry and execution identity. */
public final class PaymentWorker implements AutoCloseable {
    private final PaymentOperations operations;
    private final Session runner;
    private ScheduledExecutorService executor;
    private boolean closed;

    public PaymentWorker(PaymentOperations operations, Session runner) {
        this.operations = Objects.requireNonNull(operations);
        this.runner = Objects.requireNonNull(runner);
    }

    public synchronized void start() {
        if (closed) throw new IllegalStateException("Payment worker is closed");
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "local-payment-worker");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::step, 0, 200, TimeUnit.MILLISECONDS);
    }

    private void step() {
        try { operations.runNext(runner); }
        catch (RuntimeException ignored) {
            // The outer application decorator already records the safe completion category.
            // Later polls use durable state; this poller never retries a financial call itself.
        }
    }

    /** Interrupts polling and bounds waiting even if a replacement ignores interruption. */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (executor == null) return;
        executor.shutdownNow();
        try { executor.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
