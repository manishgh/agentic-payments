package io.agenticpayments.observability;

import java.util.Map;

/**
 * metrics-0.1 nonnegative cumulative counter. Add must be thread-safe, bounded and nonblocking on
 * caller threads, snapshot finite catalog dimensions, and drop invalid values without throwing.
 * Replacements must never invoke exporters here; thrown-fault guards do not isolate synchronous stalls.
 */
@FunctionalInterface
public interface Counter { void add(long value, Map<String, String> dimensions); }
