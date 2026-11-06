package io.agenticpayments.sdk.descriptor;

/** Declarative currency metadata; this does not expand the local Money execution profile. */
public record CurrencyLimit(String currency, int exponent, String minimumMinorUnits, String maximumMinorUnits) {
    public CurrencyLimit {
        currency = DescriptorValues.currency(currency);
        if (exponent < 0 || exponent > 9 || DescriptorValues.amount(minimumMinorUnits).compareTo(DescriptorValues.amount(maximumMinorUnits)) > 0) {
            throw new IllegalArgumentException("Invalid currency limit.");
        }
    }
    public boolean permits(String requestedCurrency, String minorUnits) {
        var amount = DescriptorValues.amount(minorUnits);
        return currency.equals(requestedCurrency) && amount.compareTo(DescriptorValues.amount(minimumMinorUnits)) >= 0
                && amount.compareTo(DescriptorValues.amount(maximumMinorUnits)) <= 0;
    }
    String canonical() { return currency + ":" + exponent + ":" + minimumMinorUnits + ":" + maximumMinorUnits; }
}
