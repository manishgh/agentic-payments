package io.agenticpayments.sdk.execution.v1;

import java.util.*;

/** The complete absent/present prepared-resource association must be atomically bound before send. */
public record SubmissionRequest(ExecutionOperation operation, Optional<String> preparedResourceRef) {
    public SubmissionRequest {
        Objects.requireNonNull(operation); Objects.requireNonNull(preparedResourceRef);
        preparedResourceRef = preparedResourceRef.map(ContractValues::token);
        boolean creates = operation.selection().combination().preparationEffect() == ExecutionOffer.PreparationEffect.RESOURCE_CREATING;
        ContractValues.require(creates == preparedResourceRef.isPresent(), ExecutionFailure.Code.PREPARATION_UNRESOLVED);
    }
    public String fingerprint() { return ContractValues.hash(List.of("provider-execution-submission-1", operation.fingerprint(),
            preparedResourceRef.isPresent() ? "present" : "absent", preparedResourceRef.orElse(""))); }
    @Override public String toString() { return "SubmissionRequest[redacted]"; }
}
