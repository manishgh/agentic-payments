package io.agenticpayments.application;

import io.agenticpayments.sdk.ActionType;
import java.util.*;

/** Trusted preparation result carried into the core without provider I/O in its transaction. */
public record PreparationObservation(ActionType actionType,Optional<ProviderActionBinding> providerAction) {
    public PreparationObservation {
        Objects.requireNonNull(actionType);Objects.requireNonNull(providerAction);
        if(providerAction.isPresent()&&actionType!=ActionType.SYNTHETIC_CHALLENGE)
            throw new IllegalArgumentException("Invalid preparation action.");
    }
    @Override public String toString(){return "PreparationObservation[redacted]";}
}
