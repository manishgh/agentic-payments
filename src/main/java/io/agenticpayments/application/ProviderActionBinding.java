package io.agenticpayments.application;

import io.agenticpayments.sdk.execution.v1.*;
import java.util.Objects;

/** Immutable provider action association; no credentials or presentation payload. */
public record ProviderActionBinding(ExecutionOperation operation, TrustedActionDescriptor action) {
    public ProviderActionBinding {
        Objects.requireNonNull(operation);Objects.requireNonNull(action);
        if(action.type()!=ExecutionOffer.ActionType.SYNTHETIC_CHALLENGE
                ||!action.operationFingerprint().equals(operation.fingerprint())
                ||!action.bindingDigest().equals(operation.binding().digest())
                ||!operation.selection().combination().actions().contains(action.type()))
            throw new IllegalArgumentException("Invalid provider authentication binding.");
    }
    @Override public String toString(){return "ProviderActionBinding[redacted]";}
}
