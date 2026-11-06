package io.agenticpayments.sdk;

/**
 * Synthetic adapter boundary. The core alone owns authorization, reservation, action verification,
 * dispatch admission and applying facts. Implementations must keep their durable journal separate
 * from core transactions, bind each adapter key to the immutable request, and return safe facts.
 * No implementation may reroute or create a new identity after a transport failure.
 */
public interface PaymentAdapter {
    CapabilityOffer capabilities();

    /** Must have no financial effect. */
    Preparation prepare(ChargeOperation operation);

    /** Repeated immutable key/request replays the accepted fact; transport loss may throw. */
    Fact execute(ChargeOperation operation);

    /** NOT_FOUND is inconclusive: a previously admitted worker may still send. */
    LookupResult lookup(OperationIdentity identity);

    /**
     * Atomically fence future sends with NO_EFFECT only if no acceptance already won. Otherwise
     * return the existing pending/final fact. This guarantee is specific to the local fake journal.
     */
    Fact sealNoEffect(ChargeOperation operation);
}
