package io.agenticpayments.observability;

import java.util.LinkedHashMap;
import java.util.Map;

public record LogFields(Map<String, LogValue> values) {
    public static final LogFields EMPTY = new LogFields(Map.of());
    public LogFields {
        if (values == null || values.size() > 16) values = Map.of();
        else {
            Map<String, LogValue> copy = new LinkedHashMap<>();
            int seen = 0;
            for (var entry : values.entrySet()) {
                if (++seen > 16) { copy.clear(); break; }
                String key = entry.getKey(); LogValue value = entry.getValue();
                boolean bounded = !(value instanceof LogValue.Finite finite) || (finite.value() != null && finite.value().length() <= 64);
                if (key != null && key.length() <= 64 && value != null && bounded) copy.put(key, value);
            }
            values = Map.copyOf(copy);
        }
    }
    public static LogFields of(Map<String, LogValue> values) { return new LogFields(values); }
}
