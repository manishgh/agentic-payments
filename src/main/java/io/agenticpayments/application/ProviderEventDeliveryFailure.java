package io.agenticpayments.application;

import java.util.Objects;

/** Closed safe delivery failure; exception messages/causes and payloads are never persisted. */
public final class ProviderEventDeliveryFailure extends RuntimeException {
    private final ProviderEventDeliveryQueue.Failure failure;
    private final boolean permanent;
    public ProviderEventDeliveryFailure(ProviderEventDeliveryQueue.Failure failure,boolean permanent){
        super("Provider event delivery failed.",null,false,false);this.failure=Objects.requireNonNull(failure);this.permanent=permanent;
        if(failure==ProviderEventDeliveryQueue.Failure.NONE)throw new IllegalArgumentException("Invalid delivery failure.");
    }
    public ProviderEventDeliveryQueue.Failure failure(){return failure;}
    public boolean permanent(){return permanent;}
    @Override public String toString(){return "ProviderEventDeliveryFailure["+failure+"]";}
}
