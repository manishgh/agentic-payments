package io.agenticpayments.application;
/** Safe signal requiring durable core quarantine, never a financial no-effect assertion. */
public final class ProviderEvidenceConflict extends ApplicationException {
    public ProviderEvidenceConflict(){super(ApplicationFailure.TRUSTED_RESOLUTION_REQUIRED);}
}
