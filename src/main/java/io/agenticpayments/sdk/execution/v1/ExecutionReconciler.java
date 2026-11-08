package io.agenticpayments.sdk.execution.v1;

/** Read only. Missing/expired lookup is UNKNOWN; it never establishes no effect or permits resend. */
@FunctionalInterface public interface ExecutionReconciler {
    ExecutionEvidence reconcile(ExecutionOperation operation);
}
