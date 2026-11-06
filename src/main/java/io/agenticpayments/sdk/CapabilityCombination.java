package io.agenticpayments.sdk;

import java.util.Objects;
import java.util.Set;

/** Explicit valid combinations avoid treating independent capability axes as a cross-product. */
public record CapabilityCombination(String provider, String method, String credentialType,
                                    Set<ActionType> actionTypes) {
    public CapabilityCombination {
        provider = SdkValidation.fixtureId(provider, "provider");
        method = SdkValidation.fixtureId(method, "method");
        credentialType = SdkValidation.fixtureId(credentialType, "credentialType");
        actionTypes = Set.copyOf(Objects.requireNonNull(actionTypes, "actionTypes"));
        if (actionTypes.isEmpty()) {
            throw new IllegalArgumentException("A capability combination needs an explicit action option");
        }
    }
}
