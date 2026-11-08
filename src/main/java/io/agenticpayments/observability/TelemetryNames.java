package io.agenticpayments.observability;

import java.util.Set;

/** Validation of trusted, startup-only telemetry catalogs. */
public final class TelemetryNames {
    private TelemetryNames() { }
    public static String require(String value) {
        if (value == null || !value.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("Invalid telemetry catalog name");
        }
        return value;
    }
    public static String field(String value) {
        require(value);
        String normalized = value.toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", "");
        if (Set.of("identity", "digest", "amount", "grant", "credential", "url", "header", "exception",
                "body", "jwt", "otp", "customer", "token", "password", "secret", "intentid",
                "operationid", "purchaseid", "sessionid").stream().anyMatch(normalized::contains)) {
            throw new IllegalArgumentException("Sensitive telemetry field");
        }
        return value;
    }
}
