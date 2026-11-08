package io.agenticpayments.application;
import io.agenticpayments.sdk.OperationIdentity;
/** Load the exact durable core/provider association; absence never selects another route. */
@FunctionalInterface public interface ProviderExecutionBindings {
    String CONTRACT="provider-execution-bindings-1";
    ProviderExecutionBinding load(OperationIdentity identity);
}
