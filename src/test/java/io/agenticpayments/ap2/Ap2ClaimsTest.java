package io.agenticpayments.ap2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.function.Consumer;

import static io.agenticpayments.ap2.Ap2Claims.*;
import static org.junit.jupiter.api.Assertions.*;

class Ap2ClaimsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HASH = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String CHECKOUT = "e30.e30.c2lnbmF0dXJl";

    private static Envelope envelope(Content content) {
        return new Envelope("https://tap.example", 100, 200, "verifier", "challenge", "mandate-1", content);
    }

    private static PaymentMandate payment() {
        return new PaymentMandate(HASH, new Merchant("merchant-1", "Synthetic Merchant", "https://merchant.example"),
                new Amount(15000, "EUR"), new PaymentInstrument("synthetic-1", "synthetic", null));
    }

    private static ObjectNode paymentNode() {
        return (ObjectNode) toJson(envelope(payment())).get("delegate_payload").get(0);
    }

    private static void refuses(Runnable operation) {
        var failure = assertThrows(IllegalArgumentException.class, operation::run);
        assertEquals("invalid_claims", failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test
    void nativeClosedMandatesRoundTripAndSerializationDoesNotExposeMutableState() {
        var checkout = envelope(new CheckoutMandate(CHECKOUT, HASH));
        var payment = envelope(payment());
        assertEquals(checkout, Ap2Claims.envelope(toJson(checkout)));
        assertEquals(payment, Ap2Claims.envelope(toJson(payment)));
        assertEquals(CHECKOUT_TYPE, checkout.content().type());
        assertEquals(PAYMENT_TYPE, payment.content().type());
        ObjectNode tree = toJson(checkout);
        ((ObjectNode) tree.get("delegate_payload").get(0)).put("checkout_jwt", "changed");
        assertEquals(CHECKOUT, ((CheckoutMandate) checkout.content()).checkoutJwt());
        assertEquals(checkout, Ap2Claims.envelope(toJson(checkout)));
    }

    @Test
    void delegatesExactlyOneInlineClosedMandateAndRejectsUnsupportedAuthorityShapes() {
        List<Consumer<ObjectNode>> mutations = List.of(
                node -> node.put("vct", "other-credential"),
                node -> node.put("_sd_alg", "sha-512"),
                node -> node.putArray("delegate_payload"),
                node -> node.withArray("delegate_payload").add(paymentNode()),
                node -> node.putObject("cnf"),
                node -> node.putArray("_sd"),
                node -> node.put("sd_hash", HASH),
                node -> node.putArray("aud").add("verifier"),
                node -> node.putNull("nonce"),
                node -> ((ObjectNode) node.get("delegate_payload").get(0)).put("vct", "mandate.payment.open.1"),
                node -> ((ObjectNode) node.get("delegate_payload").get(0)).putArray("constraints"),
                node -> ((ObjectNode) node.get("delegate_payload").get(0)).putObject("cnf"),
                node -> ((ObjectNode) node.get("delegate_payload").get(0)).put("vct", "mandate.payment.2"));
        for (Consumer<ObjectNode> mutate : mutations) {
            ObjectNode tree = toJson(envelope(payment()));
            mutate.accept(tree);
            refuses(() -> Ap2Claims.envelope(tree));
        }
    }

    @Test
    void exactMoneyRejectsCoercionOverflowUnsupportedCurrenciesAndAdditionalFields() {
        for (String number : List.of("1.0", "1e3", "\"15000\"", "9223372036854775808", "0", "-1")) {
            ObjectNode tree = toJson(envelope(payment()));
            try {
                ((ObjectNode) tree.at("/delegate_payload/0/payment_amount")).set("amount", JSON.readTree(number));
            } catch (Exception failure) {
                fail("Test money fixture must parse", failure);
            }
            refuses(() -> Ap2Claims.envelope(tree));
        }
        var largest = new Amount(Long.MAX_VALUE, "USD");
        var value = envelope(new PaymentMandate(HASH, payment().payee(), largest, payment().paymentInstrument()));
        assertEquals(value, Ap2Claims.envelope(toJson(value)));
        for (String currency : List.of("eur", "GBP", "USD ")) refuses(() -> new Amount(1, currency));
        ObjectNode unknown = toJson(envelope(payment()));
        ((ObjectNode) unknown.at("/delegate_payload/0/payment_amount")).put("exponent", 2);
        refuses(() -> Ap2Claims.envelope(unknown));
        ObjectNode oversized = toJson(envelope(payment()));
        ((ObjectNode) oversized.at("/delegate_payload/0/payment_amount")).put("amount", BigInteger.ONE.shiftLeft(100));
        refuses(() -> Ap2Claims.envelope(oversized));
    }

    @Test
    void optionalInstrumentDescriptionDistinguishesAbsenceFromNullAndRejectsExtensions() {
        ObjectNode tree = toJson(envelope(payment()));
        ObjectNode instrument = (ObjectNode) tree.at("/delegate_payload/0/payment_instrument");
        assertFalse(instrument.has("description"));
        assertEquals(envelope(payment()), Ap2Claims.envelope(tree));
        instrument.putNull("description");
        refuses(() -> Ap2Claims.envelope(tree));
        instrument.put("description", "Synthetic reference");
        assertEquals("Synthetic reference", ((PaymentMandate) Ap2Claims.envelope(tree).content()).paymentInstrument().description());
        instrument.put("provider_token", "SYNTHETIC_CANARY");
        refuses(() -> Ap2Claims.envelope(tree));
    }

    @Test
    void paymentFieldsAreCompleteAndCannotSmuggleUnsupportedExecutionData() {
        for (String removed : List.of("transaction_id", "payee", "payment_amount", "payment_instrument")) {
            ObjectNode tree = toJson(envelope(payment()));
            ((ObjectNode) tree.get("delegate_payload").get(0)).remove(removed);
            refuses(() -> Ap2Claims.envelope(tree));
        }
        for (String added : List.of("risk_data", "execution_date", "pisp", "iat", "unknown")) {
            ObjectNode tree = toJson(envelope(payment()));
            ((ObjectNode) tree.get("delegate_payload").get(0)).put(added, "SYNTHETIC_CANARY");
            refuses(() -> Ap2Claims.envelope(tree));
        }
        ObjectNode tree = toJson(envelope(payment()));
        ((ObjectNode) tree.at("/delegate_payload/0/payee")).remove("website");
        refuses(() -> Ap2Claims.envelope(tree));
    }

    @Test
    void bothTypedReceiptBranchesRoundTripAndGenericStatusMustAgree() {
        var checkoutSuccess = new CheckoutReceipt("merchant", 100, 200, "agent", "nonce", "receipt-c1", HASH,
                "Success", "success", "synthetic-order", null, null);
        var checkoutError = new CheckoutReceipt("merchant", 100, 200, "agent", "nonce", "receipt-c2", HASH,
                "Error", "error", null, "invalid_mandate", "The mandate was rejected.");
        var paymentSuccess = new PaymentReceipt("mpp", 100, 200, "agent", "nonce", "receipt-p1", HASH,
                "Success", "success", "synthetic-payment", "synthetic-psp", "synthetic-network", null, null);
        var paymentError = new PaymentReceipt("cp", 100, 200, "agent", "nonce", "receipt-p2", HASH,
                "Error", "error", "synthetic-attempt", null, null, "invalid_credential", "The mandate was rejected.");
        assertEquals(checkoutSuccess, checkoutReceipt(toJson(checkoutSuccess)));
        assertEquals(checkoutError, checkoutReceipt(toJson(checkoutError)));
        assertEquals(paymentSuccess, paymentReceipt(toJson(paymentSuccess)));
        assertEquals(paymentError, paymentReceipt(toJson(paymentError)));
        ObjectNode inconsistent = toJson(paymentSuccess).put("result", "error");
        refuses(() -> paymentReceipt(inconsistent));
        ObjectNode absentGeneric = toJson(checkoutSuccess);
        absentGeneric.remove("result");
        refuses(() -> checkoutReceipt(absentGeneric));
    }

    @Test
    void receiptsRequireAllBranchFieldsAndRejectContradictoryFields() {
        var success = new PaymentReceipt("mpp", 100, 200, "agent", "nonce", "receipt-p1", HASH,
                "Success", "success", "synthetic-payment", "synthetic-psp", "synthetic-network", null, null);
        for (String removed : List.of("payment_id", "psp_confirmation_id", "network_confirmation_id", "reference", "status")) {
            ObjectNode tree = toJson(success);
            tree.remove(removed);
            refuses(() -> paymentReceipt(tree));
        }
        for (String field : List.of("error", "error_description", "order_id")) {
            ObjectNode tree = toJson(success).put(field, "SYNTHETIC_CANARY");
            refuses(() -> paymentReceipt(tree));
        }
        var error = new PaymentReceipt("cp", 100, 200, "agent", "nonce", "receipt-p2", HASH,
                "Error", "error", "synthetic-attempt", null, null, "invalid_mandate", "The mandate was rejected.");
        for (String field : List.of("psp_confirmation_id", "network_confirmation_id")) {
            ObjectNode tree = toJson(error).put(field, "synthetic-confirmation");
            refuses(() -> paymentReceipt(tree));
        }
        ObjectNode noDescription = toJson(error);
        noDescription.remove("error_description");
        refuses(() -> paymentReceipt(noDescription));
        refuses(() -> new CheckoutReceipt("merchant", 100, 200, "agent", "nonce", "receipt", HASH,
                "Error", "error", "contradictory-order", "invalid_mandate", "The mandate was rejected."));
    }

    @Test
    void constructorsEnforceValuesAndRejectNoncanonicalHashesWithoutLeakingInputs() {
        for (String digest : List.of("sha256:" + HASH, HASH + "=", HASH.substring(1), "A".repeat(42) + "B")) {
            refuses(() -> new CheckoutMandate(CHECKOUT, digest));
            refuses(() -> new PaymentMandate(digest, payment().payee(), payment().paymentAmount(), payment().paymentInstrument()));
        }
        for (String compact : List.of("bare-json", "e30.e30.c2ln~", "e30=.e30.c2ln", "e30.e30.c2ln.extra")) {
            refuses(() -> new CheckoutMandate(compact, HASH));
        }
        refuses(() -> new Merchant("SYNTHETIC_CANARY\n", "Name", "https://merchant.example"));
        refuses(() -> new Merchant("x".repeat(2049), "Name", "https://merchant.example"));
        refuses(() -> new Merchant("merchant", "\uD800", "https://merchant.example"));
        refuses(() -> new Envelope("issuer", 200, 100, "audience", "nonce", "id", payment()));
        refuses(() -> new Envelope("issuer", -1, 100, "audience", "nonce", "id", payment()));
        refuses(() -> new Envelope("issuer", 100, 200, "audience", "nonce", "id", null));
    }

    @Test
    void recordStringsRedactPayloadsAndIdentifiers() {
        String canary = "SYNTHETIC_PRIVATE_CANARY";
        var merchant = new Merchant(canary, canary, canary);
        var instrument = new PaymentInstrument(canary, canary, canary);
        var payment = new PaymentMandate(HASH, merchant, new Amount(1, "USD"), instrument);
        var checkout = new CheckoutMandate(canary + "." + canary + "." + canary, HASH);
        var wrapped = new Envelope(canary, 100, 200, canary, canary, canary, checkout);
        var receipt = new CheckoutReceipt(canary, 100, 200, canary, canary, canary, HASH, "Success", "success", canary, null, null);
        for (Object value : List.of(merchant, instrument, payment, checkout, wrapped, receipt)) {
            assertFalse(value.toString().contains(canary));
            assertFalse(value.toString().contains(HASH));
            assertTrue(value.toString().contains("redacted"));
        }
    }
}
