package io.agenticpayments.ap2;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECParameterSpec;
import java.util.ArrayList;
import java.util.List;

import static io.agenticpayments.ap2.Ap2Claims.*;
import static io.agenticpayments.ap2.Ap2Engine.*;
import static io.agenticpayments.ap2.Ap2Exception.Code.*;
import static org.junit.jupiter.api.Assertions.*;

class Ap2ExchangeTest {
    private static final long NOW = 1000;
    private static final String TAP = "tap.synthetic.example";
    private static final String MERCHANT = "merchant.synthetic.example";
    private static final String CP = "cp.synthetic.example";
    private static final String MPP = "processor.synthetic.example";
    private static final String KID = "synthetic-key";
    private static final String CANARY = "SYNTHETIC_PRIVATE_CANARY";

    private static final class Fixture {
        final KeyPair tap = Ap2Crypto.generateKeyPair();
        final KeyPair merchant = Ap2Crypto.generateKeyPair();
        final KeyPair cp = Ap2Crypto.generateKeyPair();
        final KeyPair processor = Ap2Crypto.generateKeyPair();
        final String checkout = Ap2Crypto.sign(Ap2Crypto.jsonBytes(Ap2Crypto.object().put("id", "synthetic-checkout")),
                merchant.getPrivate(), KID, Ap2Crypto.CHECKOUT_TYPE);
        final String hash = Ap2Crypto.hash(checkout);
        final Merchant payee = new Merchant("synthetic-merchant", "Synthetic Merchant", "https://merchant.synthetic.example");
        final Amount amount = new Amount(1250, "EUR");
        final PaymentInstrument instrument = new PaymentInstrument("synthetic-instrument", "synthetic", null);
        final ExpectedPayment expected = new ExpectedPayment(checkout, MERCHANT, payee, amount, instrument);
        final Context merchantAuth = new Context(Role.MERCHANT, TAP, "merchant", "checkout-nonce", NOW);
        final Context cpAuth = new Context(Role.CREDENTIAL_PROVIDER, TAP, "payment", "payment-nonce", NOW);
        final Context processorAuth = new Context(Role.PAYMENT_PROCESSOR, TAP, "payment", "payment-nonce", NOW);
        final Context merchantReceipt = new Context(Role.MERCHANT, MERCHANT, "agent", "checkout-receipt", NOW);
        final Context cpReceipt = new Context(Role.CREDENTIAL_PROVIDER, CP, "agent", "cp-receipt", NOW);
        final Context processorReceipt = new Context(Role.PAYMENT_PROCESSOR, MPP, "agent", "payment-receipt", NOW);
        final Ap2Engine engine = new Ap2Engine(keys());

        List<TrustKey> keys() {
            return List.of(key(Purpose.CHECKOUT_MANDATE, TAP, tap), key(Purpose.PAYMENT_MANDATE, TAP, tap),
                    key(Purpose.PROCESSOR_PAYMENT_MANDATE, TAP, tap), key(Purpose.MERCHANT_CHECKOUT, MERCHANT, merchant),
                    key(Purpose.CHECKOUT_RECEIPT, MERCHANT, merchant), key(Purpose.CP_ERROR_RECEIPT, CP, cp),
                    key(Purpose.PAYMENT_RECEIPT, MPP, processor));
        }
        TrustKey key(Purpose purpose, String issuer, KeyPair pair) {
            return new TrustKey(purpose, issuer, KID, pair.getPublic(), NOW - 100, NOW + 500, false);
        }
        Ap2Exchange merchant() { return new Ap2Exchange(engine, Role.MERCHANT, MERCHANT, KID, merchant); }
        Ap2Exchange cp() { return new Ap2Exchange(engine, Role.CREDENTIAL_PROVIDER, CP, KID, cp); }
        Ap2Exchange processor() { return new Ap2Exchange(engine, Role.PAYMENT_PROCESSOR, MPP, KID, processor); }
        String mandate(Content content, Context context, KeyPair signer) {
            var envelope = new Envelope(TAP, NOW - 5, NOW + 200, context.audience(), context.nonce(), "synthetic-mandate", content);
            return Ap2Crypto.sign(Ap2Crypto.jsonBytes(toJson(envelope)), signer.getPrivate(), KID, Ap2Crypto.MANDATE_TYPE) + "~";
        }
        String checkoutMandate() { return mandate(new CheckoutMandate(checkout, hash), merchantAuth, tap); }
        String paymentMandate() { return mandate(new PaymentMandate(hash, payee, amount, instrument), cpAuth, tap); }
        void issue(Context context) { engine.issueChallenge(context, hash, NOW + 200); }
        Ap2Exchange.Outcome credential(String mandate) {
            return cp().credential(mandate, expected, cpAuth, cpReceipt, "synthetic-cp-receipt", "synthetic-attempt");
        }
        Ap2Exchange.Outcome process(String mandate, CredentialBinding credential) {
            return processor().process(mandate, expected, processorAuth, processorReceipt, "synthetic-payment-receipt",
                    "synthetic-payment", "synthetic-psp", "synthetic-network", credential);
        }
    }

