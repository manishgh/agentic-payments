package io.agenticpayments.application;
import io.agenticpayments.sdk.ChargeOperation;
/** Pure trusted policy; no storage or provider calls. Direct, adapted and wrapped implementations obey the same contract. */
@FunctionalInterface public interface ProviderExecutionRegistration {
    String CONTRACT="provider-execution-registration-1";
    ProviderExecutionBinding binding(ChargeOperation core);
}
