package io.agenticpayments.observability;

/**
 * metrics-0.1: isolated per-root instruments from fixed definitions with finite dimensions.
 * All caller-thread callbacks must be bounded and nonblocking, including replacement factories.
 * Invalid registrations reject explicitly; provider callbacks belong behind isolated bounded queues.
 * Thrown-fault guards cannot isolate a replacement's arbitrary synchronous stall.
 */
public interface MeterFactory { Meter create(String name, String version); }
