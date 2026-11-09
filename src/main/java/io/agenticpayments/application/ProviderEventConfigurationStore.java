package io.agenticpayments.application;

import java.util.Objects;

/** Immutable nonsecret configuration commitments; backends preserve atomic insert-or-compare semantics. */
public interface ProviderEventConfigurationStore {
    String CONTRACT="provider-event-configuration-store-1";
    /** Explicit trusted enrollment; an existing identity may never acquire another descriptor. */
    void register(Configuration configuration);
    /** Requires the exact registered commitment. Never enrolls, overwrites or selects a current revision. */
    void verify(Configuration configuration);
    record Configuration(ProviderEventDeliveryRoutes.EventRoute route,String descriptorDigest) {
        public Configuration {
            Objects.requireNonNull(route);
            if(descriptorDigest==null||!descriptorDigest.matches("sha256:[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid configuration commitment.");
        }
        @Override public String toString(){return "ProviderEventConfigurationCommitment[redacted]";}
    }
}
