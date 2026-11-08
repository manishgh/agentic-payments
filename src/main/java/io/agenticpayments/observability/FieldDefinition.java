package io.agenticpayments.observability;

import java.util.Set;

/** Immutable schema declared at startup; runtime values outside it are dropped. */
public final class FieldDefinition {
    public enum Kind { FINITE, INTEGER, DECIMAL, BOOLEAN, CORRELATION }
    private final Kind kind;
    private final Set<String> values;
    private final long minInteger;
    private final long maxInteger;
    private final double minDecimal;
    private final double maxDecimal;
    private FieldDefinition(Kind kind, Set<String> values, long minInteger, long maxInteger,
                            double minDecimal, double maxDecimal) {
        this.kind = kind; this.values = values; this.minInteger = minInteger; this.maxInteger = maxInteger;
        this.minDecimal = minDecimal; this.maxDecimal = maxDecimal;
    }
    public static FieldDefinition finite(Set<String> values) {
        if (values == null || values.isEmpty() || values.size() > 64) throw new IllegalArgumentException("Invalid finite field");
        values.forEach(TelemetryNames::require);
        return new FieldDefinition(Kind.FINITE, Set.copyOf(values), 0, 0, 0, 0);
    }
    public static FieldDefinition integer(long min, long max) {
        if (min > max) throw new IllegalArgumentException("Invalid field range");
        return new FieldDefinition(Kind.INTEGER, Set.of(), min, max, 0, 0);
    }
    public static FieldDefinition decimal(double min, double max) {
        if (!Double.isFinite(min) || !Double.isFinite(max) || min > max) throw new IllegalArgumentException("Invalid field range");
        return new FieldDefinition(Kind.DECIMAL, Set.of(), 0, 0, min, max);
    }
    public static FieldDefinition bool() { return new FieldDefinition(Kind.BOOLEAN, Set.of(), 0, 0, 0, 0); }
    public static FieldDefinition correlation() { return new FieldDefinition(Kind.CORRELATION, Set.of(), 0, 0, 0, 0); }
    public Kind kind() { return kind; }
    public boolean accepts(LogValue value) {
        return switch (kind) {
            case FINITE -> value instanceof LogValue.Finite f && f.value() != null && values.contains(f.value());
            case INTEGER -> value instanceof LogValue.IntegerValue n && n.value() >= minInteger && n.value() <= maxInteger;
            case DECIMAL -> value instanceof LogValue.DecimalValue n && Double.isFinite(n.value()) && n.value() >= minDecimal && n.value() <= maxDecimal;
            case BOOLEAN -> value instanceof LogValue.Flag;
            case CORRELATION -> value instanceof LogValue.Correlation c && c.value() != null;
        };
    }
}
