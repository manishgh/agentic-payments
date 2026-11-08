package io.agenticpayments.infrastructure.observability;

import io.agenticpayments.observability.*;
import java.util.Map;

/** Encodes only the closed telemetry value model. No reflective/general object serializer. */
final class TelemetryJson {
    private TelemetryJson() { }
    static String quote(String value) {
        StringBuilder output = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                default -> {
                    if (character < 32) output.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
                    else output.append(character);
                }
            }
        }
        return output.append('"').toString();
    }
    static String dimensions(Map<String, String> dimensions) {
        StringBuilder output = new StringBuilder("{");
        boolean first = true;
        for (var entry : dimensions.entrySet()) {
            if (!first) output.append(','); first = false;
            output.append(quote(entry.getKey())).append(':').append(quote(entry.getValue()));
        }
        return output.append('}').toString();
    }
    static String fields(LogFields fields) {
        StringBuilder output = new StringBuilder("{");
        boolean first = true;
        for (var entry : fields.values().entrySet()) {
            if (!first) output.append(','); first = false;
            output.append(quote(entry.getKey())).append(':');
            switch (entry.getValue()) {
                case LogValue.Finite finite -> output.append(quote(finite.value()));
                case LogValue.IntegerValue number -> output.append(number.value());
                case LogValue.DecimalValue number -> output.append(number.value());
                case LogValue.Flag flag -> output.append(flag.value());
                case LogValue.Correlation correlation -> output.append(quote(correlation.value().value()));
            }
        }
        return output.append('}').toString();
    }
}
