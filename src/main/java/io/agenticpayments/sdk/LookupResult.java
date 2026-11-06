package io.agenticpayments.sdk;

import java.util.Objects;
import java.util.Optional;

/** NOT_FOUND and UNAVAILABLE preserve uncertainty and do not release exposure. */
public record LookupResult(Status status, Optional<Fact> fact) {
    public enum Status { FOUND, NOT_FOUND, UNAVAILABLE }

    public LookupResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(fact, "fact");
        if ((status == Status.FOUND) != fact.isPresent()) {
            throw new IllegalArgumentException("Only FOUND lookup results contain a fact");
        }
    }

    public static LookupResult found(Fact fact) {
        return new LookupResult(Status.FOUND, Optional.of(fact));
    }

    public static LookupResult notFound() {
        return new LookupResult(Status.NOT_FOUND, Optional.empty());
    }

    public static LookupResult unavailable() {
        return new LookupResult(Status.UNAVAILABLE, Optional.empty());
    }
}
