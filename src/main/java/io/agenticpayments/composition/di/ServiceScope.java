package io.agenticpayments.composition.di;

/** Thread-confined lifetime boundary; a scope grants no application authority. */
public interface ServiceScope extends ServiceResolver, AutoCloseable {
    @Override void close();
}
