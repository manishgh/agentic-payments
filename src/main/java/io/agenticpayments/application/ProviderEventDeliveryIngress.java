package io.agenticpayments.application;

import io.agenticpayments.sdk.execution.v1.ExecutionOperation;

/** Opt-in authenticated acceptance: exact protected bytes and delivery work commit atomically. */
@FunctionalInterface public interface ProviderEventDeliveryIngress {
    String CONTRACT="provider-event-delivery-ingress-1";
    /** Authenticate and validate the complete binding before acceptance. Receipt is not financial success. */
    String accept(ExecutionOperation expected,byte[] rawBody,String signature);
}
