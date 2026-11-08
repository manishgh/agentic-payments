package io.agenticpayments.sdk.execution.v1;

/** Verify authenticity/account/request binding independently of financial status normalization. */
@FunctionalInterface public interface ProviderEventVerifier {
    VerifiedEventReference verify(EventVerificationRequest request);
}
