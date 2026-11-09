package io.agenticpayments.application;

import io.agenticpayments.sdk.execution.v1.ExecutionOperation;

/** Resolves durable authenticated dispute provenance, without current key/configuration substitution. */
@FunctionalInterface public interface ProviderEventDisputeVerifier {
    String CONTRACT="provider-event-dispute-verifier-1";
    void requireQuarantined(ExecutionOperation expected);
}
