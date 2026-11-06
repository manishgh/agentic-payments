package io.agenticpayments.sdk;

/** Exact full-charge money for the local EUR/USD exponent-two profile. */
public record Money(String currency, long minorUnits) {
    public static final long MAX_MINOR_UNITS = 999_999_999_999L;

    public Money {
        if (!"EUR".equals(currency) && !"USD".equals(currency)) {
            throw new IllegalArgumentException("Unsupported local-fake currency");
        }
        if (minorUnits < 1 || minorUnits > MAX_MINOR_UNITS) {
            throw new IllegalArgumentException("Money must be a positive bounded minor-unit amount");
        }
    }

    public static Money parse(String currency, String minorUnits) {
        if (minorUnits == null || !minorUnits.matches("[1-9][0-9]{0,11}")) {
            throw new IllegalArgumentException("Money must use a positive decimal integer string");
        }
        return new Money(currency, Long.parseLong(minorUnits));
    }

    public String decimalString() {
        return Long.toString(minorUnits);
    }
}
