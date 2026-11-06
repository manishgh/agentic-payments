package io.agenticpayments.sdk.descriptor;

import java.util.List;
import java.util.Set;

/** One explicit allowed combination, never a Cartesian product of provider-wide capabilities. */
public record AdapterCombination(String methodId, String credentialTypeId, Set<String> actionTypeIds,
                                 Set<String> operations, Set<String> capabilities, List<CurrencyLimit> limits) {
    public AdapterCombination {
        methodId = DescriptorValues.identifier(methodId);
        credentialTypeId = DescriptorValues.identifier(credentialTypeId);
        actionTypeIds = DescriptorValues.identifiers(actionTypeIds, false);
        operations = DescriptorValues.identifiers(operations, false);
        capabilities = DescriptorValues.identifiers(capabilities, true);
        limits = DescriptorValues.distinct(limits);
        if (limits.stream().map(CurrencyLimit::currency).distinct().count() != limits.size()) throw new IllegalArgumentException("Currency limits must be unambiguous.");
    }
    String canonical() {
        return String.join("|", methodId, credentialTypeId, DescriptorValues.sorted(actionTypeIds),
                DescriptorValues.sorted(operations), DescriptorValues.sorted(capabilities),
                limits.stream().map(CurrencyLimit::canonical).sorted().collect(java.util.stream.Collectors.joining(",")));
    }
}
