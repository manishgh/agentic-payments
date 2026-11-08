package io.agenticpayments.infrastructure.observability;

import io.agenticpayments.observability.TelemetryHealth;
import java.util.concurrent.atomic.AtomicLong;

final class HealthCounters {
    final AtomicLong enqueued = new AtomicLong();
    final AtomicLong dropped = new AtomicLong();
    final AtomicLong failures = new AtomicLong();
    static void add(AtomicLong value, long amount) {
        value.getAndUpdate(current -> current > Long.MAX_VALUE - amount ? Long.MAX_VALUE : current + amount);
    }
    TelemetryHealth snapshot() { return new TelemetryHealth(enqueued.get(), dropped.get(), failures.get()); }
}
