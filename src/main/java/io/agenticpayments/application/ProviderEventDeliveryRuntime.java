package io.agenticpayments.application;
/** Replaceable delivery scheduling lifecycle; durable queue state outlives this runtime. */
public interface ProviderEventDeliveryRuntime extends AutoCloseable {
    String CONTRACT="provider-event-delivery-runtime-1";
    void start();
    @Override void close();
}
