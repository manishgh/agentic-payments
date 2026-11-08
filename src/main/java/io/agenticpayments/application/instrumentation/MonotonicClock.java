package io.agenticpayments.application.instrumentation;

/** Elapsed-time instrumentation only; never payment authority or expiry time. */
@FunctionalInterface
public interface MonotonicClock {
    long nanoTime();
}
