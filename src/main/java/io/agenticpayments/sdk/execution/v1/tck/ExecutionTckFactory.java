package io.agenticpayments.sdk.execution.v1.tck;

@FunctionalInterface public interface ExecutionTckFactory {
    ExecutionTckFixture open(String pinnedCaseId);
}
