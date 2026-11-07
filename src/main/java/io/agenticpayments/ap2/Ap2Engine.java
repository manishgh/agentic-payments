package io.agenticpayments.ap2;

import com.fasterxml.jackson.databind.JsonNode;
import java.security.PublicKey;
import java.security.MessageDigest;
import java.util.*;
import io.agenticpayments.crypto.SignatureProvider;
import io.agenticpayments.crypto.JcaSignatureProvider;
import static io.agenticpayments.ap2.Ap2Exception.Code.*;
import static io.agenticpayments.ap2.Ap2Claims.*;

/** Native direct-TAP artifact verification. Deliberately disconnected from financial admission. */
public final class Ap2Engine {
    public enum Purpose { CHECKOUT_MANDATE, PAYMENT_MANDATE, PROCESSOR_PAYMENT_MANDATE, MERCHANT_CHECKOUT,
        CHECKOUT_RECEIPT, PAYMENT_RECEIPT, CP_ERROR_RECEIPT }
    public enum Role { MERCHANT, CREDENTIAL_PROVIDER, PAYMENT_PROCESSOR }

    public record TrustKey(Purpose purpose, String issuer, String kid, PublicKey publicKey,
                           long validFrom, long validUntil, boolean revoked) {
        public TrustKey {
            if (purpose == null || validFrom < 0 || validUntil <= validFrom) throw new Ap2Exception(INVALID_KEY);
            identity(issuer); identity(kid); Ap2Crypto.requireP256(publicKey);
        }
        @Override public String toString() { return "TrustKey[configured material withheld]"; }
    }
    public record Context(Role role, String issuer, String audience, String nonce, long now) {
        public Context {
            if (role == null || now < 0) throw new Ap2Exception(INVALID_CONTEXT);
            identity(issuer); identity(audience); identity(nonce);
        }
        @Override public String toString() { return "Context[verification inputs withheld]"; }
    }
    public record ExpectedPayment(String checkoutJwt, String merchantIssuer, Merchant payee,
                                  Amount amount, PaymentInstrument instrument) {
        public ExpectedPayment {
            identity(merchantIssuer);
            if (checkoutJwt == null || payee == null || amount == null || instrument == null)
                throw new Ap2Exception(INVALID_CONTEXT);
        }
        @Override public String toString() { return "ExpectedPayment[trusted facts withheld]"; }
    }
    public record Proof(String checkoutHash, String mandateHash, String issuer, String jwtId, long expiresAt) {
        @Override public String toString() { return "Proof[verified artifact references withheld]"; }
    }
    public record Bundle(String checkoutMandate, String paymentMandate, String checkoutReceipt, String paymentReceipt) {
        @Override public String toString() { return "Bundle[signed artifacts withheld]"; }
    }
    public record BundleContexts(Context checkout, Context payment, Context processor,
                                 Context checkoutReceipt, Context paymentReceipt) {
        @Override public String toString() { return "BundleContexts[verification inputs withheld]"; }
    }
    public record BundleProof(String checkoutHash, String checkoutMandateHash, String paymentMandateHash) {}
    /** In-process CP result, not a transferable credential or payment authority object. */
    public static final class CredentialBinding {
        private final Object owner;
        private final Proof proof;
        private final ExpectedPayment expected;
        private final String audience;
        private final String nonce;
        private CredentialBinding(Object owner, Proof proof, ExpectedPayment expected, Context context) {
            this.owner = owner; this.proof = proof; this.expected = expected;
            this.audience = context.audience(); this.nonce = context.nonce();
        }
        public String checkoutHash() { return proof.checkoutHash(); }
        public String mandateHash() { return proof.mandateHash(); }
        @Override public String toString() { return "CredentialBinding[synthetic, non-negotiable]"; }
    }
    private record KeyId(Purpose purpose, String issuer, String kid) {}
    private record ChallengeId(Role role, String nonce) {}
    private record AuthorizationId(Role role, String issuer, String jwtId) {}
    private record Challenge(String issuer, String audience, String checkoutHash, long expiresAt) {}
    private record Verified(Envelope envelope, Proof proof) {}
    private final Map<KeyId, TrustKey> keys;
    private final SignatureProvider signatures;
    private final Object bindingOwner = new Object();
    private final Map<ChallengeId, Challenge> challenges = new HashMap<>();
    private final Set<ChallengeId> consumed = new HashSet<>();
    private final Set<AuthorizationId> authorizations = new HashSet<>();

    public Ap2Engine(List<TrustKey> trustedKeys) {
        this(trustedKeys,new JcaSignatureProvider());
    }

