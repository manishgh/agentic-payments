package io.agenticpayments.ap2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Base64;
import java.util.Set;

/** Closed claim values for the deliberately bounded direct TAP profile. No trust is inferred here. */
public final class Ap2Claims {
    public static final String PROFILE = "ap2-v0.2-direct-tap-synthetic-0.1";
    public static final String CREDENTIAL_TYPE = "https://agentic-payments.example/profiles/ap2-direct/1";
    public static final String SD_JWT_TYPE = "dc+sd-jwt";
    public static final String CHECKOUT_TYPE = "mandate.checkout.1";
    public static final String PAYMENT_TYPE = "mandate.payment.1";
    private static final int MAX_TEXT = 2048;
    private static final int MAX_CHECKOUT = 131_072;

    private Ap2Claims() {}

    public sealed interface Content permits CheckoutMandate, PaymentMandate {
        String type();
    }

    public record CheckoutMandate(String checkoutJwt, String checkoutHash) implements Content {
        public CheckoutMandate {
            compact(checkoutJwt);
            digest(checkoutHash);
        }
        @Override public String type() { return CHECKOUT_TYPE; }
        @Override public String toString() { return "CheckoutMandate[redacted]"; }
    }

    public record PaymentMandate(String transactionId, Merchant payee, Amount paymentAmount,
                                 PaymentInstrument paymentInstrument) implements Content {
        public PaymentMandate {
            digest(transactionId);
            required(payee); required(paymentAmount); required(paymentInstrument);
        }
        @Override public String type() { return PAYMENT_TYPE; }
        @Override public String toString() { return "PaymentMandate[redacted]"; }
    }

    public record Merchant(String id, String name, String website) {
        public Merchant { text(id); text(name); text(website); }
        @Override public String toString() { return "Merchant[redacted]"; }
    }

    public record Amount(long amount, String currency) {
        public Amount {
            if (amount < 1 || (!"EUR".equals(currency) && !"USD".equals(currency))) throw invalid();
        }
        @Override public String toString() { return "Amount[redacted]"; }
    }

    public record PaymentInstrument(String id, String type, String description) {
        public PaymentInstrument { text(id); text(type); optionalText(description); }
        @Override public String toString() { return "PaymentInstrument[redacted]"; }
    }

    public record Envelope(String issuer, long issuedAt, long expiresAt, String audience,
                           String nonce, String jwtId, Content content) {
        public Envelope {
            metadata(issuer, issuedAt, expiresAt, audience, nonce, jwtId);
            required(content);
        }
        @Override public String toString() { return "Envelope[redacted]"; }
    }

    public sealed interface Receipt permits CheckoutReceipt, PaymentReceipt {
        String issuer();
        long issuedAt();
        long expiresAt();
        String audience();
        String nonce();
        String jwtId();
        String reference();
        String status();
        String result();
        String error();
        String errorDescription();
    }

    public record CheckoutReceipt(String issuer, long issuedAt, long expiresAt, String audience,
                                  String nonce, String jwtId, String reference, String status,
                                  String result, String orderId, String error,
                                  String errorDescription) implements Receipt {
        public CheckoutReceipt {
            metadata(issuer, issuedAt, expiresAt, audience, nonce, jwtId);
            receipt(reference, status, result, error, errorDescription);
            if ("Success".equals(status)) text(orderId);
            else if (orderId != null) throw invalid();
        }
        @Override public String toString() { return "CheckoutReceipt[redacted]"; }
    }

    public record PaymentReceipt(String issuer, long issuedAt, long expiresAt, String audience,
                                 String nonce, String jwtId, String reference, String status,
                                 String result, String paymentId, String pspConfirmationId,
                                 String networkConfirmationId, String error,
                                 String errorDescription) implements Receipt {
        public PaymentReceipt {
            metadata(issuer, issuedAt, expiresAt, audience, nonce, jwtId);
            receipt(reference, status, result, error, errorDescription);
            text(paymentId);
            if ("Success".equals(status)) {
                text(pspConfirmationId); text(networkConfirmationId);
            } else if (pspConfirmationId != null || networkConfirmationId != null) throw invalid();
        }
        @Override public String toString() { return "PaymentReceipt[redacted]"; }
    }

