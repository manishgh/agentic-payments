package io.agenticpayments.sdk.execution.v1;

/** Invoke only after durable admission. Retry uncertainty using the original binding, never a new key. */
@FunctionalInterface public interface FinancialSubmitter {
    ExecutionEvidence submit(SubmissionRequest request);
}