    @Test
    void actualMerchantCredentialAndProcessorDecisionsProduceVerifiedSyntheticOutcomes() {
        Fixture f = new Fixture();
        f.issue(f.merchantAuth); f.issue(f.cpAuth); f.issue(f.processorAuth);
        String checkoutMandate = f.checkoutMandate();
        String paymentMandate = f.paymentMandate();
        var checkout = f.merchant().checkout(checkoutMandate, f.checkout, MERCHANT, f.merchantAuth,
                f.merchantReceipt, "synthetic-checkout-receipt", "synthetic-order");
        assertTrue(checkout.accepted());
        assertEquals("accepted", checkout.code());
        var checkoutReceipt = f.engine.verifyCheckoutReceipt(checkout.receipt(), checkoutMandate, f.merchantReceipt);
        assertEquals("Success", checkoutReceipt.status());
        assertEquals(NOW, checkoutReceipt.issuedAt());
        assertEquals(NOW + 240, checkoutReceipt.expiresAt());
        var credential = f.credential(paymentMandate);
        assertTrue(credential.accepted());
        assertNotNull(credential.credential());
        assertNull(credential.receipt());
        var processed = f.process(paymentMandate, credential.credential());
        assertTrue(processed.accepted());
        var paymentReceipt = f.engine.verifyPaymentReceipt(processed.receipt(), paymentMandate, f.processorReceipt);
        assertEquals("Success", paymentReceipt.status());
        assertEquals("synthetic-psp", paymentReceipt.pspConfirmationId());
        assertEquals("synthetic-network", paymentReceipt.networkConfirmationId());
        assertEquals(false, processed.safeSummary().get("financialAdmission"));
        assertEquals(true, processed.safeSummary().get("synthetic"));
        var replay = f.process(paymentMandate, credential.credential());
        assertFalse(replay.accepted());
        assertEquals("invalid_mandate", replay.code());
        assertEquals("Error", f.engine.verifyPaymentReceipt(replay.receipt(), paymentMandate, f.processorReceipt).status());
    }

    @Test
    void merchantAndCredentialRejectionsAreSignedFromActualFailedAuthorization() {
        Fixture f = new Fixture();
        f.issue(f.merchantAuth); f.issue(f.cpAuth);
        String checkoutMandate = f.checkoutMandate();
        var checkout = f.merchant().checkout(checkoutMandate, "different-current-checkout", MERCHANT, f.merchantAuth,
                f.merchantReceipt, "synthetic-checkout-receipt", "synthetic-order");
        assertFalse(checkout.accepted());
        var error = f.engine.verifyCheckoutReceipt(checkout.receipt(), checkoutMandate, f.merchantReceipt);
        assertEquals("Error", error.status());
        assertEquals("error", error.result());
        assertEquals("invalid_mandate", error.error());
        assertEquals("The mandate was rejected.", error.errorDescription());
        assertTrue(f.merchant().checkout(checkoutMandate, f.checkout, MERCHANT, f.merchantAuth,
                f.merchantReceipt, "synthetic-checkout-receipt", "synthetic-order").accepted());
        String wrongAmount = f.mandate(new PaymentMandate(f.hash, f.payee, new Amount(1251, "EUR"), f.instrument), f.cpAuth, f.tap);
        var cp = f.credential(wrongAmount);
        assertFalse(cp.accepted());
        assertNull(cp.credential());
        var cpError = f.engine.verifyPaymentReceipt(cp.receipt(), wrongAmount, f.cpReceipt);
        assertEquals("Error", cpError.status());
        assertEquals("synthetic-attempt", cpError.paymentId());
        assertNull(cpError.pspConfirmationId());
        assertNull(cpError.networkConfirmationId());
        assertTrue(f.credential(f.paymentMandate()).accepted());
    }