    /** Parse only after the enclosing SD-JWT signature and framing have been authenticated. */
    public static Envelope envelope(JsonNode node) {
        fields(node, Set.of("iss", "iat", "exp", "aud", "nonce", "jti", "vct", "_sd_alg", "delegate_payload"));
        if (!CREDENTIAL_TYPE.equals(string(node, "vct")) || !"sha-256".equals(string(node, "_sd_alg"))) throw invalid();
        JsonNode delegated = node.get("delegate_payload");
        if (delegated == null || !delegated.isArray() || delegated.size() != 1) throw invalid();
        return new Envelope(string(node, "iss"), integer(node, "iat"), integer(node, "exp"),
                string(node, "aud"), string(node, "nonce"), string(node, "jti"), content(delegated.get(0)));
    }

    public static CheckoutReceipt checkoutReceipt(JsonNode node) {
        fields(node, Set.of("iss", "iat", "exp", "aud", "nonce", "jti", "reference", "status", "result",
                "order_id", "error", "error_description"));
        return new CheckoutReceipt(string(node, "iss"), integer(node, "iat"), integer(node, "exp"),
                string(node, "aud"), string(node, "nonce"), string(node, "jti"), string(node, "reference"),
                string(node, "status"), string(node, "result"), optional(node, "order_id"),
                optional(node, "error"), optional(node, "error_description"));
    }

    public static PaymentReceipt paymentReceipt(JsonNode node) {
        fields(node, Set.of("iss", "iat", "exp", "aud", "nonce", "jti", "reference", "status", "result",
                "payment_id", "psp_confirmation_id", "network_confirmation_id", "error", "error_description"));
        return new PaymentReceipt(string(node, "iss"), integer(node, "iat"), integer(node, "exp"),
                string(node, "aud"), string(node, "nonce"), string(node, "jti"), string(node, "reference"),
                string(node, "status"), string(node, "result"), string(node, "payment_id"),
                optional(node, "psp_confirmation_id"), optional(node, "network_confirmation_id"),
                optional(node, "error"), optional(node, "error_description"));
    }

    public static ObjectNode toJson(Envelope value) {
        required(value);
        ObjectNode node = metadataJson(value.issuer(), value.issuedAt(), value.expiresAt(), value.audience(), value.nonce(), value.jwtId());
        node.put("vct", CREDENTIAL_TYPE).put("_sd_alg", "sha-256");
        node.putArray("delegate_payload").add(contentJson(value.content()));
        return node;
    }

    public static ObjectNode toJson(CheckoutReceipt value) {
        required(value);
        ObjectNode node = receiptJson(value);
        putOptional(node, "order_id", value.orderId());
        return node;
    }

    public static ObjectNode toJson(PaymentReceipt value) {
        required(value);
        ObjectNode node = receiptJson(value).put("payment_id", value.paymentId());
        putOptional(node, "psp_confirmation_id", value.pspConfirmationId());
        putOptional(node, "network_confirmation_id", value.networkConfirmationId());
        return node;
    }

    private static Content content(JsonNode node) {
        if (node == null || !node.isObject()) throw invalid();
        String type = string(node, "vct");
        if (CHECKOUT_TYPE.equals(type)) {
            fields(node, Set.of("vct", "checkout_jwt", "checkout_hash"));
            return new CheckoutMandate(string(node, "checkout_jwt"), string(node, "checkout_hash"));
        }
        if (!PAYMENT_TYPE.equals(type)) throw invalid();
        fields(node, Set.of("vct", "transaction_id", "payee", "payment_amount", "payment_instrument"));
        JsonNode payee = node.get("payee");
        fields(payee, Set.of("id", "name", "website"));
        JsonNode amount = node.get("payment_amount");
        fields(amount, Set.of("amount", "currency"));
        JsonNode instrument = node.get("payment_instrument");
        fields(instrument, Set.of("id", "type", "description"));
        return new PaymentMandate(string(node, "transaction_id"),
                new Merchant(string(payee, "id"), string(payee, "name"), string(payee, "website")),
                new Amount(integer(amount, "amount"), string(amount, "currency")),
                new PaymentInstrument(string(instrument, "id"), string(instrument, "type"), optional(instrument, "description")));
    }

