package io.agenticpayments.application;

/** @deprecated Use ApplicationException and the typed application failure catalog. */
@Deprecated
public final class PaymentException extends ApplicationException {
    public PaymentException(ApplicationFailure failure) { super(failure); }
}
