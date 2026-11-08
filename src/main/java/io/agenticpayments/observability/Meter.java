package io.agenticpayments.observability;

/** metrics-0.1: bounded, nonblocking instrument lookup; equivalent definitions reuse instruments and conflicts reject. */
public interface Meter {
    Counter counter(CounterDefinition definition);
    Histogram histogram(HistogramDefinition definition);
}
