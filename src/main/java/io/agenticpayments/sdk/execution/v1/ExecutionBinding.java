package io.agenticpayments.sdk.execution.v1;

import java.util.List;

/** References are resolved by trusted infrastructure; no secrets or current-default indirection. */
public record ExecutionBinding(String providerId, String accountRef, String region,
                               String configurationRef, String configurationVersion,
                               String credentialRef, String credentialVersion, String executionOwnerId) {
    public ExecutionBinding {
        providerId = ContractValues.token(providerId); accountRef = ContractValues.token(accountRef);
        region = ContractValues.token(region); configurationRef = ContractValues.token(configurationRef);
        configurationVersion = ContractValues.token(configurationVersion); credentialRef = ContractValues.token(credentialRef);
        credentialVersion = ContractValues.token(credentialVersion); executionOwnerId = ContractValues.token(executionOwnerId);
    }
    public String digest() { return ContractValues.hash(List.of("provider-execution-binding-1", providerId,
            accountRef, region, configurationRef, configurationVersion, credentialRef, credentialVersion, executionOwnerId)); }
    @Override public String toString() { return "ExecutionBinding[redacted]"; }
}
