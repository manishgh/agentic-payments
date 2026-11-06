package io.agenticpayments.sdk.descriptor;

import java.util.Set;

public record AdapterSelection(String tenantId, String merchantId, String providerId, String descriptorRevision,
                               String extensionVersion, String profile, String methodId, String credentialTypeId,
                               String actionTypeId, Set<String> requiredCapabilities, String operation,
                               String currency, String minorUnits) {
    public AdapterSelection {
        tenantId = DescriptorValues.revision(tenantId);
        merchantId = DescriptorValues.identifier(merchantId);
        providerId = DescriptorValues.identifier(providerId);
        descriptorRevision = DescriptorValues.revision(descriptorRevision);
        extensionVersion = DescriptorValues.revision(extensionVersion);
        profile = DescriptorValues.revision(profile);
        methodId = DescriptorValues.identifier(methodId);
        credentialTypeId = DescriptorValues.identifier(credentialTypeId);
        actionTypeId = DescriptorValues.identifier(actionTypeId);
        requiredCapabilities = DescriptorValues.identifiers(requiredCapabilities, true);
        operation = DescriptorValues.identifier(operation);
        currency = DescriptorValues.currency(currency);
        DescriptorValues.amount(minorUnits);
    }
}
