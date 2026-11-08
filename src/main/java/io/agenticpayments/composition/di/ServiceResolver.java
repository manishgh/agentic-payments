package io.agenticpayments.composition.di;

/** Factories receive this restricted, invocation-local view, never the provider. */
public interface ServiceResolver {
    <T> T get(ServiceKey<T> key);
}
