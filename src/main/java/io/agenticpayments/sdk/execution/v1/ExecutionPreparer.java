package io.agenticpayments.sdk.execution.v1;

/** May create only explicitly declared nonfinancial resources. Bind identity before activity. */
@FunctionalInterface public interface ExecutionPreparer {
    PreparationResult prepare(ExecutionOperation operation);
}
