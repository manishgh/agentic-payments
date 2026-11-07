package io.agenticpayments.ap2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.agenticpayments.ap2.Ap2Engine.*;
import static io.agenticpayments.ap2.Ap2Exception.Code.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent ephemeral JCA wire fixtures exercise public protocol behavior. */
class Ap2SecurityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long NOW = 1_000;
    private static final String TAP = "https://tap.example";
    private static final String MERCHANT = "https://merchant.example";
    private static final String CP = "https://cp.example";
    private static final String MPP = "https://mpp.example";
    private static final String KID = "synthetic-key";
    private static final String CANARY = "SYNTHETIC_PRIVATE_CANARY";

    private static final class Fixture {
        final KeyPair tap = key("secp256r1");
        final KeyPair merchant = key("secp256r1");
        final KeyPair cp = key("secp256r1");
        final KeyPair mpp = key("secp256r1");
        final String checkout = sign(object().put("id", "checkout-1").put("amount", 15000).put("currency", "EUR"), merchant.getPrivate(), Ap2Crypto.CHECKOUT_TYPE);
        final String checkoutHash = hash(checkout);
        final Ap2Claims.Merchant payee = new Ap2Claims.Merchant("merchant-1", "Synthetic Merchant", MERCHANT);
        final Ap2Claims.Amount amount = new Ap2Claims.Amount(15000, "EUR");
        final Ap2Claims.PaymentInstrument instrument = new Ap2Claims.PaymentInstrument("synthetic-1", "synthetic", null);

        List<TrustKey> trust() {
            return List.of(trusted(Purpose.CHECKOUT_MANDATE, TAP, tap), trusted(Purpose.PAYMENT_MANDATE, TAP, tap),
                    trusted(Purpose.PROCESSOR_PAYMENT_MANDATE, TAP, tap),
                    trusted(Purpose.MERCHANT_CHECKOUT, MERCHANT, merchant), trusted(Purpose.CHECKOUT_RECEIPT, MERCHANT, merchant),
                    trusted(Purpose.CP_ERROR_RECEIPT, CP, cp), trusted(Purpose.PAYMENT_RECEIPT, MPP, mpp));
        }
        Ap2Engine engine() { return new Ap2Engine(trust()); }
        ExpectedPayment expected() { return new ExpectedPayment(checkout, MERCHANT, payee, amount, instrument); }
        Context context(Role role) { return new Context(role, TAP, "payment-verifiers", "challenge-1", NOW); }
        ObjectNode envelope(String audience, String nonce, String id, ObjectNode content) {
            ObjectNode node = object().put("iss", TAP).put("iat", NOW - 10).put("exp", NOW + 100)
                    .put("aud", audience).put("nonce", nonce).put("jti", id)
                    .put("vct", Ap2Claims.CREDENTIAL_TYPE).put("_sd_alg", "sha-256");
            node.putArray("delegate_payload").add(content);
            return node;
        }
        ObjectNode paymentContent() {
            ObjectNode node = object().put("vct", "mandate.payment.1").put("transaction_id", checkoutHash);
            node.set("payee", object().put("id", payee.id()).put("name", payee.name()).put("website", payee.website()));
            node.set("payment_amount", object().put("amount", amount.amount()).put("currency", amount.currency()));
            node.set("payment_instrument", object().put("id", instrument.id()).put("type", instrument.type()));
            return node;
        }
        String mandate() { return mandate("challenge-1", "payment-1", paymentContent()); }
        String mandate(String nonce, String id, ObjectNode content) {
            return sign(envelope("payment-verifiers", nonce, id, content), tap.getPrivate(), Ap2Crypto.MANDATE_TYPE) + "~";
        }
        String checkoutMandate() {
            ObjectNode content = object().put("vct", "mandate.checkout.1").put("checkout_jwt", checkout).put("checkout_hash", checkoutHash);
            return sign(envelope("merchant-verifier", "checkout-challenge", "checkout-mandate", content), tap.getPrivate(), Ap2Crypto.MANDATE_TYPE) + "~";
        }
    }

    private static KeyPair key(String curve) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec(curve));
            return generator.generateKeyPair();
        } catch (Exception failure) { throw new AssertionError("Synthetic key generation failed", failure); }
    }
    private static TrustKey trusted(Purpose purpose, String issuer, KeyPair key) {
        return new TrustKey(purpose, issuer, KID, key.getPublic(), NOW - 500, NOW + 500, false);
    }
    private static ObjectNode object() { return JSON.createObjectNode(); }
    private static String b64(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String hash(String value) {
        try { return b64(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII))); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static byte[] bytes(ObjectNode value) {
        try { return JSON.writeValueAsBytes(value); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static String sign(ObjectNode payload, PrivateKey key, String typ) {
        return sign(bytes(object().put("alg", "ES256").put("kid", KID).put("typ", typ)), bytes(payload), key);
    }
    private static String sign(byte[] header, byte[] payload, PrivateKey key) {
        try {
            String input = b64(header) + "." + b64(payload);
            Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
            signature.initSign(key);
            signature.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + b64(signature.sign());
        } catch (Exception failure) { throw new AssertionError("Synthetic signing failed", failure); }
    }
    private static void rejects(Ap2Exception.Code code, Runnable operation) {
        Ap2Exception failure = assertThrows(Ap2Exception.class, operation::run);
        assertEquals(code, failure.code());
        assertFalse(failure.toString().contains(CANARY));
        assertNull(failure.getCause());
    }
    private static void rejects(Runnable operation) {
        var failure = assertThrows(Ap2Exception.class, operation::run);
        assertFalse(failure.toString().contains(CANARY));
        assertNull(failure.getCause());
    }

    @Test
    void randomizedCheckoutSignatureChangesExactHashAndCurrentRevisionCannotBeSubstituted() {
        Fixture fixture = new Fixture();
        byte[] payload = bytes(object().put("id", "checkout-1"));
        String first = Ap2Crypto.sign(payload, fixture.merchant.getPrivate(), KID, Ap2Crypto.CHECKOUT_TYPE);
        String second = Ap2Crypto.sign(payload, fixture.merchant.getPrivate(), KID, Ap2Crypto.CHECKOUT_TYPE);
        assertFalse(first.equals(second), "Randomized checkout signatures must differ");
        assertNotEquals(hash(first), hash(second));
        assertTrue(Ap2Crypto.verify(first, fixture.merchant.getPublic(), KID, Ap2Crypto.CHECKOUT_TYPE).equals(
                Ap2Crypto.verify(second, fixture.merchant.getPublic(), KID, Ap2Crypto.CHECKOUT_TYPE)),
                "The independently signed checkouts must decode to the same content");
        Context context = new Context(Role.MERCHANT, TAP, "merchant-verifier", "checkout-challenge", NOW);
        fixture.engine().verifyCheckout(fixture.checkoutMandate(), fixture.checkout, MERCHANT, context);
        rejects(BINDING_MISMATCH, () -> fixture.engine().verifyCheckout(fixture.checkoutMandate(), first, MERCHANT, context));
        assertNotEquals(hash(fixture.checkout), hash('"' + fixture.checkout + '"'));
        assertNotEquals(hash(fixture.checkout), hash(b64(fixture.checkout.getBytes(StandardCharsets.US_ASCII))));
    }

    @Test
    void authenticatedDuplicateJsonMalformedUtf8AndUnknownHeadersFailClosed() {
        Fixture fixture = new Fixture();
        byte[] header = bytes(object().put("alg", "ES256").put("kid", KID).put("typ", Ap2Crypto.CHECKOUT_TYPE));
        String duplicate = sign(header, ("{\"id\":\"one\",\"id\":\"" + CANARY + "\"}").getBytes(StandardCharsets.UTF_8), fixture.merchant.getPrivate());
        rejects(INVALID_JSON, () -> Ap2Crypto.verify(duplicate, fixture.merchant.getPublic(), KID, Ap2Crypto.CHECKOUT_TYPE));
        String invalidUtf8 = sign(header, new byte[]{'{', '"', 'x', '"', ':', '"', (byte) 0xc3, 0x28, '"', '}'}, fixture.merchant.getPrivate());
        rejects(INVALID_JSON, () -> Ap2Crypto.verify(invalidUtf8, fixture.merchant.getPublic(), KID, Ap2Crypto.CHECKOUT_TYPE));
        for (String extension : List.of("crit", "jwk", "jku", "x5u", "b64")) {
            ObjectNode badHeader = object().put("alg", "ES256").put("kid", KID).put("typ", Ap2Crypto.CHECKOUT_TYPE).put(extension, CANARY);
            String token = sign(bytes(badHeader), bytes(object()), fixture.merchant.getPrivate());
            rejects(INVALID_HEADER, () -> Ap2Crypto.verify(token, fixture.merchant.getPublic(), KID, Ap2Crypto.CHECKOUT_TYPE));
        }
        String duplicateHeader = sign(("{\"alg\":\"ES256\",\"kid\":\"" + KID + "\",\"typ\":\"ap2-checkout+jwt\",\"kid\":\"" + CANARY + "\"}").getBytes(StandardCharsets.UTF_8), bytes(object()), fixture.merchant.getPrivate());
        rejects(INVALID_JSON, () -> Ap2Crypto.verify(duplicateHeader, fixture.merchant.getPublic(), KID, Ap2Crypto.CHECKOUT_TYPE));
    }

    @Test
    void canonicalEncodingBoundsDepthAndCurveRestrictionsAreEnforced() {
        rejects(INVALID_ENCODING, () -> Ap2Crypto.decode("AB"));
        rejects(INVALID_ENCODING, () -> Ap2Crypto.decode("e30="));
        rejects(INVALID_JSON, () -> Ap2Crypto.parseJson(("{\"x\":\"" + "a".repeat(65536) + "\"}").getBytes(StandardCharsets.UTF_8)));
        rejects(INVALID_JSON, () -> Ap2Crypto.parseJson(("{\"x\":".repeat(25) + "0" + "}".repeat(25)).getBytes(StandardCharsets.UTF_8)));
        rejects(INVALID_JSON, () -> Ap2Crypto.parseJson("{} {}".getBytes(StandardCharsets.UTF_8)));
        rejects(INVALID_JSON, () -> Ap2Crypto.parseJson("[]".getBytes(StandardCharsets.UTF_8)));
        KeyPair wrongCurve = key("secp384r1");
        rejects(INVALID_KEY, () -> Ap2Crypto.requireP256(wrongCurve.getPublic()));
        rejects(INVALID_KEY, () -> Ap2Crypto.sign(bytes(object()), wrongCurve.getPrivate(), KID, Ap2Crypto.CHECKOUT_TYPE));
        Fixture fixture = new Fixture();
        String[] parts = fixture.checkout.split("\\.");
        rejects(INVALID_ENCODING, () -> Ap2Crypto.verify(parts[0] + "=." + parts[1] + "." + parts[2], fixture.merchant.getPublic(), KID, Ap2Crypto.CHECKOUT_TYPE));
        rejects(INVALID_HEADER, () -> Ap2Crypto.verify(fixture.checkout, fixture.merchant.getPublic(), KID, Ap2Crypto.PAYMENT_RECEIPT_TYPE));
        rejects(() -> Ap2Crypto.verify("a".repeat(131073), fixture.merchant.getPublic(), KID, Ap2Crypto.CHECKOUT_TYPE));
    }

    @Test
    void contextAndPurposeSpecificTrustRejectIssuerAudienceNonceRevocationAndKeyTime() {
        Fixture fixture = new Fixture();
        String mandate = fixture.mandate();
        fixture.engine().verifyPayment(mandate, fixture.expected(), fixture.context(Role.CREDENTIAL_PROVIDER));
        rejects(UNTRUSTED_KEY, () -> fixture.engine().verifyPayment(mandate, fixture.expected(), new Context(Role.CREDENTIAL_PROVIDER, "wrong-issuer", "payment-verifiers", "challenge-1", NOW)));
        rejects(INVALID_CONTEXT, () -> fixture.engine().verifyPayment(mandate, fixture.expected(), new Context(Role.CREDENTIAL_PROVIDER, TAP, "wrong-audience", "challenge-1", NOW)));
        rejects(INVALID_CONTEXT, () -> fixture.engine().verifyPayment(mandate, fixture.expected(), new Context(Role.CREDENTIAL_PROVIDER, TAP, "payment-verifiers", "wrong-nonce", NOW)));
        for (TrustKey replacement : List.of(
                new TrustKey(Purpose.PAYMENT_MANDATE, TAP, KID, fixture.tap.getPublic(), NOW - 500, NOW + 500, true),
                new TrustKey(Purpose.PAYMENT_MANDATE, TAP, KID, fixture.tap.getPublic(), NOW + 1, NOW + 500, false),
                new TrustKey(Purpose.PAYMENT_MANDATE, TAP, KID, fixture.tap.getPublic(), NOW - 500, NOW, false))) {
            ArrayList<TrustKey> keys = new ArrayList<>(fixture.trust());
            keys.removeIf(value -> value.purpose() == Purpose.PAYMENT_MANDATE);
            keys.add(replacement);
            Ap2Engine engine = new Ap2Engine(keys);
            rejects(UNTRUSTED_KEY, () -> engine.verifyPayment(mandate, fixture.expected(), fixture.context(Role.CREDENTIAL_PROVIDER)));
        }
        Ap2Engine merchantOnly = new Ap2Engine(List.of(trusted(Purpose.MERCHANT_CHECKOUT, TAP, fixture.tap)));
        rejects(UNTRUSTED_KEY, () -> merchantOnly.verifyPayment(mandate, fixture.expected(), fixture.context(Role.CREDENTIAL_PROVIDER)));
        Ap2Engine credentialProviderOnly = new Ap2Engine(List.of(trusted(Purpose.PAYMENT_MANDATE, TAP, fixture.tap),
                trusted(Purpose.MERCHANT_CHECKOUT, MERCHANT, fixture.merchant)));
        credentialProviderOnly.verifyPayment(mandate, fixture.expected(), fixture.context(Role.CREDENTIAL_PROVIDER));
        rejects(UNTRUSTED_KEY, () -> credentialProviderOnly.verifyPayment(mandate, fixture.expected(), fixture.context(Role.PAYMENT_PROCESSOR)));
        String wrongSigner = sign(fixture.envelope("payment-verifiers", "challenge-1", "id", fixture.paymentContent()), fixture.merchant.getPrivate(), Ap2Crypto.MANDATE_TYPE) + "~";
        rejects(INVALID_SIGNATURE, () -> fixture.engine().verifyPayment(wrongSigner, fixture.expected(), fixture.context(Role.CREDENTIAL_PROVIDER)));
    }

    @Test
    void authorityTimesHaveExactExpiryFutureAndMaximumLifetimeBoundaries() {
        Fixture fixture = new Fixture();
        for (long[] bounds : List.of(new long[]{NOW - 100, NOW}, new long[]{NOW + 1, NOW + 100}, new long[]{NOW - 10, NOW + 291})) {
            ObjectNode envelope = fixture.envelope("payment-verifiers", "challenge-1", "id", fixture.paymentContent());
            envelope.put("iat", bounds[0]).put("exp", bounds[1]);
            String token = sign(envelope, fixture.tap.getPrivate(), Ap2Crypto.MANDATE_TYPE) + "~";
            rejects(EXPIRED_AUTHORITY, () -> fixture.engine().verifyPayment(token, fixture.expected(), fixture.context(Role.CREDENTIAL_PROVIDER)));
        }
        ObjectNode envelope = fixture.envelope("payment-verifiers", "challenge-1", "id", fixture.paymentContent()).put("iat", NOW).put("exp", NOW + 300);
        fixture.engine().verifyPayment(sign(envelope, fixture.tap.getPrivate(), Ap2Crypto.MANDATE_TYPE) + "~", fixture.expected(), fixture.context(Role.CREDENTIAL_PROVIDER));
    }

    @Test
    void challengeMustBeRegisteredBoundUnexpiredAndCannotBeRebound() {
        Fixture fixture = new Fixture();
        Context context = fixture.context(Role.CREDENTIAL_PROVIDER);
        String mandate = fixture.mandate();
        rejects(REPLAY_REJECTED, () -> fixture.engine().authorizeCredential(mandate, fixture.expected(), context));
        Ap2Engine wrongTransaction = fixture.engine();
        wrongTransaction.issueChallenge(context, hash("different-checkout"), NOW + 50);
        rejects(REPLAY_REJECTED, () -> wrongTransaction.authorizeCredential(mandate, fixture.expected(), context));
        Ap2Engine wrongIssuer = fixture.engine();
        wrongIssuer.issueChallenge(new Context(context.role(), "other-tap", context.audience(), context.nonce(), NOW), fixture.checkoutHash, NOW + 50);
        rejects(REPLAY_REJECTED, () -> wrongIssuer.authorizeCredential(mandate, fixture.expected(), context));
        Ap2Engine expired = fixture.engine();
        expired.issueChallenge(context, fixture.checkoutHash, NOW + 1);
        Context later = new Context(context.role(), context.issuer(), context.audience(), context.nonce(), NOW + 1);
        rejects(REPLAY_REJECTED, () -> expired.authorizeCredential(mandate, fixture.expected(), later));
        Ap2Engine registered = fixture.engine();
        registered.issueChallenge(context, fixture.checkoutHash, NOW + 50);
        rejects(REPLAY_REJECTED, () -> registered.issueChallenge(context, hash("different"), NOW + 50));
        rejects(REPLAY_REJECTED, () -> registered.issueChallenge(new Context(context.role(), context.issuer(), "different-audience", context.nonce(), NOW), fixture.checkoutHash, NOW + 50));
        rejects(REPLAY_REJECTED, () -> registered.issueChallenge(new Context(context.role(), "different-issuer", context.audience(), context.nonce(), NOW), fixture.checkoutHash, NOW + 50));
        rejects(REPLAY_REJECTED, () -> registered.issueChallenge(context, fixture.checkoutHash, NOW + 49));
        registered.authorizeCredential(mandate, fixture.expected(), context);
        rejects(REPLAY_REJECTED, () -> registered.authorizeCredential(mandate, fixture.expected(), context));
    }

    @Test
    void rejectedFactsAndSignaturesDoNotConsumeTheIssuedChallenge() {
        Fixture fixture = new Fixture();
        Ap2Engine engine = fixture.engine();
        Context context = fixture.context(Role.CREDENTIAL_PROVIDER);
        engine.issueChallenge(context, fixture.checkoutHash, NOW + 50);
        ObjectNode changed = fixture.paymentContent();
        ((ObjectNode) changed.get("payment_amount")).put("amount", 15001);
        String wrongFacts = fixture.mandate("challenge-1", "payment-1", changed);
        rejects(BINDING_MISMATCH, () -> engine.authorizeCredential(wrongFacts, fixture.expected(), context));
        String wrongSignature = sign(fixture.envelope(context.audience(), context.nonce(), "payment-1", fixture.paymentContent()), fixture.mpp.getPrivate(), Ap2Crypto.MANDATE_TYPE) + "~";
        rejects(INVALID_SIGNATURE, () -> engine.authorizeCredential(wrongSignature, fixture.expected(), context));
        assertNotNull(engine.authorizeCredential(fixture.mandate(), fixture.expected(), context));
    }

    @Test
    void issuerJtiIdentityRejectsResigningAndNewNonceAliasesIncludingAlternateEcdsaSignature() {
        Fixture fixture = new Fixture();
        Ap2Engine engine = fixture.engine();
        Context context = fixture.context(Role.CREDENTIAL_PROVIDER);
        engine.issueChallenge(context, fixture.checkoutHash, NOW + 50);
        String original = fixture.mandate();
        String alternate = alternateSignature(original, fixture.tap);
        assertFalse(original.equals(alternate), "The alternate ES256 representation must differ");
        Ap2Crypto.verify(Ap2Crypto.issuerJwt(alternate), fixture.tap.getPublic(), KID, Ap2Crypto.MANDATE_TYPE);
        engine.authorizeCredential(original, fixture.expected(), context);
        rejects(REPLAY_REJECTED, () -> engine.authorizeCredential(alternate, fixture.expected(), context));
        Context alias = new Context(context.role(), TAP, context.audience(), "challenge-2", NOW);
        engine.issueChallenge(alias, fixture.checkoutHash, NOW + 50);
        String resigned = fixture.mandate(alias.nonce(), "payment-1", fixture.paymentContent());
        rejects(REPLAY_REJECTED, () -> engine.authorizeCredential(resigned, fixture.expected(), alias));
    }

    /** ECDSA (r, n-s) is a distinct valid representation, not a new authorization identity. */
    private static String alternateSignature(String mandate, KeyPair key) {
        String[] segments = mandate.substring(0, mandate.length() - 1).split("\\.");
        byte[] raw = Base64.getUrlDecoder().decode(segments[2]);
        BigInteger order = ((ECKey) key.getPublic()).getParams().getOrder();
        byte[] alternate = order.subtract(new BigInteger(1, Arrays.copyOfRange(raw, 32, 64))).toByteArray();
        Arrays.fill(raw, 32, 64, (byte) 0);
        int count = Math.min(32, alternate.length);
        System.arraycopy(alternate, alternate.length - count, raw, 64 - count, count);
        return segments[0] + "." + segments[1] + "." + b64(raw) + "~";
    }

    @Test
    void concurrentPresentationAllowsExactlyOneAuthorization() throws Exception {
        Fixture fixture = new Fixture();
        Ap2Engine engine = fixture.engine();
        Context context = fixture.context(Role.CREDENTIAL_PROVIDER);
        engine.issueChallenge(context, fixture.checkoutHash, NOW + 50);
        String mandate = fixture.mandate();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var action = (java.util.concurrent.Callable<Boolean>) () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Concurrent fixture did not start");
                try { engine.authorizeCredential(mandate, fixture.expected(), context); return true; }
                catch (Ap2Exception failure) { assertEquals(REPLAY_REJECTED, failure.code()); return false; }
            };
            var first = executor.submit(action);
            var second = executor.submit(action);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        } finally { start.countDown(); }
    }

    @Test
    void processorRequiresSameEngineCredentialScopeAndSameApprovedFactsAndChallenge() {
        Fixture fixture = new Fixture();
        Ap2Engine engine = fixture.engine();
        Context cp = fixture.context(Role.CREDENTIAL_PROVIDER);
        Context mpp = fixture.context(Role.PAYMENT_PROCESSOR);
        engine.issueChallenge(cp, fixture.checkoutHash, NOW + 50);
        engine.issueChallenge(mpp, fixture.checkoutHash, NOW + 50);
        String mandate = fixture.mandate();
        CredentialBinding binding = engine.authorizeCredential(mandate, fixture.expected(), cp);
        Ap2Engine unrelated = fixture.engine();
        unrelated.issueChallenge(mpp, fixture.checkoutHash, NOW + 50);
        rejects(BINDING_MISMATCH, () -> unrelated.authorizeProcessor(mandate, fixture.expected(), mpp, binding));
        ObjectNode changed = fixture.paymentContent();
        ((ObjectNode) changed.get("payee")).put("id", "other-payee");
        ExpectedPayment different = new ExpectedPayment(fixture.checkout, MERCHANT,
                new Ap2Claims.Merchant("other-payee", fixture.payee.name(), fixture.payee.website()), fixture.amount, fixture.instrument);
        String changedMandate = fixture.mandate("challenge-1", "other-jti", changed);
        rejects(BINDING_MISMATCH, () -> engine.authorizeProcessor(changedMandate, different, mpp, binding));
        Context changedNonce = new Context(Role.PAYMENT_PROCESSOR, TAP, mpp.audience(), "other-challenge", NOW);
        engine.issueChallenge(changedNonce, fixture.checkoutHash, NOW + 50);
        String changedNonceMandate = fixture.mandate(changedNonce.nonce(), "other-jti", fixture.paymentContent());
        rejects(BINDING_MISMATCH, () -> engine.authorizeProcessor(changedNonceMandate, fixture.expected(), changedNonce, binding));
        assertNotNull(engine.authorizeProcessor(mandate, fixture.expected(), mpp, binding));
    }

    @Test
    void cpReceiptCanOnlyAcknowledgeRejectionAndSignerRolesCannotBeExchanged() {
        Fixture fixture = new Fixture();
        String mandate = fixture.mandate();
        ObjectNode error = object().put("iss", CP).put("iat", NOW - 1).put("exp", NOW + 100)
                .put("aud", "agent").put("nonce", "receipt-challenge").put("jti", "cp-receipt")
                .put("reference", hash(mandate)).put("status", "Error").put("result", "error")
                .put("payment_id", "synthetic-attempt").put("error", "invalid_mandate")
                .put("error_description", "The mandate was rejected.");
        Context cp = new Context(Role.CREDENTIAL_PROVIDER, CP, "agent", "receipt-challenge", NOW);
        String rejection = sign(error, fixture.cp.getPrivate(), Ap2Crypto.PAYMENT_RECEIPT_TYPE);
        assertEquals("Error", fixture.engine().verifyPaymentReceipt(rejection, mandate, cp).status());
        ObjectNode success = error.deepCopy().put("status", "Success").put("result", "success")
                .put("psp_confirmation_id", "synthetic-psp").put("network_confirmation_id", "synthetic-network");
        success.remove(List.of("error", "error_description"));
        String forbidden = sign(success, fixture.cp.getPrivate(), Ap2Crypto.PAYMENT_RECEIPT_TYPE);
        rejects(INVALID_RECEIPT, () -> fixture.engine().verifyPaymentReceipt(forbidden, mandate, cp));
        rejects(UNTRUSTED_KEY, () -> fixture.engine().verifyPaymentReceipt(rejection, mandate,
                new Context(Role.PAYMENT_PROCESSOR, CP, "agent", "receipt-challenge", NOW)));
        rejects(INVALID_RECEIPT, () -> fixture.engine().verifyPaymentReceipt(rejection, fixture.mandate(), cp));
        assertNotEquals(hash(mandate), hash(mandate.substring(0, mandate.length() - 1)));
    }

    @Test
    void protocolObjectsAndFailuresDoNotReflectArbitraryCanaries() {
        Fixture fixture = new Fixture();
        Context context = new Context(Role.CREDENTIAL_PROVIDER, TAP, CANARY, CANARY, NOW);
        ExpectedPayment expected = new ExpectedPayment(fixture.checkout, CANARY,
                new Ap2Claims.Merchant(CANARY, CANARY, CANARY), fixture.amount, fixture.instrument);
        TrustKey trusted = new TrustKey(Purpose.PAYMENT_MANDATE, CANARY, CANARY, fixture.tap.getPublic(), NOW - 10, NOW + 100, false);
        Proof proof = new Proof(CANARY, CANARY, CANARY, CANARY, NOW + 100);
        for (Object object : List.of(context, expected, trusted, proof)) assertFalse(object.toString().contains(CANARY));
        rejects(() -> fixture.engine().verifyPayment(CANARY, expected, context));
        rejects(() -> Ap2Crypto.parseJson(("{\"" + CANARY + "\":").getBytes(StandardCharsets.UTF_8)));
    }
}
