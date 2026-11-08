package io.agenticpayments.observability;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Cumulative aggregates only, with disjoint bucket counts including a final overflow bucket. */
public record MetricSnapshot(Instant timestamp, List<CounterPoint> counters, List<HistogramPoint> histograms,
                             TelemetryHealth health) {
    public MetricSnapshot { counters = List.copyOf(counters); histograms = List.copyOf(histograms); }
    public record CounterPoint(String meter, String version, CounterDefinition definition, Map<String, String> dimensions, long value) {
        public CounterPoint { dimensions = Map.copyOf(dimensions); }
    }
    public record HistogramPoint(String meter, String version, HistogramDefinition definition, Map<String, String> dimensions,
                                 long count, double sum, List<Long> bucketCounts) {
        public HistogramPoint { dimensions = Map.copyOf(dimensions); bucketCounts = List.copyOf(bucketCounts); }
    }
}
