package io.agenticpayments.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/** Pure local-profile rules. No transport, persistence or provider dependencies. */
public final class PaymentRules {
    public static final String PROFILE = "local-fake-single-tender-0.1";
    public static final String CONTRACT = "simulator-api-0.1-draft";
    public static final Set<String> CAPABILITIES = Set.of("single-tender", "exact-money", "customer-action", "idempotent-execution", "reconciliation");
    public static final Set<String> SCENARIOS = Set.of("success", "challenge-then-success", "response-loss-after-acceptance", "delayed-success", "definitive-no-effect");
    public static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED");
    public static final Set<String> ADMITTED = Set.of("ADMITTED", "PENDING", "UNKNOWN", "SUCCEEDED");
    private PaymentRules() {}
    public static String sha256(String value) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is unavailable"); }
    }
    public static String checkout(String merchant, int revision, String cart, String currency, String credential) {
        if (!merchant.equals("merchant-demo") || !Set.of("EUR", "USD").contains(currency) || !credential.equals("fixture-credential-1")) {
            throw new IllegalArgumentException("Unsupported fixture binding");
        }
        return "{\"schema\":\"local-checkout-0.1\",\"merchantId\":\"" + merchant + "\",\"checkoutRevision\":" + revision
                + ",\"catalogVersion\":\"fixtures-0.1\",\"cartFixtureId\":\"" + cart + "\",\"items\":[{\"sku\":\"sku-demo\",\"quantity\":1,\"unitMinorUnits\":\"1250\"}],\"fulfillmentFixtureId\":\"delivery-demo\",\"money\":{\"currency\":\""
                + currency + "\",\"minorUnits\":\"1250\"},\"tender\":{\"provider\":\"simulator\",\"method\":\"synthetic-card\",\"credentialReference\":\"" + credential + "\"}}";
    }
}
