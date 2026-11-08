package io.agenticpayments.application;

import io.agenticpayments.application.CheckoutConfigurationResolver.ConfigurationIdentity;
import io.agenticpayments.application.CheckoutLifecycleTransactions.CoordinationDomain;
import io.agenticpayments.composition.Composition.Scope;
import java.util.*;

/** Explicit trusted synthetic policy. Metadata promises require behavioral verification by adapters. */
public record ExternalCheckoutRegistration(Scope scope, String sourcePrincipalId, ConfigurationIdentity configuration,
                                           CheckoutConfigurationResolver resolver, CoordinationDomain coordinationDomain,
                                           Set<String> guarantees) {
    public static final Set<String> REQUIRED_GUARANTEES=Set.of("permanent-purchase-seal-0.1","bounded-local-callbacks-0.1",
            "synthetic-virtual-clock-0.1","local-synthetic-attestation-0.1");
    public ExternalCheckoutRegistration {
        Objects.requireNonNull(scope); Objects.requireNonNull(sourcePrincipalId); Objects.requireNonNull(configuration);
        Objects.requireNonNull(resolver); Objects.requireNonNull(coordinationDomain); guarantees=Set.copyOf(guarantees);
        if(!scope.tenantId().equals(configuration.binding().tenantId())
                ||!sourcePrincipalId.equals(configuration.binding().principalId())||!guarantees.containsAll(REQUIRED_GUARANTEES))
            throw new PaymentException(422,"UNSUPPORTED_CAPABILITY");
    }
    @Override public String toString() { return "ExternalCheckoutRegistration[protected]"; }
}
