package io.agenticpayments.observability;

import java.util.UUID;

/** Only locally generated identifiers can enter telemetry. There is deliberately no parse API. */
public final class CorrelationId {
    private final String value;
    private CorrelationId(String value) { this.value = value; }
    public static CorrelationId generate() { return new CorrelationId(UUID.randomUUID().toString()); }
    public String value() { return value; }
    @Override public String toString() { return value; }
}
