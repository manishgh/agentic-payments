package io.agenticpayments.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** A finite Cartesian product, capped per instrument before any allocation. */
public final class MetricDimensions {
    public static final int MAX_SERIES = 1024;
    private MetricDimensions() { }
    public static Map<String, Set<String>> validate(Map<String, Set<String>> dimensions) {
        if (dimensions == null || dimensions.size() > 8) throw new IllegalArgumentException("Invalid metric dimensions");
        long cardinality = 1;
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        for (var entry : dimensions.entrySet()) {
            TelemetryNames.field(entry.getKey());
            if (entry.getKey().toLowerCase(java.util.Locale.ROOT).contains("correlation")) throw new IllegalArgumentException("Correlation is log-only");
            Set<String> values = entry.getValue();
            if (values == null || values.isEmpty() || values.size() > 64) throw new IllegalArgumentException("Invalid dimension values");
            values.forEach(TelemetryNames::require);
            cardinality *= values.size();
            if (cardinality > MAX_SERIES) throw new IllegalArgumentException("Metric cardinality exceeds limit");
            copy.put(entry.getKey(), Set.copyOf(values));
        }
        return Map.copyOf(copy);
    }
    public static boolean accepts(Map<String, Set<String>> schema, Map<String, String> dimensions) {
        return snapshot(schema, dimensions) != null;
    }
    /** Copies only schema keys, then validates those exact values; mutable callers cannot grow the registry. */
    public static Map<String, String> snapshot(Map<String, Set<String>> schema, Map<String, String> dimensions) {
        if (dimensions == null || dimensions.size() != schema.size()) return null;
        Map<String, String> copy = new LinkedHashMap<>();
        for (var entry : schema.entrySet()) {
            String value = dimensions.get(entry.getKey());
            if (value == null || !entry.getValue().contains(value)) return null;
            copy.put(entry.getKey(), value);
        }
        return Map.copyOf(copy);
    }
}
