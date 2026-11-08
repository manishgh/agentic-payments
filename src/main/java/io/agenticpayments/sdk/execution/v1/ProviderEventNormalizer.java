package io.agenticpayments.sdk.execution.v1;

/** Resolve trusted verified provenance before mapping the selected profile's status to safe facts. */
@FunctionalInterface public interface ProviderEventNormalizer {
    ExecutionEvidence normalize(ExecutionOperation operation, VerifiedEventReference event);
}
