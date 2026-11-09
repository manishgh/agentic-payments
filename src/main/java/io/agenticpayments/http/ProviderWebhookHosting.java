package io.agenticpayments.http;

import io.agenticpayments.application.ProviderEventAcceptance;
import io.agenticpayments.application.ProviderEventDeliveryRuntime;
import java.util.Objects;

/** Explicit synthetic webhook opt-in. The host owns runtime shutdown, including startup failure. */
public record ProviderWebhookHosting(ProviderEventAcceptance acceptance, ProviderWebhookAuthentication authentication,
                                     ProviderEventDeliveryRuntime runtime) {
    public ProviderWebhookHosting {
        Objects.requireNonNull(acceptance);
        Objects.requireNonNull(authentication);
        Objects.requireNonNull(runtime);
    }
    @Override public String toString() { return "ProviderWebhookHosting[redacted]"; }
}