    public Ap2Engine(List<TrustKey> trustedKeys, SignatureProvider signatures) {
        Ap2Crypto.requireProvider(signatures);
        this.signatures=signatures;
        if (trustedKeys == null || trustedKeys.isEmpty()) throw new Ap2Exception(INVALID_KEY);
        Map<KeyId, TrustKey> registered = new HashMap<>();
        for (TrustKey key : trustedKeys) {
            if (key == null || registered.putIfAbsent(new KeyId(key.purpose(), key.issuer(), key.kid()), key) != null)
                throw new Ap2Exception(INVALID_KEY);
        }
        keys = Map.copyOf(registered);
    }

    private static void identity(String value) {
        if (value == null || value.isEmpty() || value.length() > 512
                || value.chars().anyMatch(c -> c < 32 || c == 127)) throw new Ap2Exception(INVALID_CONTEXT);
    }

    private static void digest(String value) {
        if (value == null || value.length() != 43 || Ap2Crypto.decode(value).length != 32)
            throw new Ap2Exception(INVALID_CONTEXT);
    }

    /** Trusted verifier integration registers a transaction-bound challenge before authorization. */
    public synchronized void issueChallenge(Context context, String checkoutHash, long deadline) {
        if (context == null) throw new Ap2Exception(INVALID_CONTEXT);
        digest(checkoutHash);
        if (deadline <= context.now() || deadline - context.now() > 300 || challenges.size() >= 10000)
            throw new Ap2Exception(INVALID_CONTEXT);
        ChallengeId id = new ChallengeId(context.role(), context.nonce());
        if (challenges.putIfAbsent(id, new Challenge(context.issuer(), context.audience(), checkoutHash, deadline)) != null)
            throw new Ap2Exception(REPLAY_REJECTED);
    }

    private synchronized void consume(Context context, Envelope envelope, Proof proof) {
        ChallengeId id = new ChallengeId(context.role(), context.nonce());
        Challenge challenge = challenges.get(id);
        AuthorizationId identity = new AuthorizationId(context.role(), envelope.issuer(), envelope.jwtId());
        if (challenge == null || challenge.expiresAt() <= context.now()
                || !challenge.issuer().equals(envelope.issuer()) || !challenge.audience().equals(context.audience())
                || !challenge.checkoutHash().equals(proof.checkoutHash())
                || consumed.contains(id) || authorizations.contains(identity)) throw new Ap2Exception(REPLAY_REJECTED);
        // Both predicates are committed under one monitor, only after every verification succeeds.
        consumed.add(id);
        authorizations.add(identity);
    }

    private TrustKey key(Purpose purpose, String issuer, String compact, long now) {
        String kid = Ap2Crypto.protectedHeader(compact).get("kid").textValue();
        TrustKey key = keys.get(new KeyId(purpose, issuer, kid));
        if (key == null || key.revoked() || now < key.validFrom() || now >= key.validUntil())
            throw new Ap2Exception(UNTRUSTED_KEY);
        return key;
    }

    void checkSigningKey(Purpose purpose, String issuer, String kid, PublicKey publicKey, long now) {
        TrustKey configured = keys.get(new KeyId(purpose, issuer, kid));
        if (configured == null || configured.revoked() || now < configured.validFrom() || now >= configured.validUntil()
                || !MessageDigest.isEqual(configured.publicKey().getEncoded(), publicKey.getEncoded()))
            throw new Ap2Exception(UNTRUSTED_KEY);
    }

    private static void lifetime(long issuedAt, long expiresAt, Context context, TrustKey key) {
        if (issuedAt < 0 || expiresAt <= issuedAt || expiresAt - issuedAt > 300
                || issuedAt > context.now() || context.now() >= expiresAt
                || issuedAt < key.validFrom() || issuedAt >= key.validUntil()) throw new Ap2Exception(EXPIRED_AUTHORITY);
    }

    private Envelope envelope(String mandate, Purpose purpose, Context context) {
        String compact = Ap2Crypto.issuerJwt(mandate);
        TrustKey key = key(purpose, context.issuer(), compact, context.now());
        JsonNode payload = Ap2Crypto.verify(compact, key.publicKey(), key.kid(), Ap2Crypto.MANDATE_TYPE,signatures);
        Envelope envelope;
        try { envelope = Ap2Claims.envelope(payload); }
        catch (IllegalArgumentException e) { throw new Ap2Exception(INVALID_CLAIMS); }
        if (!envelope.issuer().equals(context.issuer()) || !envelope.audience().equals(context.audience())
                || !envelope.nonce().equals(context.nonce())) throw new Ap2Exception(INVALID_CONTEXT);
        lifetime(envelope.issuedAt(), envelope.expiresAt(), context, key);
        return envelope;
    }

