package io.agenticpayments.ap2;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.agenticpayments.ap2.Ap2Engine.*;

/** Adversarial expectations originate in independent Python fixture authorship. */
class Ap2ProtocolTest {
    @Test void verifiesIndependentNativeMandatesAndRejectsAdversarialVectors() throws Exception {
        JsonNode bundle = Ap2VectorHarness.vectors();
        assertEquals("ap2-v0.2-direct-tap-synthetic-0.1", bundle.path("profile").asText());
        int accepted = 0, rejected = 0;
        for (JsonNode vector : bundle.path("cases")) {
            String id = vector.path("id").asText();
            if (vector.path("accept").asBoolean()) {
                assertDoesNotThrow(() -> Ap2VectorHarness.runCase(bundle, vector), id);
                accepted++;
            } else {
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> Ap2VectorHarness.runCase(bundle, vector), id);
                assertTrue(error.getMessage().matches("[A-Z_]+|invalid_claims"), "safe fixed error for " + id);
                assertFalse(error.toString().contains(vector.path("token").asText()), "artifact redaction for " + id);
                rejected++;
            }
        }
        assertTrue(accepted >= 7);
        assertTrue(rejected >= 60);
    }

    @Test void independentlySpecifiedHashesUseExactCompactBytesAndTerminalTilde() throws Exception {
        JsonNode b = Ap2VectorHarness.vectors(), h = b.path("hashChecks");
        String checkout = b.path("expected").path("checkoutJwt").asText();
        assertEquals(h.path("checkoutHash").asText(), Ap2Crypto.hash(checkout));
        assertEquals(h.path("checkoutResignedHash").asText(), Ap2Crypto.hash(h.path("checkoutResigned").asText()));
        assertNotEquals(h.path("checkoutHash").asText(), h.path("checkoutResignedHash").asText());
        String cm = b.path("baseline").path("checkoutMandate").asText();
        assertEquals(h.path("checkoutReference").asText(), Ap2Crypto.hash(cm));
        assertNotEquals(h.path("checkoutReference").asText(), Ap2Crypto.hash(cm.substring(0, cm.length() - 1)));
        assertEquals(h.path("paymentReference").asText(), Ap2Crypto.hash(b.path("baseline").path("paymentMandate").asText()));
    }

    @Test void concurrentRegisteredChallengeHasOnlyOneAuthorizationWinner() throws Exception {
        JsonNode b = Ap2VectorHarness.vectors();
        Ap2Engine engine = Ap2VectorHarness.engine(b, "");
        Context ctx = Ap2VectorHarness.context(b.path("contexts").path("checkout"));
        String checkout = b.path("expected").path("checkoutJwt").asText();
        engine.issueChallenge(ctx, Ap2Crypto.hash(checkout), ctx.now() + 200);
        List<Callable<Boolean>> calls = new ArrayList<>();
        for (int i = 0; i < 16; i++) calls.add(() -> {
            try {
                engine.authorizeCheckout(b.path("baseline").path("checkoutMandate").asText(), checkout,
                        b.path("expected").path("merchantIssuer").asText(), ctx);
                return true;
            } catch (Ap2Exception e) {
                assertEquals(Ap2Exception.Code.REPLAY_REJECTED, e.code());
                return false;
            }
        });
        try (var pool = Executors.newFixedThreadPool(8)) {
            long winners = 0;
            for (var future : pool.invokeAll(calls)) if (future.get()) winners++;
            assertEquals(1, winners);
        }
    }

    @Test void scopedCredentialAndProcessorIndependentlyAuthorizeSamePayment() throws Exception {
        JsonNode b = Ap2VectorHarness.vectors();
        Ap2Engine engine = Ap2VectorHarness.engine(b, "");
        Context cp = Ap2VectorHarness.context(b.path("contexts").path("payment"));
        Context mpp = new Context(Role.PAYMENT_PROCESSOR, cp.issuer(), cp.audience(), cp.nonce(), cp.now());
        var expected = Ap2VectorHarness.expected(b);
        engine.issueChallenge(cp, Ap2Crypto.hash(expected.checkoutJwt()), cp.now() + 200);
        engine.issueChallenge(mpp, Ap2Crypto.hash(expected.checkoutJwt()), mpp.now() + 200);
        String mandate = b.path("baseline").path("paymentMandate").asText();
        var scope = engine.authorizeCredential(mandate, expected, cp);
        assertDoesNotThrow(() -> engine.authorizeProcessor(mandate, expected, mpp, scope));
        assertThrows(Ap2Exception.class, () -> engine.authorizeProcessor(mandate, expected, mpp, scope));
    }

    @Test void authorizationRequiresRegisteredChallengeAndFailureDoesNotConsumeIt() throws Exception {
        JsonNode b = Ap2VectorHarness.vectors();
        Ap2Engine engine = Ap2VectorHarness.engine(b, "");
        Context ctx = Ap2VectorHarness.context(b.path("contexts").path("checkout"));
        String mandate = b.path("baseline").path("checkoutMandate").asText();
        String checkout = b.path("expected").path("checkoutJwt").asText();
        String issuer = b.path("expected").path("merchantIssuer").asText();
        assertThrows(Ap2Exception.class, () -> engine.authorizeCheckout(mandate, checkout, issuer, ctx));
        engine.issueChallenge(ctx, Ap2Crypto.hash(checkout), ctx.now() + 200);
        assertThrows(Ap2Exception.class, () -> engine.authorizeCheckout(mandate,
                b.path("hashChecks").path("checkoutResigned").asText(), issuer, ctx));
        assertDoesNotThrow(() -> engine.authorizeCheckout(mandate, checkout, issuer, ctx));
    }

    @Test void replayIdentityUsesNonceAndJtiRatherThanRandomizedOrMalleableSignatureBytes() throws Exception {
        JsonNode b = Ap2VectorHarness.vectors();
        Ap2Engine engine = Ap2VectorHarness.engine(b, "");
        Context ctx = Ap2VectorHarness.context(b.path("contexts").path("checkout"));
        String checkout = b.path("expected").path("checkoutJwt").asText();
        String merchant = b.path("expected").path("merchantIssuer").asText();
        engine.issueChallenge(ctx, Ap2Crypto.hash(checkout), ctx.now() + 200);
        engine.authorizeCheckout(b.path("baseline").path("checkoutMandate").asText(), checkout, merchant, ctx);
        for (String name : List.of("resignedSameClaims", "malleatedSignature", "sameNonceNewJti")) {
            String token = b.path("replayVariants").path(name).asText();
            assertDoesNotThrow(() -> engine.verifyCheckout(token, checkout, merchant, ctx), name);
            Ap2Exception error = assertThrows(Ap2Exception.class,
                    () -> engine.authorizeCheckout(token, checkout, merchant, ctx), name);
            assertEquals(Ap2Exception.Code.REPLAY_REJECTED, error.code(), name);
        }
        Context other = new Context(ctx.role(), ctx.issuer(), ctx.audience(), "checkout-other-nonce", ctx.now());
        engine.issueChallenge(other, Ap2Crypto.hash(checkout), other.now() + 200);
        String alias = b.path("replayVariants").path("newNonceSameJti").asText();
        assertDoesNotThrow(() -> engine.verifyCheckout(alias, checkout, merchant, other));
        Ap2Exception error = assertThrows(Ap2Exception.class,
                () -> engine.authorizeCheckout(alias, checkout, merchant, other));
        assertEquals(Ap2Exception.Code.REPLAY_REJECTED, error.code());
    }

    @Test void emitsJavaArtifactsForIndependentPythonVerification() throws Exception {
        Ap2VectorHarness.emitJava(Path.of("target/ap2-vectors/java-output.json"));
    }

    @Test void fullNativeBundleVerifiesBothRoleAuthorizationsAndBothReceiptLinks() throws Exception {
        JsonNode b = Ap2VectorHarness.vectors();
        Ap2Engine engine = Ap2VectorHarness.engine(b, "");
        Context checkout = Ap2VectorHarness.context(b.path("contexts").path("checkout"));
        Context cp = Ap2VectorHarness.context(b.path("contexts").path("payment"));
        Context mpp = new Context(Role.PAYMENT_PROCESSOR, cp.issuer(), cp.audience(), cp.nonce(), cp.now());
        var contexts = new BundleContexts(checkout, cp, mpp,
                Ap2VectorHarness.context(b.path("contexts").path("checkoutReceipt")),
                Ap2VectorHarness.context(b.path("contexts").path("paymentReceipt")));
        String cr = null, pr = null;
        for (JsonNode vector : b.path("cases")) {
            if (vector.path("id").asText().equals("checkout-success-positive")) cr = vector.path("token").asText();
            if (vector.path("id").asText().equals("payment-success-positive")) pr = vector.path("token").asText();
        }
        assertNotNull(cr); assertNotNull(pr);
        String cm = b.path("baseline").path("checkoutMandate").asText();
        String pm = b.path("baseline").path("paymentMandate").asText();
        Bundle valid = new Bundle(cm, pm, cr, pr);
        assertNotNull(engine.verifyBundle(valid, Ap2VectorHarness.expected(b), contexts));
        Bundle mixedMandate = new Bundle(b.path("replayVariants").path("resignedSameClaims").asText(), pm, cr, pr);
        assertThrows(Ap2Exception.class, () -> engine.verifyBundle(mixedMandate, Ap2VectorHarness.expected(b), contexts));
        Bundle mixedReceiptTypes = new Bundle(cm, pm, pr, cr);
        assertThrows(Ap2Exception.class, () -> engine.verifyBundle(mixedReceiptTypes, Ap2VectorHarness.expected(b), contexts));
    }
}
