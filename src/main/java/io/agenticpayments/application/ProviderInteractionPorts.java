package io.agenticpayments.application;

import io.agenticpayments.sdk.execution.v1.ProviderEventVerifier;
import io.agenticpayments.sdk.execution.v1.ProviderEventNormalizer;
import java.util.Objects;

/** Trusted composition, independently replaceable by responsibility; no provider identity is inferred. */
public record ProviderInteractionPorts(ProviderActionRegistry actions,ProviderAuthenticationResolver authentication,
        SyntheticAuthenticationDecisions syntheticDecisions,ProviderEventIngress ingress,
        ProviderEventVerifier eventVerifier,ProviderEventNormalizer eventNormalizer,ProviderEventHealth eventHealth) {
    public ProviderInteractionPorts {
        Objects.requireNonNull(actions);Objects.requireNonNull(authentication);Objects.requireNonNull(syntheticDecisions);
        Objects.requireNonNull(ingress);Objects.requireNonNull(eventVerifier);Objects.requireNonNull(eventNormalizer);Objects.requireNonNull(eventHealth);
    }
    @Override public String toString(){return "ProviderInteractionPorts[redacted]";}
}
