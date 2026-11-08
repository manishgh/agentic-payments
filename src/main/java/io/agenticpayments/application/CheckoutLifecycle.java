package io.agenticpayments.application;

import io.agenticpayments.checkout.CheckoutSessions;
import java.util.List;
import java.util.UUID;

/** Versioned commerce lifecycle use cases. No operation grants authority or moves money. */
public interface CheckoutLifecycle {
    String CONTRACT_VERSION = "checkout-lifecycle-0.1";
    String PROFILE = "durable-checkout-lifecycle-0.1";
    enum CommandState { STAGED, CLAIMED, RECORDED, UNKNOWN, STALE }
    enum Recovery { DISPATCH_STAGED, IN_FLIGHT, RETRIEVE_ONLY, TRUSTED_RESOLUTION_REQUIRED, NONE }
    record StageCommand(CheckoutSessions.Operation operation, int expectedRevision,
                        String merchantId, String configurationId, String currency, List<String> itemIds) {
        public StageCommand {
            if (operation == null || expectedRevision < 0 || itemIds == null) invalid();
            new CheckoutSessions.Binding("validation", "validation", merchantId, configurationId);
            itemIds = List.copyOf(itemIds);
            if (!List.of("usd", "eur").contains(currency)) invalid();
            var items = itemIds.stream().map(CheckoutSessions.ItemReference::new).toList();
            switch (operation) {
                case CREATE -> { if (expectedRevision != 0) invalid(); new CheckoutSessions.Create(items, currency); }
                case UPDATE -> new CheckoutSessions.Update(items);
                case RETRIEVE, CANCEL -> { if (!items.isEmpty()) invalid(); }
            }
        }
        @Override public String toString() { return "StageCommand[protected]"; }
    }
    record SafeView(String contractVersion, String profile, UUID runId, UUID purchaseId,
                    UUID commandId, int revision, CheckoutSessions.Operation operation,
                    CommandState commandState, String outcomeCode,
                    CheckoutSessions.Observation observation, int httpStatus,
                    boolean remoteSessionKnown, String remoteStatus, UUID responseReference,
                    boolean mutationBlocked, Recovery recovery) {}
    SafeView stage(PaymentService.Session session, UUID purchaseId, String localKey, StageCommand command);
    SafeView status(PaymentService.Session session, UUID purchaseId);
    SafeView dispatch(PaymentService.Session session, UUID purchaseId, UUID commandId);
    SafeView recoverClaim(PaymentService.Session session, UUID purchaseId, UUID commandId);
    private static void invalid() { throw new IllegalArgumentException("INVALID_CHECKOUT_INPUT"); }
}
