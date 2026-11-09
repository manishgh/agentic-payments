package io.agenticpayments.application;

import io.agenticpayments.sdk.execution.v1.ExecutionOperation;

/** Protected exact-byte storage. Only a scoped, admitted operation may reach this port. */
@FunctionalInterface public interface ProviderEventIngress {
    String CONTRACT="provider-event-ingress-1";
    /** Retention is not authenticity or financial admission. Never expose the bytes in diagnostics. */
    String retain(ExecutionOperation expected,byte[] rawBody,String signature);
}
