package io.agenticpayments.sdk.execution.v1;

/** Optional atomic durable exclusion against every delayed/future submitter, not ordinary cancellation. */
@FunctionalInterface public interface NoEffectFencer {
    ExecutionEvidence fence(ExecutionOperation operation);
}
