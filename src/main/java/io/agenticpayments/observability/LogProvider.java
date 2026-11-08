package io.agenticpayments.observability;

import java.util.List;

/**
 * logging-0.1 batch exporter. A successfully constructed bounded factory owns its registered provider
 * and invokes export and close only on that provider's isolated dispatcher/cleanup thread, never on
 * application threads or under application locks. Close is attempted exactly once, even before start.
 * Queues are bounded, lossy and best effort; FIFO is per provider, without durable delivery guarantees.
 * The host may abandon a stalled export/close after its shutdown deadline and discard remaining data.
 */
@FunctionalInterface
public interface LogProvider extends AutoCloseable {
    void export(List<LogRecord> batch);
    @Override default void close() { }
}
