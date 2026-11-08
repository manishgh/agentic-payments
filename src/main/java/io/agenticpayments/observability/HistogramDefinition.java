package io.agenticpayments.observability;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record HistogramDefinition(String name, String unit, Map<String, Set<String>> dimensions, List<Double> buckets) {
    public HistogramDefinition {
        TelemetryNames.require(name); TelemetryNames.require(unit);
        dimensions = MetricDimensions.validate(dimensions);
        if (buckets == null || buckets.isEmpty() || buckets.size() > 64) throw new IllegalArgumentException("Invalid histogram buckets");
        double previous = -1;
        for (Double bucket : buckets) {
            if (bucket == null || !Double.isFinite(bucket) || bucket < 0 || bucket <= previous) throw new IllegalArgumentException("Invalid histogram buckets");
            previous = bucket;
        }
        buckets = List.copyOf(buckets);
    }
}
