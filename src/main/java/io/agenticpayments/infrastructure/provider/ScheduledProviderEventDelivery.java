package io.agenticpayments.infrastructure.provider;

import io.agenticpayments.application.ProviderEventDeliveryRuntime;
import io.agenticpayments.application.ProviderEventDeliveryWork;
import io.agenticpayments.application.instrumentation.ServiceTelemetry;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Single bounded-step poller; durable queue ownership and retries belong to the application. */
public final class ScheduledProviderEventDelivery implements ProviderEventDeliveryRuntime {
    private final ProviderEventDeliveryWork work;
    private final ServiceTelemetry telemetry;
    private final Duration pollDelay;
    private final Duration shutdownTimeout;
    private ScheduledExecutorService executor;
    private volatile boolean closed;

    public ScheduledProviderEventDelivery(ProviderEventDeliveryWork work, ServiceTelemetry telemetry) {
        this(work, telemetry, Duration.ofMillis(200), Duration.ofSeconds(2));
    }

    public ScheduledProviderEventDelivery(ProviderEventDeliveryWork work, ServiceTelemetry telemetry,
                                          Duration pollDelay, Duration shutdownTimeout) {
        this.work = Objects.requireNonNull(work);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.pollDelay = Objects.requireNonNull(pollDelay);
        this.shutdownTimeout = Objects.requireNonNull(shutdownTimeout);
        if (pollDelay.compareTo(Duration.ofMillis(10)) < 0 || pollDelay.compareTo(Duration.ofSeconds(60)) > 0
                || shutdownTimeout.isNegative() || shutdownTimeout.compareTo(Duration.ofSeconds(2)) > 0)
            throw new IllegalArgumentException("Invalid provider event runtime timing");
    }

    @Override public synchronized void start() {
        if (closed) throw new IllegalStateException("Provider event delivery runtime is closed");
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "local-provider-event-delivery");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::step, 0, pollDelay.toNanos(), TimeUnit.NANOSECONDS);
    }

    private void step() {
        if (closed) return;
        try {
            telemetry.call(ServiceTelemetry.Operation.PROVIDER_EVENT_DELIVERY, work::runOnce);
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath fatal) throw fatal;
            // Telemetry observes fixed catalog categories only. Later polls consult durable work;
            // neither peer payloads nor exception text are exported or retained by this runtime.
        }
    }

    /** Stop new polls and interrupt work, bounding waiting even for an uncooperative replacement. */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (executor == null) return;
        executor.shutdownNow();
        try { executor.awaitTermination(shutdownTimeout.toNanos(), TimeUnit.NANOSECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
