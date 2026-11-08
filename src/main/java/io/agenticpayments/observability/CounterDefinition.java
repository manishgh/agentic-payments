package io.agenticpayments.observability;

import java.util.Map;
import java.util.Set;

public record CounterDefinition(String name, String unit, Map<String, Set<String>> dimensions) {
    public CounterDefinition {
        TelemetryNames.require(name); TelemetryNames.require(unit);
        dimensions = MetricDimensions.validate(dimensions);
    }
}
