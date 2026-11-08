package io.agenticpayments.sdk.execution.v1;

import java.util.*;

/** Preparation never asserts financial acceptance; UNKNOWN retains unresolved resource creation. */
public record PreparationResult(ExecutionOperation operation, Status status, ExecutionOffer.PreparationEffect effect,
                                Optional<String> preparedResourceRef, Optional<TrustedActionDescriptor> action) {
    public enum Status { READY, ACTION_REQUIRED, UNKNOWN, REFUSED }
    public PreparationResult {
        Objects.requireNonNull(operation); Objects.requireNonNull(status); Objects.requireNonNull(effect);
        Objects.requireNonNull(preparedResourceRef); Objects.requireNonNull(action);
        preparedResourceRef = preparedResourceRef.map(ContractValues::token);
        if (effect != operation.selection().combination().preparationEffect()
                || (status == Status.ACTION_REQUIRED) != action.isPresent()
                || (effect == ExecutionOffer.PreparationEffect.EFFECT_FREE && preparedResourceRef.isPresent())
                || (effect == ExecutionOffer.PreparationEffect.RESOURCE_CREATING && status == Status.READY && preparedResourceRef.isEmpty())
                || action.stream().anyMatch(a -> !a.operationFingerprint().equals(operation.fingerprint())
                    || !a.bindingDigest().equals(operation.binding().digest()) || !operation.selection().combination().actions().contains(a.type())))
            throw new IllegalArgumentException("Invalid preparation result binding.");
    }
    @Override public String toString() { return "PreparationResult[redacted]"; }
}
