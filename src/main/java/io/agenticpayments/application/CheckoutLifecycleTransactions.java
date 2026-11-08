package io.agenticpayments.application;

import io.agenticpayments.checkout.CheckoutCommandTransport.Prepared;
import io.agenticpayments.checkout.CheckoutSessions;
import io.agenticpayments.application.CheckoutConfigurationResolver.ConfigurationIdentity;
import io.agenticpayments.application.CheckoutLifecycle.*;
import java.util.UUID;

/** Atomic scoped lifecycle unit of work. No callback may perform network I/O. */
public interface CheckoutLifecycleTransactions {
    String CONTRACT_VERSION = "checkout-lifecycle-transactions-0.1";
    <T> T run(PaymentService.Session session, Work<T> work);
    /** Trusted infrastructure enrollment only, never exposed as an application API. */
    void enroll(PaymentService.Session scope, ConfigurationIdentity identity);
    @FunctionalInterface interface Work<T> { T run(Transaction transaction); }
    interface Transaction {
        Purchase purchase(UUID purchaseId);
        StoredCommand replay(UUID purchaseId, String localKey);
        StoredCommand command(UUID purchaseId, UUID commandId);
        ConfigurationIdentity currentConfiguration(String merchantId, String configurationId);
        StoredCommand stage(UUID purchaseId, String localKey, String fingerprint, StageCommand input,
                            ConfigurationIdentity configuration, Prepared prepared);
        Claim claim(UUID purchaseId, UUID commandId, UUID token);
        /** Always retain correlated outcome; permit projection only with all immutable/current fences. */
        StoredCommand record(Claim claim, CheckoutSessions.Result result, boolean resolverCurrent);
        StoredCommand recoverClaim(UUID purchaseId, UUID commandId);
    }
    record Purchase(UUID id, ConfigurationIdentity configuration, String currency, String sessionId,
                    int revision, String remoteStatus, String documentDigest, UUID currentCommandId,
                    boolean mutationBlocked) {
        @Override public String toString() { return "Purchase[protected]"; }
    }
    record StoredCommand(UUID id, UUID purchaseId, String localKey, String fingerprint,
                         StageCommand input, ConfigurationIdentity configuration, Prepared prepared,
                         int stagedRevision, CommandState state, UUID claimToken, String outcomeCode,
                         CheckoutSessions.Observation observation, int httpStatus, UUID responseReference) {
        @Override public String toString() { return "StoredCommand[protected]"; }
    }
    record Claim(StoredCommand command, UUID token) {
        @Override public String toString() { return "Claim[protected]"; }
    }
}
