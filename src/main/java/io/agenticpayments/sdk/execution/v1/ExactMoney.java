package io.agenticpayments.sdk.execution.v1;

/** Exact positive minor units; support and exponent are selected explicitly, never inferred. */
public record ExactMoney(String currency, int exponent, long minorUnits) {
    public static final long MAX_MINOR_UNITS = 999_999_999_999_999_999L;
    public ExactMoney {
        if (currency == null || !currency.matches("[A-Z]{3}") || exponent < 0 || exponent > 9
                || minorUnits < 1 || minorUnits > MAX_MINOR_UNITS)
            throw new IllegalArgumentException("Invalid exact money.");
    }
    public static ExactMoney parse(String currency, int exponent, String units) {
        if (units == null || !units.matches("[1-9][0-9]{0,17}"))
            throw new IllegalArgumentException("Invalid canonical minor units.");
        return new ExactMoney(currency, exponent, Long.parseLong(units));
    }
}
