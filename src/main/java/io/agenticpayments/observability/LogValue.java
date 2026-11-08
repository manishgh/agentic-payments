package io.agenticpayments.observability;

/** Closed values: arbitrary objects, serializers, peer text and exceptions cannot be attached. */
public sealed interface LogValue {
    record Finite(String value) implements LogValue { }
    record IntegerValue(long value) implements LogValue { }
    record DecimalValue(double value) implements LogValue { }
    record Flag(boolean value) implements LogValue { }
    record Correlation(CorrelationId value) implements LogValue { }
    static LogValue finite(String value) { return new Finite(value); }
    static LogValue number(long value) { return new IntegerValue(value); }
    static LogValue decimal(double value) { return new DecimalValue(value); }
    static LogValue flag(boolean value) { return new Flag(value); }
    static LogValue correlation(CorrelationId value) { return new Correlation(value); }
}
