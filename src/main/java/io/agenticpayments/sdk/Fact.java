package io.agenticpayments.sdk;

import java.util.Objects;
import java.util.UUID;

/** Safe journal evidence received only through the trusted adapter port. */
public record Fact(UUID eventId, OperationIdentity identity, Money money, long sequence, Outcome outcome) {
    public Fact {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(money, "money");
        Objects.requireNonNull(outcome, "outcome");
        if (sequence < 1) {
            throw new IllegalArgumentException("Fact sequence must be positive");
        }
    }
}