    private void merchantCheckout(String compact, String merchantIssuer, long now) {
        TrustKey key = key(Purpose.MERCHANT_CHECKOUT, merchantIssuer, compact, now);
        Ap2Crypto.verify(compact, key.publicKey(), key.kid(), Ap2Crypto.CHECKOUT_TYPE,signatures);
    }

    private Verified checkout(String mandate, String expectedCheckoutJwt, String merchantIssuer, Context context) {
        if (context == null || expectedCheckoutJwt == null || merchantIssuer == null || context.role() != Role.MERCHANT)
            throw new Ap2Exception(INVALID_CONTEXT);
        Envelope envelope = envelope(mandate, Purpose.CHECKOUT_MANDATE, context);
        if (!(envelope.content() instanceof CheckoutMandate checkout)) throw new Ap2Exception(INVALID_CLAIMS);
        if (!checkout.checkoutJwt().equals(expectedCheckoutJwt)
                || !checkout.checkoutHash().equals(Ap2Crypto.hash(checkout.checkoutJwt())))
            throw new Ap2Exception(BINDING_MISMATCH);
        merchantCheckout(checkout.checkoutJwt(), merchantIssuer, context.now());
        return new Verified(envelope, new Proof(checkout.checkoutHash(), Ap2Crypto.hash(mandate),
            envelope.issuer(), envelope.jwtId(), envelope.expiresAt()));
    }

    public Proof verifyCheckout(String mandate, String expectedCheckoutJwt, String merchantIssuer, Context context) {
        return checkout(mandate, expectedCheckoutJwt, merchantIssuer, context).proof();
    }

    public Proof authorizeCheckout(String mandate, String expectedCheckoutJwt, String merchantIssuer, Context context) {
        Verified verified = checkout(mandate, expectedCheckoutJwt, merchantIssuer, context);
        consume(context, verified.envelope(), verified.proof());
        return verified.proof();
    }

    private Verified payment(String mandate, ExpectedPayment expected, Context context) {
        if (context == null || expected == null || (context.role() != Role.CREDENTIAL_PROVIDER && context.role() != Role.PAYMENT_PROCESSOR))
            throw new Ap2Exception(INVALID_CONTEXT);
        Envelope envelope = envelope(mandate, context.role() == Role.CREDENTIAL_PROVIDER
            ? Purpose.PAYMENT_MANDATE : Purpose.PROCESSOR_PAYMENT_MANDATE, context);
        if (!(envelope.content() instanceof PaymentMandate payment)) throw new Ap2Exception(INVALID_CLAIMS);
        String checkoutHash = Ap2Crypto.hash(expected.checkoutJwt());
        if (!payment.transactionId().equals(checkoutHash) || !payment.payee().equals(expected.payee())
                || !payment.paymentAmount().equals(expected.amount()) || !payment.paymentInstrument().equals(expected.instrument()))
            throw new Ap2Exception(BINDING_MISMATCH);
        merchantCheckout(expected.checkoutJwt(), expected.merchantIssuer(), context.now());
        return new Verified(envelope, new Proof(checkoutHash, Ap2Crypto.hash(mandate),
            envelope.issuer(), envelope.jwtId(), envelope.expiresAt()));
    }

    public Proof verifyPayment(String mandate, ExpectedPayment expected, Context context) {
        return payment(mandate, expected, context).proof();
    }

    public CredentialBinding authorizeCredential(String mandate, ExpectedPayment expected, Context context) {
        if (context == null || context.role() != Role.CREDENTIAL_PROVIDER) throw new Ap2Exception(INVALID_CONTEXT);
        Verified verified = payment(mandate, expected, context);
        consume(context, verified.envelope(), verified.proof());
        return new CredentialBinding(bindingOwner, verified.proof(), expected, context);
    }

    public Proof authorizeProcessor(String mandate, ExpectedPayment expected, Context context, CredentialBinding binding) {
        if (context == null || context.role() != Role.PAYMENT_PROCESSOR) throw new Ap2Exception(INVALID_CONTEXT);
        Verified verified = payment(mandate, expected, context);
        if (binding == null || binding.owner != bindingOwner || !binding.proof.equals(verified.proof())
                || !binding.expected.equals(expected) || !binding.audience.equals(context.audience())
                || !binding.nonce.equals(context.nonce())) throw new Ap2Exception(BINDING_MISMATCH);
        consume(context, verified.envelope(), verified.proof());
        return verified.proof();
    }

