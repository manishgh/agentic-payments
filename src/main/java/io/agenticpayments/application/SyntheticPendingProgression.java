package io.agenticpayments.application;

import io.agenticpayments.sdk.ChargeOperation;
import io.agenticpayments.sdk.Fact;

/** Explicit local-simulator scheduler step; never used by manual reconciliation. */
@FunctionalInterface
public interface SyntheticPendingProgression {
    Fact progress(ChargeOperation expected,Fact pending);
}
