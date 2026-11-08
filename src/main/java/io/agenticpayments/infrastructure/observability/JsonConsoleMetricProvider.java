package io.agenticpayments.infrastructure.observability;

import io.agenticpayments.observability.MetricProvider;
import io.agenticpayments.observability.MetricSnapshot;
import java.io.PrintStream;
import java.util.Objects;

public final class JsonConsoleMetricProvider implements MetricProvider {
    private final PrintStream output;
    public JsonConsoleMetricProvider(PrintStream output) { this.output = Objects.requireNonNull(output); }
    @Override public void export(MetricSnapshot snapshot) {
        for (var point : snapshot.counters()) {
            output.println(prefix(snapshot, point.meter(), point.version(), point.definition().name(), point.definition().unit())
                    + ",\"instrument\":\"counter\",\"dimensions\":" + TelemetryJson.dimensions(point.dimensions())
                    + ",\"value\":" + point.value() + "}");
        }
        for (var point : snapshot.histograms()) {
            output.println(prefix(snapshot, point.meter(), point.version(), point.definition().name(), point.definition().unit())
                    + ",\"instrument\":\"histogram\",\"dimensions\":" + TelemetryJson.dimensions(point.dimensions())
                    + ",\"count\":" + point.count() + ",\"sum\":" + point.sum()
                    + ",\"buckets\":" + point.definition().buckets() + ",\"bucketCounts\":" + point.bucketCounts() + "}");
        }
        output.println("{\"kind\":\"telemetry_health\",\"timestamp\":" + TelemetryJson.quote(snapshot.timestamp().toString())
                + ",\"enqueued\":" + snapshot.health().enqueued() + ",\"dropped\":" + snapshot.health().dropped()
                + ",\"exportFailures\":" + snapshot.health().exportFailures() + "}");
        if (output.checkError()) throw new IllegalStateException("Telemetry output unavailable");
    }
    private static String prefix(MetricSnapshot snapshot, String meter, String version, String name, String unit) {
        return "{\"kind\":\"metric\",\"timestamp\":" + TelemetryJson.quote(snapshot.timestamp().toString())
                + ",\"meter\":" + TelemetryJson.quote(meter) + ",\"version\":" + TelemetryJson.quote(version)
                + ",\"name\":" + TelemetryJson.quote(name) + ",\"unit\":" + TelemetryJson.quote(unit);
    }
}
