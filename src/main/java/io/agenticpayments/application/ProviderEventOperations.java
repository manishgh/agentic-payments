package io.agenticpayments.application;

import io.agenticpayments.application.PaymentService.Session;
import java.util.UUID;

/** Authenticated event retention and explicit durable replay; neither command dispatches a payment. */
public interface ProviderEventOperations {
    String CONTRACT="provider-event-operations-1";
    String retain(Session session,UUID intentId,byte[] rawBody,String signature);
    void replay(Session session,UUID intentId,String envelopeReference);
}
