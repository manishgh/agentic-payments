package io.agenticpayments.infrastructure.observability;

import io.agenticpayments.observability.MetricProvider;
import io.agenticpayments.observability.MetricSnapshot;
import java.util.ArrayDeque;
import java.util.List;

public final class MemoryMetricProvider implements MetricProvider {
    private final int capacity;
    private final ArrayDeque<MetricSnapshot> snapshots = new ArrayDeque<>();
    public MemoryMetricProvider(int capacity) {
        if (capacity < 1 || capacity > 65536) throw new IllegalArgumentException("Invalid memory metric capacity");
        this.capacity = capacity;
    }
    @Override public synchronized void export(MetricSnapshot snapshot) {
        if (snapshots.size() == capacity) snapshots.removeFirst();
        snapshots.addLast(snapshot);
    }
    public synchronized List<MetricSnapshot> snapshots() { return List.copyOf(snapshots); }
}
