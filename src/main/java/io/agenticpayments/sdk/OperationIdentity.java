package io.agenticpayments.sdk;

import java.util.Objects;
import java.util.UUID;

/** Persist before dispatch; every delivery and lookup reuses these identities. */
public record OperationIdentity(ExecutionScope scope, UUID intentId, UUID attemptId,
                                UUID operationId, UUID adapterKey, String requestDigest) {
    public OperationIdentity {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(adapterKey, "adapterKey");
        requestDigest = SdkValidation.digest(requestDigest);
    }
}
