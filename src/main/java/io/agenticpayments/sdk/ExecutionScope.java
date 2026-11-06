package io.agenticpayments.sdk;

import java.util.Objects;
import java.util.UUID;

/** Scope is supplied by trusted authentication/configuration, never an API body. */
public record ExecutionScope(String tenantId, UUID runId, String merchantId) {
    public ExecutionScope {
        tenantId = SdkValidation.fixtureId(tenantId, "tenantId");
        Objects.requireNonNull(runId, "runId");
        merchantId = SdkValidation.fixtureId(merchantId, "merchantId");
    }
}
