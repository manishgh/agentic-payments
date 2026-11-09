package io.agenticpayments.infrastructure;

import io.agenticpayments.application.*;
import java.util.Objects;

/** Coherent host composition; scheduling and transport use independently replaceable responsibility ports. */
public record ProviderEventDeliveryServices(PaymentStore store,PaymentOperations payments,ProviderEventAcceptance acceptance,ProviderEventDeliveryWork delivery) {
    public ProviderEventDeliveryServices {Objects.requireNonNull(store);Objects.requireNonNull(payments);Objects.requireNonNull(acceptance);Objects.requireNonNull(delivery);}
}
