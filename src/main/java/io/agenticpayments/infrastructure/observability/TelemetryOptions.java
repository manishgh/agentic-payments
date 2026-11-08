package io.agenticpayments.infrastructure.observability;

import io.agenticpayments.observability.LogLevel;
import java.time.Duration;

/** Trusted startup limits. No background work starts while constructing options or factories. */
public record TelemetryOptions(int queueCapacity, int batchSize, int memoryCapacity, Duration exportInterval,
                               Duration shutdownTimeout, LogLevel minimumLevel) {
    public TelemetryOptions {
        if (queueCapacity < 1 || queueCapacity > 65536 || batchSize < 1 || batchSize > queueCapacity
                || memoryCapacity < 1 || memoryCapacity > 65536 || exportInterval == null
                || exportInterval.compareTo(Duration.ofMillis(1)) < 0 || exportInterval.compareTo(Duration.ofHours(1)) > 0
                || shutdownTimeout == null || shutdownTimeout.isNegative() || shutdownTimeout.compareTo(Duration.ofMinutes(1)) > 0
                || minimumLevel == null) throw new IllegalArgumentException("Invalid telemetry limits");
    }
    public static TelemetryOptions defaults() {
        return new TelemetryOptions(256, 32, 256, Duration.ofSeconds(10), Duration.ofMillis(250), LogLevel.INFO);
    }
}
