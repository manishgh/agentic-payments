package io.agenticpayments.infrastructure.observability;

import io.agenticpayments.observability.*;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Per-root finite cumulative aggregation. Application threads never invoke providers. */
public final class BoundedMeterFactory implements MeterFactory, AutoCloseable {
    private final Map<MeterKey, CatalogMeter> meters;
    private final List<ProviderDispatcher<MetricSnapshot>> dispatchers;
    private final HealthCounters health = new HealthCounters();
    private final TelemetryOptions options;
    private final Clock clock;
    private volatile boolean closed;
    private Thread publisher;

    public BoundedMeterFactory(List<MeterDefinition> meters, List<MetricProvider> providers, TelemetryOptions options) {
        this(meters, providers, options, Clock.systemUTC());
    }
    public BoundedMeterFactory(List<MeterDefinition> meters, List<MetricProvider> providers, TelemetryOptions options, Clock clock) {
        if (meters == null || meters.size() > 32 || providers == null || providers.size() > 16 || options == null || clock == null) {
            throw new IllegalArgumentException("Invalid meter catalog");
        }
        this.options = options; this.clock = clock;
        Map<MeterKey, CatalogMeter> catalog = new HashMap<>();
        long totalSeries = 0;
        for (MeterDefinition definition : meters) {
            for (CounterDefinition counter : definition.counters()) totalSeries += cardinality(counter.dimensions());
            for (HistogramDefinition histogram : definition.histograms()) totalSeries += cardinality(histogram.dimensions());
            if (totalSeries > 16384) throw new IllegalArgumentException("Root metric cardinality exceeds limit");
            if (catalog.putIfAbsent(new MeterKey(definition.name(), definition.version()), new CatalogMeter(definition)) != null) {
                throw new IllegalArgumentException("Duplicate meter definition");
            }
        }
        this.meters = Map.copyOf(catalog);
        List<ProviderDispatcher<MetricSnapshot>> queues = new ArrayList<>();
        Set<MetricProvider> identities = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (MetricProvider provider : providers) {
            if (provider == null || !identities.add(provider)) throw new IllegalArgumentException("Duplicate metric provider");
            queues.add(new ProviderDispatcher<>(options, batch -> batch.forEach(provider::export), provider::close, health));
        }
        dispatchers = List.copyOf(queues);
    }
    private static long cardinality(Map<String, Set<String>> dimensions) {
        long count = 1;
        for (Set<String> values : dimensions.values()) count *= values.size();
        return count;
    }
    public synchronized void start() {
        if (closed) throw new IllegalStateException("Meter factory is closed");
        if (publisher != null) return;
        dispatchers.forEach(ProviderDispatcher::start);
        publisher = new Thread(this::publish, "telemetry-metrics");
        publisher.setDaemon(true); publisher.start();
    }
    private void publish() {
        while (!closed) {
            try { TimeUnit.NANOSECONDS.sleep(options.exportInterval().toNanos()); }
            catch (InterruptedException interrupted) { if (closed) return; }
            if (!closed) {
                try { enqueueSnapshot(); }
                catch (Throwable failure) { TelemetryFaults.rethrowFatal(failure); HealthCounters.add(health.failures, 1); }
            }
        }
    }
    @Override public Meter create(String name, String version) {
        CatalogMeter meter = meters.get(new MeterKey(name, version));
        if (meter == null) throw new IllegalArgumentException("Unregistered meter");
        return meter;
    }
    public MetricSnapshot snapshot() {
        List<MetricSnapshot.CounterPoint> counters = new ArrayList<>();
        List<MetricSnapshot.HistogramPoint> histograms = new ArrayList<>();
        for (CatalogMeter meter : meters.values()) {
            for (CounterState counter : meter.counters.values()) counter.snapshot(meter.definition, counters);
            for (HistogramState histogram : meter.histograms.values()) histogram.snapshot(meter.definition, histograms);
        }
        return new MetricSnapshot(clock.instant(), counters, histograms, health.snapshot());
    }
    private void enqueueSnapshot() {
        MetricSnapshot snapshot = snapshot();
        dispatchers.forEach(dispatcher -> dispatcher.offer(snapshot));
    }
    public TelemetryHealth health() { return health.snapshot(); }
    @Override public void close() { close(options.shutdownTimeout()); }
    public void close(Duration timeout) {
        long deadline = ProviderDispatcher.deadline(timeout);
        synchronized (this) {
            if (closed) return;
            closed = true;
            if (publisher != null) publisher.interrupt();
            try { enqueueSnapshot(); } catch (Throwable failure) { TelemetryFaults.rethrowFatal(failure); HealthCounters.add(health.failures, 1); }
            dispatchers.forEach(ProviderDispatcher::beginClose);
        }
        dispatchers.forEach(dispatcher -> dispatcher.awaitClose(deadline));
    }
    private record MeterKey(String name, String version) { }
    private final class CatalogMeter implements Meter {
        private final MeterDefinition definition;
        private final Map<String, CounterState> counters = new HashMap<>();
        private final Map<String, HistogramState> histograms = new HashMap<>();
        CatalogMeter(MeterDefinition definition) {
            this.definition = definition;
            definition.counters().forEach(counter -> counters.put(counter.name(), new CounterState(counter)));
            definition.histograms().forEach(histogram -> histograms.put(histogram.name(), new HistogramState(histogram)));
        }
        @Override public Counter counter(CounterDefinition requested) {
            CounterState state = requested == null ? null : counters.get(requested.name());
            if (state == null || !state.definition.equals(requested)) throw new IllegalArgumentException("Conflicting counter definition");
            return state;
        }
        @Override public Histogram histogram(HistogramDefinition requested) {
            HistogramState state = requested == null ? null : histograms.get(requested.name());
            if (state == null || !state.definition.equals(requested)) throw new IllegalArgumentException("Conflicting histogram definition");
            return state;
        }
    }
    private final class CounterState implements Counter {
        private final CounterDefinition definition;
        private final Map<Map<String, String>, Long> series = new HashMap<>();
        CounterState(CounterDefinition definition) { this.definition = definition; }
        @Override public synchronized void add(long value, Map<String, String> dimensions) {
            if (closed) return;
            try {
                Map<String, String> key = MetricDimensions.snapshot(definition.dimensions(), dimensions);
                if (value < 0 || key == null) { HealthCounters.add(health.dropped, 1); return; }
                long current = series.getOrDefault(key, 0L);
                series.put(key, current > Long.MAX_VALUE - value ? Long.MAX_VALUE : current + value);
            } catch (Throwable invalid) { TelemetryFaults.rethrowFatal(invalid); HealthCounters.add(health.dropped, 1); }
        }
        synchronized void snapshot(MeterDefinition meter, List<MetricSnapshot.CounterPoint> output) {
            series.forEach((dimensions, value) -> output.add(new MetricSnapshot.CounterPoint(meter.name(), meter.version(), definition, dimensions, value)));
        }
    }
    private final class HistogramState implements Histogram {
        private final HistogramDefinition definition;
        private final Map<Map<String, String>, Aggregate> series = new HashMap<>();
        HistogramState(HistogramDefinition definition) { this.definition = definition; }
        @Override public synchronized void record(double value, Map<String, String> dimensions) {
            if (closed) return;
            try {
                Map<String, String> key = MetricDimensions.snapshot(definition.dimensions(), dimensions);
                if (!Double.isFinite(value) || value < 0 || key == null) { HealthCounters.add(health.dropped, 1); return; }
                Aggregate aggregate = series.computeIfAbsent(key, ignored -> new Aggregate(definition.buckets().size() + 1));
                if (aggregate.count == Long.MAX_VALUE) { HealthCounters.add(health.dropped, 1); return; }
                aggregate.count++;
                aggregate.sum = aggregate.sum > Double.MAX_VALUE - value ? Double.MAX_VALUE : aggregate.sum + value;
                int bucket = 0;
                while (bucket < definition.buckets().size() && value > definition.buckets().get(bucket)) bucket++;
                aggregate.buckets[bucket]++;
            } catch (Throwable invalid) { TelemetryFaults.rethrowFatal(invalid); HealthCounters.add(health.dropped, 1); }
        }
        synchronized void snapshot(MeterDefinition meter, List<MetricSnapshot.HistogramPoint> output) {
            series.forEach((dimensions, aggregate) -> {
                List<Long> buckets = new ArrayList<>(aggregate.buckets.length);
                for (long count : aggregate.buckets) buckets.add(count);
                output.add(new MetricSnapshot.HistogramPoint(meter.name(), meter.version(), definition, dimensions, aggregate.count, aggregate.sum, buckets));
            });
        }
    }
    private static final class Aggregate {
        private long count;
        private double sum;
        private final long[] buckets;
        Aggregate(int bucketCount) { buckets = new long[bucketCount]; }
    }
}
