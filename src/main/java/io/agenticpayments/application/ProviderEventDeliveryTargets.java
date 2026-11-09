package io.agenticpayments.application;
import io.agenticpayments.sdk.execution.v1.ExecutionOperation;
/** Resolve the original retained core association without choosing a newer attempt or configuration. */
@FunctionalInterface public interface ProviderEventDeliveryTargets {
    String CONTRACT="provider-event-delivery-targets-1";
    ProviderExecutionBinding load(ExecutionOperation expected);
}
