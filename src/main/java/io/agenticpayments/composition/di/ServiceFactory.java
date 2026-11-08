package io.agenticpayments.composition.di;

@FunctionalInterface
public interface ServiceFactory<T> {
    T create(ServiceResolver dependencies);
}
