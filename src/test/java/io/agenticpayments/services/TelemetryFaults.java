package io.agenticpayments.services;

import io.agenticpayments.application.instrumentation.*;
import io.agenticpayments.observability.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Deliberately broken replacement contracts, independent of the bundled providers. */
final class TelemetryFaults {
    enum Fault { LOGGER_CREATE, LOGGER_ENABLED, LOGGER_WRITE, METER_CREATE, COUNTER_CREATE, COUNTER_WRITE,
        HISTOGRAM_CREATE, HISTOGRAM_WRITE, CLOCK_START, CLOCK_END }
    enum Failure { RUNTIME, ASSERTION, LINKAGE }
    final AtomicInteger failures = new AtomicInteger();
    final ServiceTelemetry telemetry;
    private final Failure failure;

    TelemetryFaults(Fault fault) { this(fault, Failure.RUNTIME); }
    TelemetryFaults(Fault fault, Failure failure) {
        this.failure = failure;
        Logger logger = new Logger() {
            public boolean isEnabled(LogLevel level) { fail(fault, Fault.LOGGER_ENABLED); return true; }
            public void log(LogLevel level, EventDefinition event, LogFields fields) { fail(fault, Fault.LOGGER_WRITE); }
            public LogScope beginScope(LogFields fields) { throw new AssertionError("Unexpected logging scope"); }
        };
        LoggerFactory loggers = new LoggerFactory() {
            public Logger create(String category) { fail(fault, Fault.LOGGER_CREATE); return logger; }
            public ScopeSnapshot captureScope() { throw new AssertionError("Unexpected scope capture"); }
            public LogScope beginScope(ScopeSnapshot snapshot) { throw new AssertionError("Unexpected factory scope"); }
        };
        Meter meter = new Meter() {
            public Counter counter(CounterDefinition definition) {
                fail(fault, Fault.COUNTER_CREATE); return (value, dimensions) -> fail(fault, Fault.COUNTER_WRITE);
            }
            public Histogram histogram(HistogramDefinition definition) {
                fail(fault, Fault.HISTOGRAM_CREATE); return (value, dimensions) -> fail(fault, Fault.HISTOGRAM_WRITE);
            }
        };
        MeterFactory meters = (name, version) -> { fail(fault, Fault.METER_CREATE); return meter; };
        AtomicInteger ticks = new AtomicInteger();
        MonotonicClock clock = () -> {
            int tick = ticks.incrementAndGet();
            fail(fault, tick == 1 ? Fault.CLOCK_START : Fault.CLOCK_END);
            return tick * 1_000_000L;
        };
        telemetry = new ServiceTelemetry(loggers, meters, clock);
    }

    private void fail(Fault selected, Fault actual) {
        if (selected == actual) {
            failures.incrementAndGet();
            switch (failure) {
                case RUNTIME -> throw new IllegalStateException("private-telemetry-canary");
                case ASSERTION -> throw new AssertionError("private-telemetry-canary");
                case LINKAGE -> throw new LinkageError("private-telemetry-canary");
            }
        }
    }
}
