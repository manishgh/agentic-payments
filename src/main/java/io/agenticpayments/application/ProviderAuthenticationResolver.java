package io.agenticpayments.application;

import java.util.Optional;

/** Resolve trusted provider provenance; arbitrary constructed observations do not authenticate. */
public interface ProviderAuthenticationResolver {
    String CONTRACT="provider-authentication-resolver-1";
    Optional<AuthenticationObservation> resolve(ProviderActionBinding binding);
}
