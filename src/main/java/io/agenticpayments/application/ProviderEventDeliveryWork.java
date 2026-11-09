package io.agenticpayments.application;
/** A host schedules bounded delivery steps; execution never submits a financial operation. */
@FunctionalInterface public interface ProviderEventDeliveryWork {
    String CONTRACT="provider-event-delivery-work-1";
    boolean runOnce();
}
