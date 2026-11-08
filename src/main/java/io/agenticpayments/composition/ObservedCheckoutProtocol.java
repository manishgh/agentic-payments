package io.agenticpayments.composition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agenticpayments.ap2.*;
import io.agenticpayments.crypto.SignatureProvider;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static io.agenticpayments.composition.Composition.*;

/** Closed synthetic external profile. Its signature is a local attestation, never merchant delegation. */
public final class ObservedCheckoutProtocol implements CheckoutProtocol {
    private static final String HANDLER="synthetic-handler-1";
    private static final String UTC_TIMESTAMP="[0-9]{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\\.[0-9]{1,9})?Z";
    private final SignatureProvider signatures;
    public ObservedCheckoutProtocol(SignatureProvider signatures) { Ap2Crypto.requireProvider(signatures); this.signatures=signatures; }
    @Override public Descriptor describe() {
        return new Descriptor("checkout-protocol",VERSION,"synthetic-observed-checkout",Set.of(EXTERNAL_PROFILE),
            Set.of("immutable-checkout","exact-money","exact-observed-checkout","local-synthetic-attestation",LOCAL_CALLBACKS),"simulator-core");
    }
    @Override public FrozenCheckout freeze(CheckoutRequest request) { throw new CompositionException(Failure.UNSUPPORTED_CAPABILITY); }
    @Override public ObservedTerms inspectObserved(ObservedCheckout observation,Instant now) {
        Objects.requireNonNull(observation); Objects.requireNonNull(now);
        var terms=terms(observation.bytes());
        var doc=observation.document();
        if(!Arrays.equals(doc.bytes(),observation.bytes())||!observation.scope().tenantId().equals(observation.configuration().binding().tenantId())
                ||!observation.configuration().binding().equals(doc.session().binding())
                ||!terms.sessionId().equals(doc.session().id())||!terms.currency().toLowerCase(Locale.ROOT).equals(doc.session().currency())
                ||!"ready_for_payment".equals(doc.status())||!terms.protocolVersion().equals(doc.observedProtocolVersion())) invalid();
        if(now.isBefore(observation.observedAt())||!now.isBefore(observation.observedAt().plusSeconds(300))
                ||!now.isBefore(terms.expiresAt())) throw new CompositionException(Failure.EXPIRED_AUTHORITY);
        return terms;
    }
    @Override public FrozenCheckout freezeObserved(ObservedCheckoutRequest request) {
        var observation=request.observation(); var facts=request.facts();
        var terms=inspectObserved(observation,observation.observedAt());
        Instant deadline=Collections.min(List.of(observation.observedAt().plusSeconds(300),terms.expiresAt()));
        if(!facts.scope().equals(observation.scope())||!facts.merchantId().equals(observation.configuration().binding().merchantId())
                ||!facts.currency().equals(terms.currency())||facts.minorUnits()!=terms.minorUnits()
                ||!facts.expiresAt().equals(deadline)||!"fixture-credential-1".equals(facts.credentialReference())) invalid();
        String jwt;
        try { jwt=Ap2Crypto.sign(observation.bytes(),signatures,request.signer().keyHandle(),request.signer().kid(),Ap2Crypto.CHECKOUT_TYPE); }
        catch(RuntimeException failure) { throw new CompositionException(Failure.SIGNING_UNAVAILABLE); }
        return new FrozenCheckout(terms.sessionId(),new String(observation.bytes(),StandardCharsets.UTF_8),jwt,Ap2Crypto.hash(jwt),
                ArtifactIntegrity.digest(jwt.getBytes(StandardCharsets.UTF_8)),ArtifactIntegrity.digest(observation.bytes()),
                request.signer().issuer(),terms.handlerId(),terms.protocolVersion());
    }
    @Override public CheckoutFacts validate(CheckoutFacts expected,FrozenCheckout checkout,KeyResolver keys,Instant now) {
        try {
            var terms=terms(checkout.acpPayload().getBytes(StandardCharsets.UTF_8));
            if(!now.isBefore(expected.expiresAt())) throw new CompositionException(Failure.EXPIRED_AUTHORITY);
            if(!"merchant-demo".equals(expected.merchantId())||!"fixture-credential-1".equals(expected.credentialReference())
                    ||!expected.currency().equals(terms.currency())||expected.minorUnits()!=terms.minorUnits()
                    ||expected.expiresAt().isAfter(terms.expiresAt())||!terms.sessionId().equals(checkout.sessionId())
                    ||!terms.protocolVersion().equals(checkout.protocolVersion())||!terms.handlerId().equals(checkout.handlerId())
                    ||!Ap2Crypto.hash(checkout.checkoutJwt()).equals(checkout.checkoutHash())
                    ||!ArtifactIntegrity.digest(checkout.checkoutJwt().getBytes(StandardCharsets.UTF_8)).equals(checkout.checkoutDigest())
                    ||!ArtifactIntegrity.digest(checkout.acpPayload().getBytes(StandardCharsets.UTF_8)).equals(checkout.payloadDigest())) invalid();
            String kid=Ap2Crypto.protectedHeader(checkout.checkoutJwt()).get("kid").textValue();
            var key=keys.resolve(expected.scope(),checkout.merchantIssuer(),kid,MERCHANT_CHECKOUT,now);
            if(key==null||!expected.scope().equals(key.scope())||!checkout.merchantIssuer().equals(key.issuer())||!kid.equals(key.kid())
                    ||!MERCHANT_CHECKOUT.equals(key.purpose())||key.revoked()||now.isBefore(key.validFrom())||!now.isBefore(key.validUntil()))
                throw new CompositionException(Failure.UNTRUSTED_KEY);
            Ap2Crypto.verify(checkout.checkoutJwt(),Ap2Crypto.publicKey(key.publicKeySpki()),key.kid(),Ap2Crypto.CHECKOUT_TYPE,signatures);
            if(!Arrays.equals(Ap2Crypto.decode(checkout.checkoutJwt().split("\\.",-1)[1]),checkout.acpPayload().getBytes(StandardCharsets.UTF_8))) invalid();
            return expected;
        } catch(SignatureProvider.UnavailableException error) { throw new CompositionException(Failure.CRYPTO_UNAVAILABLE); }
        catch(CompositionException error) { throw error; }
        catch(Ap2Exception error) { throw new CompositionException(switch(error.code()) {
            case EXPIRED_AUTHORITY -> Failure.EXPIRED_AUTHORITY;
            case UNTRUSTED_KEY,INVALID_KEY -> Failure.UNTRUSTED_KEY;
            case UNSUPPORTED_PROFILE -> Failure.UNSUPPORTED_CAPABILITY;
            default -> Failure.INVALID_AUTHORITY;
        }); }
        catch(IllegalArgumentException error) { throw new CompositionException(Failure.INVALID_AUTHORITY); }
    }
    private static ObservedTerms terms(byte[] bytes) {
        try {
            ObjectNode root=Ap2Crypto.parseJson(bytes);
            String session=text(root,"id"), currency=text(root,"currency"), expiry=text(root,"expires_at");
            if(!session.matches("cs_[A-Za-z0-9_-]{1,124}")||!Set.of("usd","eur").contains(currency)||!expiry.matches(UTC_TIMESTAMP)) invalid();
            Instant expiresAt=Instant.parse(expiry);
            // PostgreSQL stores exact microseconds. Reject before permanent sealing; never round signed input.
            if(expiresAt.getNano()%1000!=0) throw new CompositionException(Failure.UNSUPPORTED_CAPABILITY);
            JsonNode lines=root.get("line_items");
            if(lines==null||!lines.isArray()||lines.size()!=1) invalid();
            JsonNode item=lines.get(0).get("item"); if(item==null||!item.isObject()) invalid();
            String itemId=text(item,"id");
            if(!Set.of("demo-item","demo-item-alt").contains(itemId)) throw new CompositionException(Failure.UNSUPPORTED_CAPABILITY);
            JsonNode amount=item.get("unit_amount");
            if(amount==null||!amount.isIntegralNumber()||!amount.canConvertToLong()||amount.longValue()<1||amount.longValue()>999999999999L) invalid();
            long minor=amount.longValue();
            var facts=new CheckoutFacts(new Scope("validation",new UUID(0,0)),new UUID(0,0),"merchant-demo",1,
                    currency.toUpperCase(Locale.ROOT),minor,"fixture-credential-1",expiresAt);
            ObjectNode expected=NativeComposition.acp(facts); expected.put("id",session);
            ((ObjectNode)expected.get("line_items").get(0).get("item")).put("id",itemId);
            // Preserve the original RFC3339 text. Parsing never supplies replacement signed bytes.
            expected.put("expires_at",expiry);
            if(!root.path("capabilities").equals(expected.path("capabilities"))) throw new CompositionException(Failure.UNSUPPORTED_CAPABILITY);
            if(!root.equals(Ap2Crypto.parseJson(Ap2Crypto.jsonBytes(expected)))) invalid();
            return new ObservedTerms(session,ACP_VERSION,HANDLER,currency.toUpperCase(Locale.ROOT),minor,expiresAt);
        } catch(CompositionException error) { throw error; }
        catch(RuntimeException error) { throw new CompositionException(Failure.INVALID_AUTHORITY); }
    }
    private static String text(JsonNode root,String field) {
        JsonNode value=root.get(field); if(value==null||!value.isTextual()) invalid(); return value.textValue();
    }
    private static void invalid() { throw new CompositionException(Failure.INVALID_AUTHORITY); }
}
