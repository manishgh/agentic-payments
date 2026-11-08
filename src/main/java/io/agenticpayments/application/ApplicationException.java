package io.agenticpayments.application;

import java.util.Objects;

/** Safe application failure without transport metadata, arbitrary detail, or a raw cause. */
public class ApplicationException extends RuntimeException {
    private final ApplicationFailure failure;

    public ApplicationException(ApplicationFailure failure) {
        super(Objects.requireNonNull(failure).message(), null, false, true);
        this.failure = failure;
    }

    public final ApplicationFailure failure() { return failure; }
    public final String code() { return failure.code(); }
}
