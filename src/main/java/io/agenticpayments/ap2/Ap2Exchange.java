package io.agenticpayments.ap2;

import java.security.KeyPair;
import java.util.Map;
import java.util.function.Supplier;

import static io.agenticpayments.ap2.Ap2Claims.*;
import static io.agenticpayments.ap2.Ap2Engine.*;
import static io.agenticpayments.ap2.Ap2Exception.Code.*;

/** Synthetic role exchange: signed outcomes follow actual deterministic authorization decisions. */
public final class Ap2Exchange {
    private static final String PLACEHOLDER_REFERENCE = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String REJECTION_TEXT = "The mandate was rejected.";
    private final Ap2Engine engine;
    private final Role role;
    private final String issuer;
    private final String kid;
    private final KeyPair signer;

    /** Private signing material is retained internally and never exposed by an accessor. */
    public Ap2Exchange(Ap2Engine engine, Role role, String issuer, String kid, KeyPair signer) {
        if (engine == null || role == null || signer == null || signer.getPrivate() == null || signer.getPublic() == null)
            throw new Ap2Exception(INVALID_KEY);
        new Context(role, issuer, "synthetic-signer-check", "synthetic-signer-check", 0);
        this.engine = engine;
        this.role = role;
        this.issuer = issuer;
        this.kid = kid;
        this.signer = signer;
        // A mismatched public/private pair must fail before any authorization can be consumed.
        try {
            String check = Ap2Crypto.sign(Ap2Crypto.jsonBytes(Ap2Crypto.object().put("synthetic", true)),
                    signer.getPrivate(), kid, receiptType());
            Ap2Crypto.verify(check, signer.getPublic(), kid, receiptType());
        } catch (Ap2Exception failure) { throw failure; }
        catch (RuntimeException failure) { throw new Ap2Exception(INVALID_KEY); }
    }

    public static final class Outcome {
        private final boolean accepted;
        private final String code;
        private final String receipt;
        private final CredentialBinding credential;
        private Outcome(boolean accepted, String code, String receipt, CredentialBinding credential) {
            this.accepted = accepted;
            this.code = code;
            this.receipt = receipt;
            this.credential = credential;
        }
        public boolean accepted() { return accepted; }
        public String code() { return code; }
        public String receipt() { return receipt; }
        public CredentialBinding credential() { return credential; }
        public Map<String, Object> safeSummary() {
            return Map.of("accepted", accepted, "code", code, "synthetic", true,
                    "financialAdmission", false, "receiptPresent", receipt != null,
                    "credentialPresent", credential != null);
        }
        @Override public String toString() { return "Outcome[synthetic decision; artifacts withheld; financialAdmission=false]"; }
    }

    public Outcome checkout(String mandate, String expectedCheckoutJwt, String merchantIssuer,
                            Context authContext, Context receiptContext, String receiptJti, String orderId) {
        preflight(Role.MERCHANT, authContext, receiptContext);
        // Validate both output branches before authorizeCheckout can consume its challenge.
        checkoutReceipt(receiptContext, receiptJti, PLACEHOLDER_REFERENCE, orderId, null);
        checkoutReceipt(receiptContext, receiptJti, PLACEHOLDER_REFERENCE, null, "invalid_credential");
        String reference = reference(mandate);
        if (reference == null) return rejectedWithoutReceipt();
        try {
            engine.authorizeCheckout(mandate, expectedCheckoutJwt, merchantIssuer, authContext);
        } catch (Ap2Exception failure) {
            String error = error(failure);
            return signed(false, error, () -> sign(checkoutReceipt(receiptContext, receiptJti, reference, null, error)));
        }
        return signed(true, "accepted", () -> sign(checkoutReceipt(receiptContext, receiptJti, reference, orderId, null)));
    }

    public Outcome credential(String mandate, ExpectedPayment expected, Context authContext,
                              Context receiptContext, String receiptJti, String paymentId) {
        preflight(Role.CREDENTIAL_PROVIDER, authContext, receiptContext);
        if (expected == null) throw new Ap2Exception(INVALID_CONTEXT);
        paymentReceipt(receiptContext, receiptJti, PLACEHOLDER_REFERENCE, paymentId, null, null, "invalid_credential");
        String reference = reference(mandate);
        if (reference == null) return rejectedWithoutReceipt();
        try {
            CredentialBinding binding = engine.authorizeCredential(mandate, expected, authContext);
            return new Outcome(true, "accepted", null, binding);
        } catch (Ap2Exception failure) {
            String error = error(failure);
            return signed(false, error, () -> sign(paymentReceipt(receiptContext, receiptJti, reference,
                    paymentId, null, null, error)));
        }
    }

