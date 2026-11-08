package io.agenticpayments.observability;

/** Explicit default with no workers, I/O, registry, or retained application values. */
public final class NoopTelemetry {
    private NoopTelemetry() { }
    public static LoggerFactory loggers() {
        return new LoggerFactory() {
            @Override public Logger create(String category) {
                return new Logger() {
                    @Override public boolean isEnabled(LogLevel level) { return false; }
                    @Override public void log(LogLevel level, EventDefinition event, LogFields fields) { }
                    @Override public LogScope beginScope(LogFields fields) { return () -> { }; }
                };
            }
            @Override public ScopeSnapshot captureScope() { return new ScopeSnapshot(LogFields.EMPTY); }
            @Override public LogScope beginScope(ScopeSnapshot snapshot) { return () -> { }; }
        };
    }
    public static MeterFactory meters() {
        return (name, version) -> new Meter() {
            @Override public Counter counter(CounterDefinition definition) { return (value, dimensions) -> { }; }
            @Override public Histogram histogram(HistogramDefinition definition) { return (value, dimensions) -> { }; }
        };
    }
}
