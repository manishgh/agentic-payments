package io.agenticpayments.infrastructure.observability;

import io.agenticpayments.observability.LogProvider;
import io.agenticpayments.observability.LogRecord;
import java.util.ArrayDeque;
import java.util.List;

/** A diagnostic ring buffer, never an audit trail. */
public final class MemoryLogProvider implements LogProvider {
    private final int capacity;
    private final ArrayDeque<LogRecord> records = new ArrayDeque<>();
    public MemoryLogProvider(int capacity) {
        if (capacity < 1 || capacity > 65536) throw new IllegalArgumentException("Invalid memory log capacity");
        this.capacity = capacity;
    }
    @Override public synchronized void export(List<LogRecord> batch) {
        for (LogRecord record : batch) {
            if (records.size() == capacity) records.removeFirst();
            records.addLast(record);
        }
    }
    public synchronized List<LogRecord> records() { return List.copyOf(records); }
}