    @Test
    void processorRejectsAbsentBindingWithSignedErrorAndThenAcceptsRealCpBinding() {
        Fixture f = new Fixture();
        f.issue(f.cpAuth); f.issue(f.processorAuth);
        String paymentMandate = f.paymentMandate();
        var rejected = f.process(paymentMandate, null);
        assertFalse(rejected.accepted());
        assertEquals("Error", f.engine.verifyPaymentReceipt(rejected.receipt(), paymentMandate, f.processorReceipt).status());
        var credential = f.credential(paymentMandate);
        assertTrue(f.process(paymentMandate, credential.credential()).accepted());
    }

    @Test
    void correctlyFramedInvalidSignatureGetsExactReferenceWhileMalformedFramesGetNoReceipt() {
        Fixture f = new Fixture();
        f.issue(f.cpAuth);
        String invalidSignature = f.mandate(new PaymentMandate(f.hash, f.payee, f.amount, f.instrument), f.cpAuth, f.processor);
        var error = f.credential(invalidSignature);
        assertFalse(error.accepted());
        assertEquals("invalid_credential", error.code());
        var receipt = f.engine.verifyPaymentReceipt(error.receipt(), invalidSignature, f.cpReceipt);
        assertEquals(Ap2Crypto.hash(invalidSignature), receipt.reference());
        for (String malformed : List.of(CANARY, "e30.e30.YQ~", "e30=.e30.YQ~", "a".repeat(131073),
                "a.b.c~disclosure~", "a.b.c", "a.b.c.extra~")) {
            var result = f.credential(malformed);
            assertFalse(result.accepted());
            assertNull(result.receipt());
            assertNull(result.credential());
            assertFalse(result.toString().contains(CANARY));
        }
        assertTrue(f.credential(f.paymentMandate()).accepted());
    }

    @Test
    void outputIdsReceiptContextAndSyntheticConfirmationsAreValidatedBeforeConsumption() {
        Fixture f = new Fixture();
        f.issue(f.merchantAuth); f.issue(f.cpAuth); f.issue(f.processorAuth);
        String checkoutMandate = f.checkoutMandate();
        var merchant = f.merchant();
        rejects(INVALID_CONTEXT, () -> merchant.checkout(checkoutMandate, f.checkout, MERCHANT, f.merchantAuth,
                f.merchantReceipt, "synthetic-receipt", CANARY + "\n"));
        rejects(INVALID_CONTEXT, () -> merchant.checkout(checkoutMandate, f.checkout, MERCHANT, f.merchantAuth,
                new Context(Role.MERCHANT, "wrong-issuer", "agent", "receipt", NOW), "synthetic-receipt", "synthetic-order"));
        rejects(INVALID_CONTEXT, () -> merchant.checkout(checkoutMandate, f.checkout, MERCHANT, f.merchantAuth,
                new Context(Role.MERCHANT, MERCHANT, "agent", "receipt", NOW + 1), "synthetic-receipt", "synthetic-order"));
        assertTrue(merchant.checkout(checkoutMandate, f.checkout, MERCHANT, f.merchantAuth,
                f.merchantReceipt, "synthetic-receipt", "synthetic-order").accepted());
        String mandate = f.paymentMandate();
        rejects(INVALID_CONTEXT, () -> f.cp().credential(mandate, f.expected, f.cpAuth, f.cpReceipt, "", "synthetic-attempt"));
        var credential = f.credential(mandate);
        rejects(INVALID_CONTEXT, () -> f.processor().process(mandate, f.expected, f.processorAuth, f.processorReceipt,
                "synthetic-receipt", "synthetic-payment", "unlabelled-psp", "synthetic-network", credential.credential()));
        rejects(INVALID_CONTEXT, () -> f.processor().process(mandate, f.expected, f.processorAuth, f.processorReceipt,
                "synthetic-receipt", "synthetic-payment", "synthetic-psp", "", credential.credential()));
        assertTrue(f.process(mandate, credential.credential()).accepted());
    }

