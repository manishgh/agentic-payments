package io.agenticpayments.infrastructure;

import io.agenticpayments.application.*;
import java.util.Objects;

/** Construct first, explicitly enroll trusted metadata, then expose guarded acceptance and delivery. */
public final class ProviderEventRouteServices {
    private final PaymentStore store;private final PaymentOperations payments;private final ProviderEventAcceptance acceptance;
    private final ProviderEventDeliveryRoutes.Resolved route;private final ProviderEventConfigurationStore configurations;
    private final ProviderEventConfigurationStore.Configuration configuration;
    public ProviderEventRouteServices(PaymentStore store,PaymentOperations payments,ProviderEventAcceptance acceptance,
            ProviderEventDeliveryRoutes.Resolved route,ProviderEventConfigurationStore configurations,
            ProviderEventConfigurationStore.Configuration configuration) {
        this.store=Objects.requireNonNull(store);this.payments=Objects.requireNonNull(payments);this.acceptance=Objects.requireNonNull(acceptance);
        this.route=Objects.requireNonNull(route);this.configurations=Objects.requireNonNull(configurations);this.configuration=Objects.requireNonNull(configuration);
        if(!route.route().equals(configuration.route()))throw new ApplicationException(ApplicationFailure.CONFIGURATION_CHANGED);
    }
    public void registerConfiguration(){configurations.register(configuration);}
    public PaymentStore store(){return store;}
    public PaymentOperations payments(){return payments;}
    public ProviderEventAcceptance acceptance(){return acceptance;}
    public ProviderEventDeliveryRoutes.Resolved route(){return route;}
    @Override public String toString(){return "ProviderEventRouteServices[redacted]";}
}
