package io.agenticpayments.sdk.descriptor;

import java.util.Objects;

/** A metadata selection. A separate execution policy and current spend authority remain required. */
public record NegotiatedDescriptor(String descriptorDigest, AdapterSelection selection,
                                   AdapterCombination combination, CurrencyLimit currencyLimit, String accountAlias) {
    public NegotiatedDescriptor {
        if (descriptorDigest == null || !descriptorDigest.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid descriptor digest.");
        Objects.requireNonNull(selection); Objects.requireNonNull(combination); Objects.requireNonNull(currencyLimit);
        accountAlias = DescriptorValues.identifier(accountAlias);
    }
}
