package io.agenticpayments.observability;

/**
 * logging-0.1: all callbacks, including scope disposal, must be bounded and nonblocking on caller threads.
 * Snapshot only catalog-approved typed fields; drop invalid values without changing business outcomes.
 * No exporter, console, network, flush, arbitrary-object serialization or exception callbacks may run here.
 * Replacements have the same obligation: thrown-fault guards cannot isolate synchronous stalls.
 */
public interface Logger {
    boolean isEnabled(LogLevel level);
    void log(LogLevel level, EventDefinition event, LogFields fields);
    LogScope beginScope(LogFields fields);
}
