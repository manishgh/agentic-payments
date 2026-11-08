package io.agenticpayments.application;

import io.agenticpayments.checkout.CheckoutCommandTransport;
import io.agenticpayments.checkout.CheckoutSessions.Binding;
import java.util.Objects;

/** Resolve exact immutable enrollment metadata. Credentials remain inside trusted infrastructure. */
@FunctionalInterface
public interface CheckoutConfigurationResolver {
    Resolved resolve(ConfigurationIdentity identity);
    record ConfigurationIdentity(Binding binding, String configurationVersion, String credentialVersion,
                                 String transportProfile, String encodingVersion, String configurationFingerprint) {
        public ConfigurationIdentity {
            Objects.requireNonNull(binding);
            for (String value : new String[]{configurationVersion,credentialVersion,transportProfile,encodingVersion})
                if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) invalid();
            if (configurationFingerprint == null || !configurationFingerprint.matches("[a-f0-9]{64}")) invalid();
        }
        @Override public String toString() { return "ConfigurationIdentity[protected]"; }
    }
    record Resolved(ConfigurationIdentity identity, CheckoutCommandTransport transport) {
        public Resolved { Objects.requireNonNull(identity); Objects.requireNonNull(transport); }
        @Override public String toString() { return "Resolved[protected]"; }
    }
    private static void invalid() { throw new IllegalArgumentException("INVALID_CHECKOUT_CONFIGURATION"); }
}
