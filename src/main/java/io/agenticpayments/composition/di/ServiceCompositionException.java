package io.agenticpayments.composition.di;

/** Fixed safe diagnostics deliberately omit constructor, factory and cleanup causes. */
public final class ServiceCompositionException extends IllegalStateException {
    public ServiceCompositionException(String message) { super(message); }
}
