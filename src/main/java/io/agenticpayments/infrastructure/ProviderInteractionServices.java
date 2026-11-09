package io.agenticpayments.infrastructure;
import io.agenticpayments.application.PaymentService;
import io.agenticpayments.application.ProviderEventOperations;
import java.util.Objects;
/** Host composition exposes authenticated payments and separately replaceable event ingress. */
public record ProviderInteractionServices(PaymentService payments,ProviderEventOperations events) {
    public ProviderInteractionServices {Objects.requireNonNull(payments);Objects.requireNonNull(events);}
}
