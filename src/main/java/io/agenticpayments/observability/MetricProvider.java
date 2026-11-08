package io.agenticpayments.observability;

/**
 * metrics-0.1 aggregate exporter. A successfully constructed bounded factory owns its registered
 * provider and invokes export and close only on its isolated dispatcher/cleanup thread, never on
 * application threads or under application locks. Close is attempted exactly once, even before start.
 * Queues are bounded, lossy and best effort; FIFO is per provider, without durable delivery guarantees.
 * The host may abandon a stalled export/close after its shutdown deadline and discard remaining data.
 */
@FunctionalInterface
public interface MetricProvider extends AutoCloseable {
    void export(MetricSnapshot snapshot);
    @Override default void close() { }
}