    @Test
    void mismatchedSigningPairUnregisteredWrongRoleAndRevokedReceiptKeysCannotConsumeAuthority() {
        Fixture f = new Fixture();
        rejects(INVALID_SIGNATURE, () -> new Ap2Exchange(f.engine, Role.MERCHANT, MERCHANT, KID,
                new KeyPair(f.merchant.getPublic(), f.processor.getPrivate())));
        f.issue(f.merchantAuth);
        var wrongSigner = new Ap2Exchange(f.engine, Role.MERCHANT, MERCHANT, KID, f.processor);
        rejects(UNTRUSTED_KEY, () -> wrongSigner.checkout(f.checkoutMandate(), f.checkout, MERCHANT,
                f.merchantAuth, f.merchantReceipt, "synthetic-receipt", "synthetic-order"));
        var keys = new ArrayList<>(f.keys());
        keys.removeIf(value -> value.purpose() == Purpose.CHECKOUT_RECEIPT);
        keys.add(new TrustKey(Purpose.CHECKOUT_RECEIPT, MERCHANT, KID, f.merchant.getPublic(), NOW - 100, NOW + 500, true));
        var revoked = new Ap2Exchange(new Ap2Engine(keys), Role.MERCHANT, MERCHANT, KID, f.merchant);
        rejects(UNTRUSTED_KEY, () -> revoked.checkout(f.checkoutMandate(), f.checkout, MERCHANT,
                f.merchantAuth, f.merchantReceipt, "synthetic-receipt", "synthetic-order"));
        var roleOnly = new Ap2Exchange(new Ap2Engine(List.of(f.key(Purpose.MERCHANT_CHECKOUT, MERCHANT, f.merchant))),
                Role.MERCHANT, MERCHANT, KID, f.merchant);
        rejects(UNTRUSTED_KEY, () -> roleOnly.checkout(f.checkoutMandate(), f.checkout, MERCHANT,
                f.merchantAuth, f.merchantReceipt, "synthetic-receipt", "synthetic-order"));
        assertTrue(f.merchant().checkout(f.checkoutMandate(), f.checkout, MERCHANT, f.merchantAuth,
                f.merchantReceipt, "synthetic-receipt", "synthetic-order").accepted());
    }

    @Test
    void signingFailureAfterAuthorizationKeepsChallengeSpentAndReportsReceiptUnavailable() {
        Fixture f = new Fixture();
        SwitchablePrivateKey privateKey = new SwitchablePrivateKey((ECPrivateKey) f.merchant.getPrivate());
        var merchant = new Ap2Exchange(f.engine, Role.MERCHANT, MERCHANT, KID,
                new KeyPair(f.merchant.getPublic(), privateKey));
        f.issue(f.merchantAuth);
        String mandate = f.checkoutMandate();
        privateKey.fail = true;
        var result = merchant.checkout(mandate, f.checkout, MERCHANT, f.merchantAuth,
                f.merchantReceipt, "synthetic-receipt", "synthetic-order");
        assertTrue(result.accepted());
        assertEquals("receipt_unavailable", result.code());
        assertNull(result.receipt());
        assertEquals(false, result.safeSummary().get("financialAdmission"));
        rejects(REPLAY_REJECTED, () -> f.engine.authorizeCheckout(mandate, f.checkout, MERCHANT, f.merchantAuth));
        assertFalse(result.toString().contains(CANARY));
        assertFalse(result.safeSummary().toString().contains(CANARY));
    }

    private static final class SwitchablePrivateKey implements ECPrivateKey {
        private final ECPrivateKey delegate;
        volatile boolean fail;
        SwitchablePrivateKey(ECPrivateKey delegate) { this.delegate = delegate; }
        @Override public BigInteger getS() {
            if (fail) throw new IllegalStateException(CANARY);
            return delegate.getS();
        }
        @Override public ECParameterSpec getParams() { return delegate.getParams(); }
        @Override public String getAlgorithm() { return "EC"; }
        @Override public String getFormat() { return delegate.getFormat(); }
        @Override public byte[] getEncoded() {
            if (fail) throw new IllegalStateException(CANARY);
            return delegate.getEncoded();
        }
    }

    @Test
    void safeSummariesWithholdArtifactsAndSigningMaterial() {
        Fixture f = new Fixture();
        f.issue(f.cpAuth);
        var exchange = f.cp();
        var result = f.credential(f.paymentMandate());
        assertFalse(exchange.toString().contains(f.checkout));
        assertFalse(result.toString().contains(f.checkout));
        assertFalse(result.safeSummary().containsKey("receipt"));
        assertFalse(result.safeSummary().containsKey("credential"));
        assertThrows(UnsupportedOperationException.class, () -> result.safeSummary().put("receipt", CANARY));
        assertEquals(0, Ap2Exchange.Outcome.class.getConstructors().length);
        assertEquals(0, java.util.Arrays.stream(Ap2Exchange.class.getMethods())
                .filter(method -> method.getReturnType() == KeyPair.class || method.getReturnType() == java.security.PrivateKey.class).count());
    }

    private static void rejects(Ap2Exception.Code code, Runnable operation) {
        Ap2Exception failure = assertThrows(Ap2Exception.class, operation::run);
        assertEquals(code, failure.code());
        assertFalse(failure.toString().contains(CANARY));
        assertNull(failure.getCause());
    }
}
