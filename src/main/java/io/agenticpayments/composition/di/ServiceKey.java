package io.agenticpayments.composition.di;

import java.util.Objects;

/** A startup-selected contract and optional trusted qualifier. */
public record ServiceKey<T>(Class<T> type, String qualifier) {
    public ServiceKey {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(qualifier, "qualifier");
        if (type.isPrimitive() || qualifier.length() > 128 || !qualifier.matches("[A-Za-z0-9_.-]*")) {
            throw new IllegalArgumentException("Invalid service key");
        }
    }

    public static <T> ServiceKey<T> of(Class<T> type) { return new ServiceKey<>(type, ""); }
    public static <T> ServiceKey<T> of(Class<T> type, String qualifier) { return new ServiceKey<>(type, qualifier); }
}
