package io.agenticpayments.sdk;

import java.util.Objects;

final class SdkValidation {
    private SdkValidation() { }

    static String fixtureId(String value, String field) {
        Objects.requireNonNull(value, field);
        if (!value.matches("[a-z0-9][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException(field + " must be a fixture identifier");
        }
        return value;
    }

    static String digest(String value) {
        Objects.requireNonNull(value, "requestDigest");
        if (!value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestDigest must be a lowercase SHA-256 reference");
        }
        return value;
    }
}
