package io.agenticpayments.http;

import io.agenticpayments.application.instrumentation.ServiceTelemetry;
import io.agenticpayments.infrastructure.observability.*;
import io.agenticpayments.observability.*;
import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Local host ownership of optional exporters; payment services only receive the factory interfaces. */
public final class LocalTelemetry implements AutoCloseable {
    private final LoggerFactory loggers;
    private final MeterFactory meters;
    private final BoundedLoggerFactory boundedLogs;
    private final BoundedMeterFactory boundedMetrics;
    private final Duration shutdownTimeout;
    private boolean closed;

    private LocalTelemetry(LoggerFactory loggers, MeterFactory meters, BoundedLoggerFactory boundedLogs,
                           BoundedMeterFactory boundedMetrics, Duration shutdownTimeout) {
        this.loggers = loggers; this.meters = meters;
        this.boundedLogs = boundedLogs; this.boundedMetrics = boundedMetrics;
        this.shutdownTimeout = shutdownTimeout;
    }
    public static LocalTelemetry disabled() {
        return new LocalTelemetry(NoopTelemetry.loggers(), NoopTelemetry.meters(), null, null, Duration.ZERO);
    }
    public static LocalTelemetry console(PrintStream output) {
        java.util.Objects.requireNonNull(output);
        return providers(List.of(new JsonConsoleLogProvider(output)), List.of(new JsonConsoleMetricProvider(output)), TelemetryOptions.defaults());
    }
    /** Takes ownership of providers when their respective bounded factory is successfully constructed. */
    public static LocalTelemetry providers(List<LogProvider> logProviders, List<MetricProvider> metricProviders, TelemetryOptions options) {
        var logs = new BoundedLoggerFactory(Set.of(ServiceTelemetry.CATEGORY), List.of(ServiceTelemetry.COMPLETED),
            Map.of(), logProviders, options);
        try {
            var metrics = new BoundedMeterFactory(List.of(ServiceTelemetry.DEFINITION), metricProviders, options);
            return new LocalTelemetry(logs, metrics, logs, metrics, options.shutdownTimeout());
        } catch (RuntimeException | Error failure) {
            logs.close();
            throw failure;
        }
    }
    public LoggerFactory loggers() { return loggers; }
    public MeterFactory meters() { return meters; }
    public synchronized void start() {
        if (closed) throw new IllegalStateException("Telemetry is closed");
        if (boundedLogs != null) boundedLogs.start();
        if (boundedMetrics != null) boundedMetrics.start();
    }
    /** One shared exporter deadline, including when a provider ignores interruption. */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        long started = System.nanoTime();
        if (boundedLogs != null) boundedLogs.close(shutdownTimeout);
        long remaining = Math.max(0, shutdownTimeout.toNanos() - (System.nanoTime() - started));
        if (boundedMetrics != null) boundedMetrics.close(Duration.ofNanos(remaining));
    }
}
