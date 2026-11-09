package io.agenticpayments.application;

import io.agenticpayments.application.PaymentService.Session;
import java.util.UUID;

/**
 * Scoped acceptance of an event into durable automatic delivery. Implementations resolve the
 * admitted operation from trusted caller scope and intent, authenticate exact bytes before
 * decoding, and validate the full operation/account/configuration binding. Raw retention and
 * delivery creation commit atomically; this command never dispatches a provider payment.
 */
@FunctionalInterface
public interface ProviderEventAcceptance {
    String CONTRACT = "provider-event-acceptance-1";

    /** Returns only after exact bytes and delivery work commit; never acknowledges payment completion. */
    String accept(Session session, UUID intentId, byte[] rawBody, String signature);
}
