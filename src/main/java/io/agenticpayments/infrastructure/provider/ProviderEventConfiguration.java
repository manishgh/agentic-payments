package io.agenticpayments.infrastructure.provider;

import io.agenticpayments.sdk.execution.v1.*;
import java.util.Objects;

/** Immutable trusted event policy; all references and versions must survive restart unchanged. */
public record ProviderEventConfiguration(String tenantId,String merchantId,String runId,ExecutionBinding binding,
        ExecutionOffer offer,String policyReference,String policyVersion,String keyReference,String keyVersion) {
    public ProviderEventConfiguration {
        Objects.requireNonNull(binding);Objects.requireNonNull(offer);
        new ExecutionIdentity(tenantId,merchantId,runId,policyReference,policyVersion,keyReference,keyVersion);
        if(!offer.providerId().equals(binding.providerId()))throw invalid();
    }
    public void validate(ExecutionOperation operation){
        var id=operation.identity();
        if(!tenantId.equals(id.tenantId())||!merchantId.equals(id.merchantId())||!runId.equals(id.runId())||!binding.equals(operation.binding()))throw invalid();
        operation.selection().validate(offer,binding);
        if(!operation.selection().combination().capabilities().contains(ExecutionOffer.Capability.VERIFIED_EVENTS))throw invalid();
    }
    @Override public String toString(){return "ProviderEventConfiguration[redacted]";}
    private static ExecutionFailure invalid(){return new ExecutionFailure(ExecutionFailure.Code.INVALID_EVENT,ExecutionFailure.Certainty.NOT_STARTED);}
}
