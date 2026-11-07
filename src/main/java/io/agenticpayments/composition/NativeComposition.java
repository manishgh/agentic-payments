package io.agenticpayments.composition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agenticpayments.ap2.*;
import io.agenticpayments.crypto.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static io.agenticpayments.composition.Composition.*;

/** Independent native protocol reference adapters; no JDBC, payment store or provider dependency. */
public final class NativeComposition {
    private static final String HANDLER="synthetic-handler-1";
    private final SignatureProvider signatures;
    private final Supplier<String> jwtIds;
    public NativeComposition(SignatureProvider signatures) { this(signatures,()->UUID.randomUUID().toString()); }
    /** Trusted fixture issuance hook; a substituted identity is still independently signature-verified. */
    public NativeComposition(SignatureProvider signatures,Supplier<String> jwtIds) {
        Ap2Crypto.requireProvider(signatures); this.signatures=signatures; this.jwtIds=Objects.requireNonNull(jwtIds);
    }
    public static Ports ports(SignedArtifactStore artifacts) {
        var provider=new JcaSignatureProvider(); var nativeProtocols=new NativeComposition(provider);
        return new Ports(nativeProtocols.checkout(),nativeProtocols.issuer(),nativeProtocols.verifier(),new FixtureKeyProvisioner(provider),artifacts);
    }
    public CheckoutProtocol checkout() { return new ReferenceCheckout(); }
    public AuthorityIssuer issuer() { return new ReferenceIssuer(); }
    public AuthorityVerifier verifier() { return new ReferenceVerifier(); }
    private static Descriptor descriptor(String contract,String... capabilities) {
        return new Descriptor(contract,VERSION,"independent-synthetic-"+contract,Set.of(PROFILE),Set.of(capabilities),"simulator-core");
    }
    private static void fixture(CheckoutFacts facts) {
        if(!"merchant-demo".equals(facts.merchantId())||!"fixture-credential-1".equals(facts.credentialReference())
                ||!Set.of("EUR","USD").contains(facts.currency())||facts.minorUnits()>999999999999L)
            reject(Failure.UNSUPPORTED_CAPABILITY);
    }
    private static String session(CheckoutFacts facts) { return "synthetic-checkout-"+facts.intentId()+"-r"+facts.revision(); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static Ap2Claims.Merchant payee(CheckoutFacts facts) {
        return new Ap2Claims.Merchant(facts.merchantId(),"Synthetic merchant","https://merchant.example.invalid");
    }
    private static Ap2Claims.PaymentInstrument instrument(CheckoutFacts facts) {
        return new Ap2Claims.PaymentInstrument(facts.credentialReference(),"synthetic-card",null);
    }
    /** Closed supported ACP fixture. Unknown fields/handlers fail by full-tree comparison at validation. */
    private static ObjectNode acp(CheckoutFacts facts) {
        fixture(facts);
        ObjectNode root=Ap2Crypto.object().put("id",session(facts));
        root.putObject("protocol").put("version",ACP_VERSION);
        root.put("status","ready_for_payment").put("currency",facts.currency().toLowerCase(Locale.ROOT));
        ObjectNode line=root.putArray("line_items").addObject().put("id","synthetic-line-1");
        line.putObject("item").put("id","synthetic-sku-1").put("name","Synthetic item").put("unit_amount",facts.minorUnits());
        line.put("quantity",1);
        line.putArray("totals").addObject().put("type","total").put("display_text","Total").put("amount",facts.minorUnits());
        root.putArray("totals").addObject().put("type","total").put("display_text","Total").put("amount",facts.minorUnits());
        root.putArray("fulfillment_options"); root.putArray("messages"); root.putArray("links");
        ObjectNode handler=root.putObject("capabilities").putObject("payment").putArray("handlers").addObject();
        handler.put("id",HANDLER).put("name","invalid.example.synthetic.card").put("version",ACP_VERSION)
            .put("spec","https://merchant.example.invalid/handler").put("requires_delegate_payment",false)
            .put("requires_pci_compliance",false).put("psp","synthetic-psp")
            .put("config_schema","https://merchant.example.invalid/config.schema.json");
        handler.putArray("instrument_schemas").add("https://merchant.example.invalid/instrument.schema.json");
        handler.putObject("config").put("merchant_id",facts.merchantId()).put("environment","offline-only");
        root.put("expires_at",facts.expiresAt().toString()); return root;
    }
    private static TrustKey resolve(KeyResolver resolver,Scope scope,String issuer,String compact,String purpose,Instant now) {
        String kid=Ap2Crypto.protectedHeader(compact).get("kid").textValue();
        TrustKey key=resolver.resolve(scope,issuer,kid,purpose,now);
        if(key==null||!scope.equals(key.scope())||!issuer.equals(key.issuer())||!kid.equals(key.kid())||!purpose.equals(key.purpose())
                ||key.revoked()||now.isBefore(key.validFrom())||!now.isBefore(key.validUntil())) reject(Failure.UNTRUSTED_KEY);
        return key;
    }
    private static Ap2Engine.TrustKey engineKey(TrustKey key) {
        return new Ap2Engine.TrustKey(Ap2Engine.Purpose.valueOf(key.purpose()),key.issuer(),key.kid(),
            Ap2Crypto.publicKey(key.publicKeySpki()),key.validFrom().getEpochSecond(),key.validUntil().getEpochSecond(),key.revoked());
    }
    private static CompositionException map(Ap2Exception failure) {
        return new CompositionException(switch(failure.code()) {
            case EXPIRED_AUTHORITY -> Failure.EXPIRED_AUTHORITY;
            case UNTRUSTED_KEY, INVALID_KEY -> Failure.UNTRUSTED_KEY;
            case UNSUPPORTED_PROFILE -> Failure.UNSUPPORTED_CAPABILITY;
            default -> Failure.INVALID_AUTHORITY;
        });
    }
    private final class ReferenceCheckout implements CheckoutProtocol {
        @Override public Descriptor describe() { return descriptor("checkout-protocol","immutable-checkout","exact-money","local-freeze"); }
        @Override public FrozenCheckout freeze(CheckoutRequest request) {
            Objects.requireNonNull(request); byte[] payload=Ap2Crypto.jsonBytes(acp(request.facts()));
            String jwt;
            try { jwt=Ap2Crypto.sign(payload,signatures,request.signer().keyHandle(),request.signer().kid(),Ap2Crypto.CHECKOUT_TYPE); }
            catch(RuntimeException failure) { throw new CompositionException(Failure.SIGNING_UNAVAILABLE); }
            return new FrozenCheckout(session(request.facts()),new String(payload,StandardCharsets.UTF_8),jwt,Ap2Crypto.hash(jwt),
                ArtifactIntegrity.digest(bytes(jwt)),ArtifactIntegrity.digest(payload),request.signer().issuer(),HANDLER,ACP_VERSION);
        }
        @Override public CheckoutFacts validate(CheckoutFacts expected,FrozenCheckout checkout,KeyResolver keys,Instant now) {
            Objects.requireNonNull(expected); Objects.requireNonNull(checkout); Objects.requireNonNull(keys); Objects.requireNonNull(now);
            try {
                fixture(expected);
                if(!now.isBefore(expected.expiresAt())) reject(Failure.EXPIRED_AUTHORITY);
                if(!session(expected).equals(checkout.sessionId())||!ACP_VERSION.equals(checkout.protocolVersion())
                        ||!HANDLER.equals(checkout.handlerId())||!Ap2Crypto.hash(checkout.checkoutJwt()).equals(checkout.checkoutHash())
                        ||!ArtifactIntegrity.digest(bytes(checkout.checkoutJwt())).equals(checkout.checkoutDigest())
                        ||!ArtifactIntegrity.digest(bytes(checkout.acpPayload())).equals(checkout.payloadDigest())) reject(Failure.INVALID_AUTHORITY);
                TrustKey key=resolve(keys,expected.scope(),checkout.merchantIssuer(),checkout.checkoutJwt(),MERCHANT_CHECKOUT,now);
                JsonNode authenticated=Ap2Crypto.verify(checkout.checkoutJwt(),Ap2Crypto.publicKey(key.publicKeySpki()),key.kid(),Ap2Crypto.CHECKOUT_TYPE,signatures);
                String[] parts=checkout.checkoutJwt().split("\\.",-1);
                byte[] original=Ap2Crypto.decode(parts[1]);
                // Jackson's constructed LongNode and parsed IntNode can represent the same exact integer.
                // Normalize only the trusted expected tree, never the signed bytes or their identity.
                JsonNode expectedTree=Ap2Crypto.parseJson(Ap2Crypto.jsonBytes(acp(expected)));
                if(!Arrays.equals(original,bytes(checkout.acpPayload()))||!authenticated.equals(expectedTree)) reject(Failure.INVALID_AUTHORITY);
                return expected;
            } catch(SignatureProvider.UnavailableException failure) { throw new CompositionException(Failure.CRYPTO_UNAVAILABLE); }
            catch(Ap2Exception failure) { throw map(failure); }
        }
    }
    private final class ReferenceIssuer implements AuthorityIssuer {
        @Override public Descriptor describe() { return descriptor("authority-issuer","closed-mandates"); }
        @Override public IssuedAuthority issue(IssuanceRequest request) {
            Objects.requireNonNull(request); AuthorityContext context=request.context();
            fixture(context.facts());
            profileLifetime(context);
            if(!context.issuer().equals(request.signer().issuer())) reject(Failure.INVALID_AUTHORITY);
            var checkout=new Ap2Claims.CheckoutMandate(context.checkout().checkoutJwt(),context.checkout().checkoutHash());
            var payment=new Ap2Claims.PaymentMandate(context.checkout().checkoutHash(),payee(context.facts()),
                new Ap2Claims.Amount(context.facts().minorUnits(),context.facts().currency()),instrument(context.facts()));
            return new IssuedAuthority(sign(context,request.signer(),context.checkoutAudience(),context.checkoutNonce(),checkout),
                sign(context,request.signer(),context.paymentAudience(),context.paymentNonce(),payment));
        }
        private String sign(AuthorityContext context,SigningIdentity signer,String audience,String nonce,Ap2Claims.Content content) {
            try {
                var envelope=new Ap2Claims.Envelope(context.issuer(),context.issuedAt().getEpochSecond(),context.expiresAt().getEpochSecond(),
                    audience,nonce,jwtIds.get(),content);
                return Ap2Crypto.sign(Ap2Crypto.jsonBytes(Ap2Claims.toJson(envelope)),signatures,signer.keyHandle(),signer.kid(),Ap2Crypto.MANDATE_TYPE)+"~";
            } catch(RuntimeException failure) { throw new CompositionException(Failure.SIGNING_UNAVAILABLE); }
        }
    }
    private final class ReferenceVerifier implements AuthorityVerifier {
        @Override public Descriptor describe() { return descriptor("authority-verifier","independent-signatures","role-bound","read-only"); }
        @Override public VerifiedAuthority verify(AuthorityRequest request,KeyResolver keys,Instant now) {
            Objects.requireNonNull(request); Objects.requireNonNull(keys); Objects.requireNonNull(now);
            try {
                AuthorityContext c=request.context(); fixture(c.facts());
                profileLifetime(c);
                if(now.isBefore(c.issuedAt())||!now.isBefore(c.expiresAt())||!now.isBefore(c.facts().expiresAt())) reject(Failure.EXPIRED_AUTHORITY);
                String cm=request.artifacts().checkoutMandate(), pm=request.artifacts().paymentMandate();
                var trust=new ArrayList<Ap2Engine.TrustKey>();
                trust.add(engineKey(resolve(keys,c.facts().scope(),c.checkout().merchantIssuer(),c.checkout().checkoutJwt(),MERCHANT_CHECKOUT,now)));
                trust.add(engineKey(resolve(keys,c.facts().scope(),c.issuer(),Ap2Crypto.issuerJwt(cm),CHECKOUT_MANDATE,now)));
                trust.add(engineKey(resolve(keys,c.facts().scope(),c.issuer(),Ap2Crypto.issuerJwt(pm),PAYMENT_MANDATE,now)));
                trust.add(engineKey(resolve(keys,c.facts().scope(),c.issuer(),Ap2Crypto.issuerJwt(pm),PROCESSOR_PAYMENT_MANDATE,now)));
                var engine=new Ap2Engine(trust,signatures);
                var expected=new Ap2Engine.ExpectedPayment(c.checkout().checkoutJwt(),c.checkout().merchantIssuer(),payee(c.facts()),
                    new Ap2Claims.Amount(c.facts().minorUnits(),c.facts().currency()),instrument(c.facts()));
                var merchant=new Ap2Engine.Context(Ap2Engine.Role.MERCHANT,c.issuer(),c.checkoutAudience(),c.checkoutNonce(),now.getEpochSecond());
                var cp=new Ap2Engine.Context(Ap2Engine.Role.CREDENTIAL_PROVIDER,c.issuer(),c.paymentAudience(),c.paymentNonce(),now.getEpochSecond());
                var mpp=new Ap2Engine.Context(Ap2Engine.Role.PAYMENT_PROCESSOR,c.issuer(),c.paymentAudience(),c.paymentNonce(),now.getEpochSecond());
                var checkout=engine.verifyCheckout(cm,c.checkout().checkoutJwt(),c.checkout().merchantIssuer(),merchant);
                var payment=engine.verifyPayment(pm,expected,cp);
                var processor=engine.verifyPayment(pm,expected,mpp);
                issuanceTime(cm,c); issuanceTime(pm,c);
                if(!checkout.checkoutHash().equals(c.checkout().checkoutHash())||!payment.checkoutHash().equals(checkout.checkoutHash())
                        ||!processor.mandateHash().equals(payment.mandateHash())) reject(Failure.INVALID_AUTHORITY);
                var authorizations=List.of(role("MERCHANT",checkout),role("CREDENTIAL_PROVIDER",payment),role("PAYMENT_PROCESSOR",processor));
                Instant expiry=authorizations.stream().map(RoleAuthorization::expiresAt).min(Instant::compareTo).orElseThrow();
                return new VerifiedAuthority(checkout.checkoutHash(),checkout.mandateHash(),payment.mandateHash(),authorizations,expiry);
            } catch(SignatureProvider.UnavailableException failure) { throw new CompositionException(Failure.CRYPTO_UNAVAILABLE); }
            catch(Ap2Exception failure) { throw map(failure); }
            catch(CompositionException failure) { throw failure; }
            catch(IllegalArgumentException failure) { throw new CompositionException(Failure.INVALID_AUTHORITY); }
        }
    }
    private static RoleAuthorization role(String role,Ap2Engine.Proof proof) {
        return new RoleAuthorization(role,proof.issuer(),proof.jwtId(),Instant.ofEpochSecond(proof.expiresAt()));
    }
    private static void profileLifetime(AuthorityContext context) {
        if(context.expiresAt().getEpochSecond()-context.issuedAt().getEpochSecond()>300)
            reject(Failure.UNSUPPORTED_CAPABILITY);
    }
    /** Called only after original-byte signature verification, never to establish authentication. */
    private static void issuanceTime(String mandate,AuthorityContext context) {
        String compact=Ap2Crypto.issuerJwt(mandate);
        var envelope=Ap2Claims.envelope(Ap2Crypto.parseJson(Ap2Crypto.decode(compact.split("\\.",-1)[1])));
        if(envelope.issuedAt()!=context.issuedAt().getEpochSecond()||envelope.expiresAt()!=context.expiresAt().getEpochSecond())
            reject(Failure.INVALID_AUTHORITY);
    }
}
