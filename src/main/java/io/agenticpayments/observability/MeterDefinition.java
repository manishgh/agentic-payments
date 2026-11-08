package io.agenticpayments.observability;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record MeterDefinition(String name, String version, List<CounterDefinition> counters, List<HistogramDefinition> histograms) {
    public MeterDefinition {
        TelemetryNames.require(name);
        if (version == null || !version.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,31}")) throw new IllegalArgumentException("Invalid meter version");
        if (counters == null || histograms == null || counters.size() + histograms.size() > 64) throw new IllegalArgumentException("Invalid meter catalog");
        counters = List.copyOf(counters); histograms = List.copyOf(histograms);
        Set<String> names = new HashSet<>();
        for (var counter : counters) if (!names.add(counter.name())) throw new IllegalArgumentException("Conflicting metric definition");
        for (var histogram : histograms) if (!names.add(histogram.name())) throw new IllegalArgumentException("Conflicting metric definition");
    }
}
