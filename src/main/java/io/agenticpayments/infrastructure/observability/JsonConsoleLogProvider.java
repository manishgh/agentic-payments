package io.agenticpayments.infrastructure.observability;

import io.agenticpayments.observability.LogProvider;
import io.agenticpayments.observability.LogRecord;
import java.io.PrintStream;
import java.util.List;
import java.util.Objects;

/** Console I/O runs only on a factory's provider dispatcher. The stream is borrowed. */
public final class JsonConsoleLogProvider implements LogProvider {
    private final PrintStream output;
    public JsonConsoleLogProvider(PrintStream output) { this.output = Objects.requireNonNull(output); }
    @Override public void export(List<LogRecord> batch) {
        for (LogRecord record : batch) {
            output.println("{\"kind\":\"log\",\"timestamp\":" + TelemetryJson.quote(record.timestamp().toString())
                    + ",\"category\":" + TelemetryJson.quote(record.category()) + ",\"level\":" + TelemetryJson.quote(record.level().name())
                    + ",\"event\":" + TelemetryJson.quote(record.event().id()) + ",\"template\":" + TelemetryJson.quote(record.event().template())
                    + ",\"fields\":" + TelemetryJson.fields(record.fields()) + "}");
        }
        if (output.checkError()) throw new IllegalStateException("Telemetry output unavailable");
    }
}