    public Outcome process(String mandate, ExpectedPayment expected, Context authContext,
                           Context receiptContext, String receiptJti, String paymentId,
                           String pspId, String networkId, CredentialBinding credential) {
        preflight(Role.PAYMENT_PROCESSOR, authContext, receiptContext);
        if (expected == null) throw new Ap2Exception(INVALID_CONTEXT);
        syntheticConfirmation(pspId);
        syntheticConfirmation(networkId);
        paymentReceipt(receiptContext, receiptJti, PLACEHOLDER_REFERENCE, paymentId, pspId, networkId, null);
        paymentReceipt(receiptContext, receiptJti, PLACEHOLDER_REFERENCE, paymentId, null, null, "invalid_credential");
        String reference = reference(mandate);
        if (reference == null) return rejectedWithoutReceipt();
        try {
            engine.authorizeProcessor(mandate, expected, authContext, credential);
        } catch (Ap2Exception failure) {
            String error = error(failure);
            return signed(false, error, () -> sign(paymentReceipt(receiptContext, receiptJti, reference,
                    paymentId, null, null, error)));
        }
        return signed(true, "accepted", () -> sign(paymentReceipt(receiptContext, receiptJti, reference,
                paymentId, pspId, networkId, null)));
    }

    private void preflight(Role expectedRole, Context authContext, Context receiptContext) {
        if (role != expectedRole || authContext == null || receiptContext == null
                || authContext.role() != role || receiptContext.role() != role
                || !receiptContext.issuer().equals(issuer) || authContext.now() != receiptContext.now()
                || receiptContext.now() > Long.MAX_VALUE - 240) throw new Ap2Exception(INVALID_CONTEXT);
        engine.checkSigningKey(receiptPurpose(), issuer, kid, signer.getPublic(), receiptContext.now());
    }

    private CheckoutReceipt checkoutReceipt(Context context, String id, String reference, String orderId, String error) {
        try {
            return new CheckoutReceipt(issuer, context.now(), context.now() + 240, context.audience(), context.nonce(),
                    id, reference, error == null ? "Success" : "Error", error == null ? "success" : "error",
                    orderId, error, error == null ? null : REJECTION_TEXT);
        } catch (IllegalArgumentException failure) { throw new Ap2Exception(INVALID_CONTEXT); }
    }

    private PaymentReceipt paymentReceipt(Context context, String id, String reference, String paymentId,
                                          String pspId, String networkId, String error) {
        try {
            return new PaymentReceipt(issuer, context.now(), context.now() + 240, context.audience(), context.nonce(),
                    id, reference, error == null ? "Success" : "Error", error == null ? "success" : "error",
                    paymentId, pspId, networkId, error, error == null ? null : REJECTION_TEXT);
        } catch (IllegalArgumentException failure) { throw new Ap2Exception(INVALID_CONTEXT); }
    }

    private String sign(CheckoutReceipt receipt) {
        return Ap2Crypto.sign(Ap2Crypto.jsonBytes(Ap2Claims.toJson(receipt)), signer.getPrivate(), kid, receiptType());
    }

    private String sign(PaymentReceipt receipt) {
        return Ap2Crypto.sign(Ap2Crypto.jsonBytes(Ap2Claims.toJson(receipt)), signer.getPrivate(), kid, receiptType());
    }

    private Purpose receiptPurpose() {
        return switch (role) {
            case MERCHANT -> Purpose.CHECKOUT_RECEIPT;
            case CREDENTIAL_PROVIDER -> Purpose.CP_ERROR_RECEIPT;
            case PAYMENT_PROCESSOR -> Purpose.PAYMENT_RECEIPT;
        };
    }

    private String receiptType() {
        return role == Role.MERCHANT ? Ap2Crypto.CHECKOUT_RECEIPT_TYPE : Ap2Crypto.PAYMENT_RECEIPT_TYPE;
    }

    /** A malformed frame cannot identify a closed mandate to which a receipt may bind. */
    private static String reference(String mandate) {
        try {
            String compact = Ap2Crypto.issuerJwt(mandate);
            String[] segments = compact.split("\\.", -1);
            if (segments.length != 3 || segments[0].length() > 2048
                    || Ap2Crypto.decode(segments[0]).length == 0
                    || Ap2Crypto.decode(segments[1]).length > 65536
                    || Ap2Crypto.decode(segments[2]).length != 64) return null;
            return Ap2Crypto.hash(mandate);
        } catch (Ap2Exception failure) { return null; }
    }

    private static String error(Ap2Exception failure) {
        return switch (failure.code()) {
            case BINDING_MISMATCH, INVALID_CONTEXT, REPLAY_REJECTED -> "invalid_mandate";
            case UNSUPPORTED_PROFILE -> "mandates_not_supported";
            default -> "invalid_credential";
        };
    }

    private static void syntheticConfirmation(String value) {
        if (value == null || !value.startsWith("synthetic-")) throw new Ap2Exception(INVALID_CONTEXT);
    }

    private static Outcome rejectedWithoutReceipt() { return new Outcome(false, "invalid_credential", null, null); }
    private static Outcome signed(boolean accepted, String code, Supplier<String> signing) {
        try { return new Outcome(accepted, code, signing.get(), null); }
        catch (RuntimeException failure) {
            // A failed signer never reverses a consumed authorization or manufactures a rejection receipt.
            return new Outcome(accepted, "receipt_unavailable", null, null);
        }
    }
    @Override public String toString() { return "Ap2Exchange[synthetic role; signing material withheld; financialAdmission=false]"; }
}
