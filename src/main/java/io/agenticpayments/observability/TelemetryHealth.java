package io.agenticpayments.observability;

/** Bounded cumulative local health, never recursively logged. */
public record TelemetryHealth(long enqueued, long dropped, long exportFailures) { }
