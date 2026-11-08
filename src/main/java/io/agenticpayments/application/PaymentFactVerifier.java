package io.agenticpayments.application;

import io.agenticpayments.sdk.ChargeOperation;
import io.agenticpayments.sdk.Fact;

/** Trusted provenance lookup; a constructed fact alone is not authority. */
@FunctionalInterface
public interface PaymentFactVerifier {
    Fact verify(ChargeOperation expected,Fact delivered);
}