    private JsonNode receiptPayload(String compact, Purpose purpose, String typ, Context context) {
        TrustKey key = key(purpose, context.issuer(), compact, context.now());
        return Ap2Crypto.verify(compact, key.publicKey(), key.kid(), typ,signatures);
    }

    private void checkReceipt(Receipt receipt, String compact, String mandate, Purpose purpose, Context context) {
        Ap2Crypto.issuerJwt(mandate); // An exact, framed reference; does not imply this mandate was authorized.
        if (!receipt.issuer().equals(context.issuer()) || !receipt.audience().equals(context.audience())
                || !receipt.nonce().equals(context.nonce()) || !receipt.reference().equals(Ap2Crypto.hash(mandate)))
            throw new Ap2Exception(INVALID_RECEIPT);
        lifetime(receipt.issuedAt(), receipt.expiresAt(), context, key(purpose, context.issuer(), compact, context.now()));
    }

    public CheckoutReceipt verifyCheckoutReceipt(String receipt, String mandate, Context context) {
        if (context == null || context.role() != Role.MERCHANT) throw new Ap2Exception(INVALID_CONTEXT);
        JsonNode payload = receiptPayload(receipt, Purpose.CHECKOUT_RECEIPT, Ap2Crypto.CHECKOUT_RECEIPT_TYPE, context);
        CheckoutReceipt parsed;
        try { parsed = Ap2Claims.checkoutReceipt(payload); }
        catch (IllegalArgumentException e) { throw new Ap2Exception(INVALID_RECEIPT); }
        checkReceipt(parsed, receipt, mandate, Purpose.CHECKOUT_RECEIPT, context);
        return parsed;
    }

    public PaymentReceipt verifyPaymentReceipt(String receipt, String mandate, Context context) {
        if (context == null) throw new Ap2Exception(INVALID_CONTEXT);
        Purpose purpose;
        if (context.role() == Role.CREDENTIAL_PROVIDER) purpose = Purpose.CP_ERROR_RECEIPT;
        else if (context.role() == Role.PAYMENT_PROCESSOR) purpose = Purpose.PAYMENT_RECEIPT;
        else throw new Ap2Exception(INVALID_CONTEXT);
        JsonNode payload = receiptPayload(receipt, purpose, Ap2Crypto.PAYMENT_RECEIPT_TYPE, context);
        PaymentReceipt parsed;
        try { parsed = Ap2Claims.paymentReceipt(payload); }
        catch (IllegalArgumentException e) { throw new Ap2Exception(INVALID_RECEIPT); }
        if (purpose == Purpose.CP_ERROR_RECEIPT && !parsed.status().equals("Error")) throw new Ap2Exception(INVALID_RECEIPT);
        checkReceipt(parsed, receipt, mandate, purpose, context);
        return parsed;
    }

    /** Read-only verification of the complete successful synthetic exchange, not a financial receipt adjudicator. */
    public BundleProof verifyBundle(Bundle bundle, ExpectedPayment expected, BundleContexts contexts) {
        if (bundle == null || expected == null || contexts == null || contexts.checkout() == null
                || contexts.payment() == null || contexts.processor() == null || contexts.checkoutReceipt() == null
                || contexts.paymentReceipt() == null || contexts.checkout().role() != Role.MERCHANT
                || contexts.payment().role() != Role.CREDENTIAL_PROVIDER || contexts.processor().role() != Role.PAYMENT_PROCESSOR
                || contexts.checkoutReceipt().role() != Role.MERCHANT || contexts.paymentReceipt().role() != Role.PAYMENT_PROCESSOR)
            throw new Ap2Exception(INVALID_CONTEXT);
        Proof checkout = verifyCheckout(bundle.checkoutMandate(), expected.checkoutJwt(), expected.merchantIssuer(), contexts.checkout());
        Proof payment = verifyPayment(bundle.paymentMandate(), expected, contexts.payment());
        Proof processor = verifyPayment(bundle.paymentMandate(), expected, contexts.processor());
        CheckoutReceipt cr = verifyCheckoutReceipt(bundle.checkoutReceipt(), bundle.checkoutMandate(), contexts.checkoutReceipt());
        PaymentReceipt pr = verifyPaymentReceipt(bundle.paymentReceipt(), bundle.paymentMandate(), contexts.paymentReceipt());
        if (!checkout.checkoutHash().equals(payment.checkoutHash()) || !payment.equals(processor)
                || !cr.status().equals("Success") || !pr.status().equals("Success")) throw new Ap2Exception(INVALID_RECEIPT);
        return new BundleProof(checkout.checkoutHash(), checkout.mandateHash(), payment.mandateHash());
    }
}
