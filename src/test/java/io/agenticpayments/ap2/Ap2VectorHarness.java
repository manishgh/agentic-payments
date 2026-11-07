package io.agenticpayments.ap2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import static io.agenticpayments.ap2.Ap2Claims.*;
import static io.agenticpayments.ap2.Ap2Engine.*;

/** Test-only public-artifact bridge. No private key leaves this process. */
public final class Ap2VectorHarness {
    static final ObjectMapper JSON = new ObjectMapper();
    private Ap2VectorHarness() {}

    static JsonNode vectors() throws Exception {
        return JSON.readTree(Files.readAllBytes(Path.of(System.getProperty("ap2.vectors", "target/ap2-vectors/vectors.json"))));
    }

    static Context context(JsonNode node) {
        return new Context(Role.valueOf(node.path("role").asText()), node.path("issuer").asText(),
                node.path("audience").asText(), node.path("nonce").asText(), node.path("now").asLong());
    }

    static ExpectedPayment expected(JsonNode bundle) {
        JsonNode n = bundle.path("expected"), p = n.path("payee"), a = n.path("amount"), i = n.path("instrument");
        return new ExpectedPayment(n.path("checkoutJwt").asText(), n.path("merchantIssuer").asText(),
                new Merchant(p.path("id").asText(), p.path("name").asText(), p.path("website").asText()),
                new Amount(a.path("amount").asLong(), a.path("currency").asText()),
                new PaymentInstrument(i.path("id").asText(), i.path("type").asText(), i.path("description").asText()));
    }

    static Ap2Engine engine(JsonNode bundle, String policy) {
        List<TrustKey> keys = new ArrayList<>();
        for (JsonNode n : bundle.path("keys")) {
            Purpose purpose = Purpose.valueOf(n.path("purpose").asText());
            boolean mandate = purpose == Purpose.CHECKOUT_MANDATE;
            long from = n.path("validFrom").asLong(), until = n.path("validUntil").asLong();
            boolean revoked = n.path("revoked").asBoolean();
            if (mandate && "revoked".equals(policy)) revoked = true;
            if (mandate && "expired".equals(policy)) until = bundle.path("now").asLong();
            if (mandate && "future".equals(policy)) from = bundle.path("now").asLong() + 1;
            if (mandate && "wrongPurpose".equals(policy)) continue;
            keys.add(new TrustKey(purpose, n.path("issuer").asText(), n.path("kid").asText(),
                    Ap2Crypto.publicKey(n.path("spki").asText()), from, until, revoked));
        }
        return new Ap2Engine(keys);
    }

    static Object runCase(JsonNode bundle, JsonNode vector) {
        Ap2Engine engine = engine(bundle, vector.path("keyPolicy").asText());
        String operation = vector.path("operation").asText(), token = vector.path("token").asText();
        Context context = context(bundle.path("contexts").path(operation));
        return switch (operation) {
            case "checkout" -> engine.verifyCheckout(token, bundle.path("expected").path("checkoutJwt").asText(),
                    bundle.path("expected").path("merchantIssuer").asText(), context);
            case "payment" -> engine.verifyPayment(token, expected(bundle), context);
            case "checkoutReceipt" -> engine.verifyCheckoutReceipt(token, bundle.path("baseline").path("checkoutMandate").asText(), context);
            case "paymentReceipt", "cpReceipt" -> engine.verifyPaymentReceipt(token, bundle.path("baseline").path("paymentMandate").asText(), context);
            default -> throw new IllegalStateException("unknown_vector_operation");
        };
    }

    static String sign(ObjectNode node, KeyPair key, String kid, String typ) {
        return Ap2Crypto.sign(Ap2Crypto.jsonBytes(node), key.getPrivate(), kid, typ);
    }