    private static ObjectNode contentJson(Content value) {
        ObjectNode node = object().put("vct", value.type());
        if (value instanceof CheckoutMandate checkout) {
            return node.put("checkout_jwt", checkout.checkoutJwt()).put("checkout_hash", checkout.checkoutHash());
        }
        PaymentMandate payment = (PaymentMandate) value;
        node.put("transaction_id", payment.transactionId());
        Merchant payee = payment.payee();
        node.set("payee", object().put("id", payee.id()).put("name", payee.name()).put("website", payee.website()));
        node.set("payment_amount", object().put("amount", payment.paymentAmount().amount()).put("currency", payment.paymentAmount().currency()));
        PaymentInstrument instrument = payment.paymentInstrument();
        ObjectNode instrumentNode = object().put("id", instrument.id()).put("type", instrument.type());
        putOptional(instrumentNode, "description", instrument.description());
        node.set("payment_instrument", instrumentNode);
        return node;
    }

    private static ObjectNode receiptJson(Receipt value) {
        ObjectNode node = metadataJson(value.issuer(), value.issuedAt(), value.expiresAt(), value.audience(), value.nonce(), value.jwtId())
                .put("reference", value.reference()).put("status", value.status()).put("result", value.result());
        putOptional(node, "error", value.error());
        putOptional(node, "error_description", value.errorDescription());
        return node;
    }

    private static ObjectNode metadataJson(String issuer, long issuedAt, long expiresAt, String audience, String nonce, String jwtId) {
        return object().put("iss", issuer).put("iat", issuedAt).put("exp", expiresAt)
                .put("aud", audience).put("nonce", nonce).put("jti", jwtId);
    }

    private static void metadata(String issuer, long issuedAt, long expiresAt, String audience, String nonce, String jwtId) {
        text(issuer); text(audience); text(nonce); text(jwtId);
        if (issuedAt < 0 || expiresAt <= issuedAt) throw invalid();
    }

    private static void receipt(String reference, String status, String result, String error, String description) {
        digest(reference);
        if ("Success".equals(status) && "success".equals(result)) {
            if (error != null || description != null) throw invalid();
        } else if ("Error".equals(status) && "error".equals(result)) {
            text(error); text(description);
        } else throw invalid();
    }

    private static void fields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) throw invalid();
        var names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name) || node.get(name).isNull()) throw invalid();
        }
    }

    private static String string(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual()) throw invalid();
        return value.textValue();
    }

    private static String optional(JsonNode node, String key) {
        return node.has(key) ? string(node, key) : null;
    }

    private static long integer(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
        return value.longValue();
    }

    private static void compact(String value) {
        if (value == null || value.length() > MAX_CHECKOUT || !value.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) throw invalid();
    }

    private static void digest(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (decoded.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) throw invalid();
        } catch (IllegalArgumentException ignored) {
            throw invalid();
        }
    }

    private static void text(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_TEXT) throw invalid();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isISOControl(current)) throw invalid();
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) throw invalid();
            } else if (Character.isLowSurrogate(current)) throw invalid();
        }
    }

    private static void optionalText(String value) { if (value != null) text(value); }
    private static void required(Object value) { if (value == null) throw invalid(); }
    private static ObjectNode object() { return JsonNodeFactory.instance.objectNode(); }
    private static void putOptional(ObjectNode node, String key, String value) { if (value != null) node.put(key, value); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid_claims"); }
}
