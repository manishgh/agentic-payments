package io.agenticpayments.composition.di;

/** Immutable root graph. Only singleton services can be obtained from the root. */
public interface ServiceProvider extends ServiceResolver, AutoCloseable {
    ServiceScope createScope();
    @Override void close();
}
