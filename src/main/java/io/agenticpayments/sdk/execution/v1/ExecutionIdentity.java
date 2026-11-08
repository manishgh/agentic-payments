package io.agenticpayments.sdk.execution.v1;

/** Trusted scope; provider/account/region are immutable values, not namespace escape hatches. */
public record ExecutionIdentity(String tenantId, String merchantId, String runId, String intentId,
                                String attemptId, String operationId, String callerIdempotencyKey) {
    public ExecutionIdentity {
        tenantId = ContractValues.token(tenantId); merchantId = ContractValues.token(merchantId);
        runId = ContractValues.token(runId); intentId = ContractValues.token(intentId);
        attemptId = ContractValues.token(attemptId); operationId = ContractValues.token(operationId);
        callerIdempotencyKey = ContractValues.token(callerIdempotencyKey);
    }
    public ExecutionKey key() { return new ExecutionKey(tenantId, merchantId, runId, callerIdempotencyKey); }
    public record ExecutionKey(String tenantId, String merchantId, String runId, String callerIdempotencyKey) {
        public ExecutionKey {
            tenantId = ContractValues.token(tenantId); merchantId = ContractValues.token(merchantId);
            runId = ContractValues.token(runId); callerIdempotencyKey = ContractValues.token(callerIdempotencyKey);
        }
        @Override public String toString() { return "ExecutionKey[redacted]"; }
    }
    @Override public String toString() { return "ExecutionIdentity[redacted]"; }
}
