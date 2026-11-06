package io.agenticpayments.sdk;

/** Transport uncertainty is safe to expose and must trigger same-operation reconciliation. */
public final class AdapterUnavailableException extends RuntimeException {
    public AdapterUnavailableException() {
        super("The existing adapter operation requires reconciliation.");
    }
}
