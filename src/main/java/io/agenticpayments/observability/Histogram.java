package io.agenticpayments.observability;

import java.util.Map;

/**
 * metrics-0.1 cumulative count/sum/fixed-bucket instrument for finite nonnegative measurements.
 * Record must be thread-safe, bounded and nonblocking on caller threads and drop invalid inputs.
 * Replacements must never invoke exporters here; thrown-fault guards do not isolate synchronous stalls.
 */
@FunctionalInterface
public interface Histogram { void record(double value, Map<String, String> dimensions); }
