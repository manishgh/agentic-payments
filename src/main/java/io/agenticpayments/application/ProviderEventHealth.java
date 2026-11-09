package io.agenticpayments.application;

import io.agenticpayments.sdk.execution.v1.ExecutionOperation;

/** Durable event disputes gate new activity and evidence projection; this is not a no-effect fence. */
@FunctionalInterface public interface ProviderEventHealth {
    String CONTRACT="provider-event-health-1";
    void requireHealthy(ExecutionOperation expected);
}
