package io.agenticpayments.sdk.tck;
import io.agenticpayments.sdk.Fact;
import java.util.List;
/** Harness-owned inspection of durable effects, independent of an execute response. */
public record TckObservation(long acceptedOperations, long successfulCharges, boolean immutableBindingsValid, List<Fact> facts) {
    public TckObservation {
        if (acceptedOperations < 0 || successfulCharges < 0 || successfulCharges > acceptedOperations) throw new IllegalArgumentException("Invalid observed counts.");
        facts = List.copyOf(facts);
    }
}
