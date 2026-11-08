package io.agenticpayments.application;
import io.agenticpayments.sdk.ChargeOperation;
import io.agenticpayments.sdk.execution.v1.*;
import java.time.Instant;
import java.util.Objects;
/** Complete immutable admission association, including the approved credential reference. */
public record ProviderExecutionBinding(String profile,String mappingPolicy,String scenarioPolicyReference,
        String scenarioPolicyVersion,ChargeOperation coreOperation,String credentialReference,
        ExecutionOperation operation,Instant observedAt,Instant actionExpiresAt) {
    public ProviderExecutionBinding {
        Objects.requireNonNull(profile);Objects.requireNonNull(mappingPolicy);Objects.requireNonNull(scenarioPolicyReference);
        Objects.requireNonNull(scenarioPolicyVersion);Objects.requireNonNull(coreOperation);Objects.requireNonNull(credentialReference);
        Objects.requireNonNull(operation);Objects.requireNonNull(observedAt);Objects.requireNonNull(actionExpiresAt);
        for(String token:java.util.List.of(profile,mappingPolicy,scenarioPolicyReference,scenarioPolicyVersion,credentialReference))
            if(!token.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))throw new IllegalArgumentException("Invalid provider admission policy.");
        if(!coreOperation.money().currency().equals(operation.money().currency())
                ||coreOperation.money().minorUnits()!=operation.money().minorUnits()||operation.money().exponent()!=2
                ||operation.purpose()!=OperationPurpose.CHARGE||!observedAt.isBefore(actionExpiresAt)
                ||!credentialReference.equals(operation.binding().credentialRef()))
            throw new IllegalArgumentException("Invalid provider admission binding.");
        var core=coreOperation.identity();var provider=operation.identity();
        if(!core.scope().tenantId().equals(provider.tenantId())||!core.scope().merchantId().equals(provider.merchantId())
                ||!core.scope().runId().toString().equals(provider.runId())||!core.intentId().toString().equals(provider.intentId())
                ||!core.attemptId().toString().equals(provider.attemptId())
                ||!(core.operationId()+":"+coreOperation.scenarioId()).equals(provider.operationId())
                ||!core.adapterKey().toString().equals(provider.callerIdempotencyKey()))
            throw new IllegalArgumentException("Invalid provider admission identity.");
    }
    @Override public String toString(){return "ProviderExecutionBinding[redacted]";}
}