    /** Emit Java-produced native artifacts for independent Python verification. */
    static void emitJava(Path output) throws Exception {
        long now = 1794045600L;
        KeyPair provider = Ap2Crypto.generateKeyPair(), merchant = Ap2Crypto.generateKeyPair(), processor = Ap2Crypto.generateKeyPair(), cpKey = Ap2Crypto.generateKeyPair();
        ObjectNode checkoutBody = JSON.createObjectNode().put("id", "synthetic-java-checkout").put("revision", "1")
                .put("amount", 1250).put("currency", "EUR");
        String checkout = sign(checkoutBody, merchant, "java-merchant", "ap2-checkout+jwt");
        Merchant payee = new Merchant("synthetic-java-merchant", "Synthetic Java Merchant", "https://java-merchant.synthetic.example");
        Amount amount = new Amount(1250, "EUR");
        PaymentInstrument instrument = new PaymentInstrument("synthetic-java-instrument", "synthetic", "Non-negotiable fixture");
        String cm = sign(toJson(new Envelope("https://java-tap.synthetic.example", now - 5, now + 240,
                "java-merchant", "java-checkout-nonce", "java-checkout-jti", new CheckoutMandate(checkout, Ap2Crypto.hash(checkout)))),
                provider, "java-provider", "dc+sd-jwt") + "~";
        String pm = sign(toJson(new Envelope("https://java-tap.synthetic.example", now - 5, now + 240,
                "java-payment", "java-payment-nonce", "java-payment-jti", new PaymentMandate(Ap2Crypto.hash(checkout), payee, amount, instrument))),
                provider, "java-provider", "dc+sd-jwt") + "~";
        List<TrustKey> trusted = new ArrayList<>();
        for (Purpose p : List.of(Purpose.CHECKOUT_MANDATE, Purpose.PAYMENT_MANDATE, Purpose.PROCESSOR_PAYMENT_MANDATE))
            trusted.add(new TrustKey(p, "https://java-tap.synthetic.example", "java-provider", provider.getPublic(), now - 1000, now + 1000, false));
        for (Purpose p : List.of(Purpose.MERCHANT_CHECKOUT, Purpose.CHECKOUT_RECEIPT))
            trusted.add(new TrustKey(p, "https://java-merchant.synthetic.example", "java-merchant", merchant.getPublic(), now - 1000, now + 1000, false));
        trusted.add(new TrustKey(Purpose.PAYMENT_RECEIPT, "https://java-processor.synthetic.example", "java-processor", processor.getPublic(), now - 1000, now + 1000, false));
        trusted.add(new TrustKey(Purpose.CP_ERROR_RECEIPT, "https://java-cp.synthetic.example", "java-cp", cpKey.getPublic(), now - 1000, now + 1000, false));
        Ap2Engine engine = new Ap2Engine(trusted);
        Context merchantAuth = new Context(Role.MERCHANT, "https://java-tap.synthetic.example", "java-merchant", "java-checkout-nonce", now);
        Context cpAuth = new Context(Role.CREDENTIAL_PROVIDER, "https://java-tap.synthetic.example", "java-payment", "java-payment-nonce", now);
        Context mppAuth = new Context(Role.PAYMENT_PROCESSOR, "https://java-tap.synthetic.example", "java-payment", "java-payment-nonce", now);
        Context merchantReceipt = new Context(Role.MERCHANT, "https://java-merchant.synthetic.example", "java-agent", "java-checkout-receipt", now);
        Context cpReceipt = new Context(Role.CREDENTIAL_PROVIDER, "https://java-cp.synthetic.example", "java-agent", "java-cp-receipt", now);
        Context mppReceipt = new Context(Role.PAYMENT_PROCESSOR, "https://java-processor.synthetic.example", "java-agent", "java-payment-receipt", now);
        for (Context ctx : List.of(merchantAuth, cpAuth, mppAuth)) engine.issueChallenge(ctx, Ap2Crypto.hash(checkout), now + 200);
        var factsExpected = new ExpectedPayment(checkout, "https://java-merchant.synthetic.example", payee, amount, instrument);
        var merchantOutcome = new Ap2Exchange(engine, Role.MERCHANT, "https://java-merchant.synthetic.example", "java-merchant", merchant)
                .checkout(cm, checkout, "https://java-merchant.synthetic.example", merchantAuth, merchantReceipt, "java-checkout-receipt-jti", "synthetic-java-order");
        var credentialOutcome = new Ap2Exchange(engine, Role.CREDENTIAL_PROVIDER, "https://java-cp.synthetic.example", "java-cp", cpKey)
                .credential(pm, factsExpected, cpAuth, cpReceipt, "java-cp-receipt-jti", "synthetic-java-payment");
        var processorOutcome = new Ap2Exchange(engine, Role.PAYMENT_PROCESSOR, "https://java-processor.synthetic.example", "java-processor", processor)
                .process(pm, factsExpected, mppAuth, mppReceipt, "java-payment-receipt-jti", "synthetic-java-payment", "synthetic-java-psp", "synthetic-java-network", credentialOutcome.credential());
        if (!merchantOutcome.accepted() || !credentialOutcome.accepted() || !processorOutcome.accepted())
            throw new IllegalStateException("synthetic_exchange_rejected");
        String cr = merchantOutcome.receipt(), pr = processorOutcome.receipt();
        engine.verifyCheckoutReceipt(cr, cm, merchantReceipt);
        engine.verifyPaymentReceipt(pr, pm, mppReceipt);
        engine.verifyBundle(new Bundle(cm, pm, cr, pr), factsExpected,
                new BundleContexts(merchantAuth, cpAuth, mppAuth, merchantReceipt, mppReceipt));
        ObjectNode result = JSON.createObjectNode().put("profile", PROFILE).put("now", now).put("checkoutJwt", checkout)
                .put("checkoutMandate", cm).put("paymentMandate", pm).put("checkoutReceipt", cr).put("paymentReceipt", pr);
        result.putObject("keys").put("provider", Ap2Crypto.publicKeyEncoded(provider.getPublic()))
                .put("merchant", Ap2Crypto.publicKeyEncoded(merchant.getPublic())).put("processor", Ap2Crypto.publicKeyEncoded(processor.getPublic()));
        ObjectNode facts = result.putObject("expected");
        facts.set("payee", JSON.valueToTree(java.util.Map.of("id", payee.id(), "name", payee.name(), "website", payee.website())));
        facts.set("amount", JSON.valueToTree(java.util.Map.of("amount", amount.amount(), "currency", amount.currency())));
        facts.set("instrument", JSON.valueToTree(java.util.Map.of("id", instrument.id(), "type", instrument.type(), "description", instrument.description())));
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.write(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(result));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("expected_public_output_path");
        emitJava(Path.of(args[0]));
        System.out.println("{\"javaPublicArtifactsEmitted\":5,\"privateKeysPersisted\":false}");
    }
}
