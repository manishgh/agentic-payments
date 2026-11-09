package io.agenticpayments.application;

/** Only retained event projection or authenticated dispute projection; never financial submission. */
@FunctionalInterface public interface ProviderEventDeliveryHandler {
    String CONTRACT="provider-event-delivery-handler-1";
    void deliver(ProviderEventDeliveryQueue.Delivery delivery);
}
