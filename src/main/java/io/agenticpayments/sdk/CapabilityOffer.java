package io.agenticpayments.sdk;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Versioned local-only offer; support still requires current core configuration and authority. */
public record CapabilityOffer(String extensionVersion, String profile, String catalogVersion,
                              Set<String> capabilities, List<CapabilityCombination> combinations) {
    public static final String EXTENSION_VERSION = "0.1.0-draft";
    public static final String PROFILE = "local-fake-single-tender-0.1";
    public static final String CATALOG_VERSION = "fixtures-0.1";
    public static final Set<String> CATALOG = Set.of("single-tender", "exact-money", "customer-action",
            "idempotent-execution", "reconciliation");

    public CapabilityOffer {
        if (!EXTENSION_VERSION.equals(extensionVersion) || !PROFILE.equals(profile)
                || !CATALOG_VERSION.equals(catalogVersion)) {
            throw new IllegalArgumentException("Unsupported local-fake offer revision");
        }
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        combinations = List.copyOf(Objects.requireNonNull(combinations, "combinations"));
        if (!CATALOG.containsAll(capabilities) || combinations.isEmpty()
                || combinations.stream().anyMatch(c -> !"simulator".equals(c.provider())
                || !"synthetic-card".equals(c.method()) || !"synthetic-reference".equals(c.credentialType()))) {
            throw new IllegalArgumentException("Offer exceeds the local-fake catalog");
        }
    }

    public static CapabilityOffer localFake() {
        return new CapabilityOffer(EXTENSION_VERSION, PROFILE, CATALOG_VERSION, CATALOG,
                List.of(new CapabilityCombination("simulator", "synthetic-card", "synthetic-reference",
                        Set.of(ActionType.NONE, ActionType.SYNTHETIC_CHALLENGE))));
    }

    public CapabilityOffer negotiate(String requestedVersion, String requestedProfile,
                                     Set<String> requiredCapabilities, CapabilityCombination requiredCombination) {
        if (!extensionVersion.equals(requestedVersion) || !profile.equals(requestedProfile)) {
            throw new CapabilityRefusedException(CapabilityRefusedException.Code.UNSUPPORTED_PROFILE);
        }
        Objects.requireNonNull(requiredCapabilities, "requiredCapabilities");
        if (requiredCapabilities.size() > 16 || !capabilities.containsAll(requiredCapabilities)) {
            throw new CapabilityRefusedException(CapabilityRefusedException.Code.UNSUPPORTED_CAPABILITY);
        }
        Objects.requireNonNull(requiredCombination, "requiredCombination");
        boolean supported = combinations.stream().anyMatch(c -> c.provider().equals(requiredCombination.provider())
                && c.method().equals(requiredCombination.method())
                && c.credentialType().equals(requiredCombination.credentialType())
                && c.actionTypes().containsAll(requiredCombination.actionTypes()));
        if (!supported) {
            throw new CapabilityRefusedException(CapabilityRefusedException.Code.UNSUPPORTED_TENDER);
        }
        return this;
    }
}
