package io.agenticpayments.application;

/** Immutable action registration; exact retry must retain the original action and expiry. */
public interface ProviderActionRegistry {
    String CONTRACT="provider-action-registry-1";
    void register(ProviderActionBinding binding);
}
