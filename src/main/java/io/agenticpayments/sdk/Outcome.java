package io.agenticpayments.sdk;

/** Authoritative adapter facts; absence/transport uncertainty is never NO_EFFECT. */
public enum Outcome {
    PENDING,
    SUCCEEDED,
    NO_EFFECT
}
