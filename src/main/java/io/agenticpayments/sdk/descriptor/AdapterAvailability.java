package io.agenticpayments.sdk.descriptor;

import java.time.Instant;
import java.util.Objects;

/** Trusted configuration snapshot supplied by the host; never accepted as a browser assertion. */
public record AdapterAvailability(String providerId, String descriptorRevision, String tenantId, String merchantId,
                                  String accountAlias, boolean configured, boolean enabled, boolean available,
                                  Instant validFrom, Instant expiresAt) {
    public AdapterAvailability {
        providerId = DescriptorValues.identifier(providerId);
        descriptorRevision = DescriptorValues.revision(descriptorRevision);
        tenantId = DescriptorValues.revision(tenantId);
        merchantId = DescriptorValues.identifier(merchantId);
        accountAlias = DescriptorValues.identifier(accountAlias);
        Objects.requireNonNull(validFrom); Objects.requireNonNull(expiresAt);
        if (!validFrom.isBefore(expiresAt)) throw new IllegalArgumentException("Invalid availability interval.");
    }
}
