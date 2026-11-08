package io.agenticpayments.sdk.execution.v1;

import java.time.Instant;
import java.util.Objects;

/** Only opaque references. Resolving/presenting/completing an action is a separate trusted boundary. */
public record TrustedActionDescriptor(ExecutionOffer.ActionType type, String actionRef, String operationFingerprint,
                                      String bindingDigest, Instant expiresAt) {
    public TrustedActionDescriptor {
        Objects.requireNonNull(type); actionRef = ContractValues.token(actionRef);
        operationFingerprint = ContractValues.digest(operationFingerprint); bindingDigest = ContractValues.digest(bindingDigest);
        Objects.requireNonNull(expiresAt);
    }
    public boolean usableFor(ExecutionOperation operation, Instant trustedNow) {
        Objects.requireNonNull(operation); Objects.requireNonNull(trustedNow);
        return operation.fingerprint().equals(operationFingerprint) && operation.binding().digest().equals(bindingDigest)
                && operation.selection().combination().actions().contains(type) && trustedNow.isBefore(expiresAt);
    }
    @Override public String toString() { return "TrustedActionDescriptor[redacted]"; }
}
