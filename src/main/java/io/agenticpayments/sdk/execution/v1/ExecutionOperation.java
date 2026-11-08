package io.agenticpayments.sdk.execution.v1;

import java.util.*;

/** Persist this complete value before activity. Construction does not establish financial admission. */
public record ExecutionOperation(ExecutionIdentity identity, ExecutionBinding binding, CapabilitySelection selection,
                                 ExactMoney money, OperationPurpose purpose, String fingerprint) {
    public ExecutionOperation {
        Objects.requireNonNull(identity); Objects.requireNonNull(binding); Objects.requireNonNull(selection);
        Objects.requireNonNull(money); Objects.requireNonNull(purpose);
        ContractValues.require(binding.equals(selection.binding()) && selection.combination().purposes().contains(purpose)
                && selection.combination().permits(money), ExecutionFailure.Code.UNSUPPORTED_COMBINATION);
        if (!fingerprint(identity, binding, selection, money, purpose).equals(ContractValues.digest(fingerprint)))
            throw new IllegalArgumentException("Operation fingerprint mismatch.");
    }
    public static ExecutionOperation create(ExecutionIdentity identity, ExecutionBinding binding, CapabilitySelection selection,
                                             ExactMoney money, OperationPurpose purpose) {
        return new ExecutionOperation(identity, binding, selection, money, purpose, fingerprint(identity, binding, selection, money, purpose));
    }
    private static String fingerprint(ExecutionIdentity id, ExecutionBinding binding, CapabilitySelection selection, ExactMoney money, OperationPurpose purpose) {
        return ContractValues.hash(List.of("provider-execution-operation-1", id.tenantId(), id.merchantId(), id.runId(), id.intentId(),
                id.attemptId(), id.operationId(), id.callerIdempotencyKey(), binding.digest(), selection.digest(), money.currency(),
                Integer.toString(money.exponent()), Long.toString(money.minorUnits()), purpose.name()));
    }
    @Override public String toString() { return "ExecutionOperation[redacted]"; }
}
