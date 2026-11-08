package io.agenticpayments.observability;

import java.util.Map;

public record EventDefinition(String id, String template, Map<String, FieldDefinition> fields) {
    public EventDefinition {
        TelemetryNames.require(id);
        if (template == null || template.length() > 256 || template.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid event template");
        }
        if (fields == null || fields.size() > 16) throw new IllegalArgumentException("Invalid event schema");
        fields.keySet().forEach(TelemetryNames::field);
        fields = Map.copyOf(fields);
    }
}
