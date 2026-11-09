package io.agenticpayments.http;

import io.agenticpayments.application.PaymentService.Session;

/** Trusted caller routing authentication, independent of event-body signature authentication. */
@FunctionalInterface
public interface ProviderWebhookAuthentication {
    String CONTRACT = "provider-webhook-authentication-1";

    /** Returns a configured scoped identity, or null; body claims cannot select this identity. */
    Session authenticate(String authorization);
}
