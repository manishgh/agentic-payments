package io.agenticpayments.composition;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agenticpayments.ap2.*;
import io.agenticpayments.crypto.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static io.agenticpayments.composition.Composition.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeCompositionTest {
    static final Instant NOW=Instant.parse("2026-11-06T10:00:00Z");
    static final class Fixture {
        final JcaSignatureProvider signer=new JcaSignatureProvider();
        final NativeComposition protocols=new NativeComposition(signer);
        final Scope scope=new Scope("native-contract",UUID.randomUUID());
        final Provisioning provision=new FixtureKeyProvisioner(signer).provision(scope,NOW);
        final CheckoutFacts facts=new CheckoutFacts(scope,UUID.randomUUID(),"merchant-demo",1,"EUR",1250,"fixture-credential-1",NOW.plusSeconds(900));
        final KeyResolver keys=(s,i,k,p,n)->provision.enrollments().stream().filter(x->x.scope().equals(s)&&x.issuer().equals(i)&&x.kid().equals(k)&&x.purpose().equals(p)).findFirst().orElse(null);
        final FrozenCheckout checkout=protocols.checkout().freeze(new CheckoutRequest(facts,provision.merchantSigner()));
        final AuthorityContext context=new AuthorityContext(facts,checkout,"customer-demo","fixture-grant-1",1,provision.tapSigner().issuer(),
            "merchant-audience","payment-audience",UUID.randomUUID().toString(),UUID.randomUUID().toString(),NOW,NOW.plusSeconds(300));
        final IssuedAuthority issued=protocols.issuer().issue(new IssuanceRequest(context,provision.tapSigner()));
        VerifiedAuthority verify(IssuedAuthority value) { return protocols.verifier().verify(new AuthorityRequest(context,value),keys,NOW); }
        String alteredPayment(java.util.function.Consumer<ObjectNode> mutation) {
            String[] parts=Ap2Crypto.issuerJwt(issued.paymentMandate()).split("\\.");
            ObjectNode payload=Ap2Crypto.parseJson(Ap2Crypto.decode(parts[1])); mutation.accept(payload);
            return Ap2Crypto.sign(Ap2Crypto.jsonBytes(payload),signer,provision.tapSigner().keyHandle(),provision.tapSigner().kid(),Ap2Crypto.MANDATE_TYPE)+"~";
        }
    }
    @Test void nativeArtifactsIndependentlyVerifyThreeRolesAndEmitExactAcpForPinnedSchemaCheck() throws Exception {
        Fixture f=new Fixture();
        assertEquals(f.facts,f.protocols.checkout().validate(f.facts,f.checkout,f.keys,NOW));
        VerifiedAuthority verified=f.verify(f.issued);
        assertEquals(f.checkout.checkoutHash(),verified.checkoutHash());
        assertEquals(List.of("MERCHANT","CREDENTIAL_PROVIDER","PAYMENT_PROCESSOR"),verified.authorizations().stream().map(RoleAuthorization::role).toList());
        assertEquals(verified.authorizations().get(1).jwtId(),verified.authorizations().get(2).jwtId());
        assertEquals(verified,f.verify(f.issued)); // Read-only verifier has no in-process replay consumption.
        Path output=Path.of("target","native-composition","checkout.json"); Files.createDirectories(output.getParent());
        Files.writeString(output,f.checkout.acpPayload(),StandardCharsets.UTF_8);
        assertFalse(f.context.toString().contains(f.checkout.checkoutJwt()));
        assertFalse(f.issued.toString().contains(f.issued.paymentMandate()));
        assertFalse(f.provision.toString().contains(f.provision.tapSigner().keyHandle()));
    }
    @Test void validlySignedWrongMoneyPayeeInstrumentAudienceNonceAndIssuanceTimeReject() {
        Fixture f=new Fixture();
        List<java.util.function.Consumer<ObjectNode>> mutations=List.of(
            p->((ObjectNode)p.get("delegate_payload").get(0).get("payment_amount")).put("amount",1251),
            p->((ObjectNode)p.get("delegate_payload").get(0).get("payee")).put("id","another-merchant"),
            p->((ObjectNode)p.get("delegate_payload").get(0).get("payment_instrument")).put("id","another-instrument"),
            p->p.put("aud","another-audience"),p->p.put("nonce","another-nonce"),p->p.put("iat",NOW.minusSeconds(1).getEpochSecond()),
            p->p.put("exp",NOW.plusSeconds(299).getEpochSecond()),p->p.put("unsupported",true));
        for(var mutation:mutations) assertThrows(CompositionException.class,()->f.verify(new IssuedAuthority(f.issued.checkoutMandate(),f.alteredPayment(mutation))));
    }
    @Test void exactSignedCheckoutIdentityRejectsResigningAndMalformedAcpEvenWithValidMerchantSignature() {
        Fixture f=new Fixture();
        String resigned=Ap2Crypto.sign(f.checkout.acpPayload().getBytes(StandardCharsets.UTF_8),f.signer,f.provision.merchantSigner().keyHandle(),
            f.provision.merchantSigner().kid(),Ap2Crypto.CHECKOUT_TYPE);
        assertNotEquals(resigned,f.checkout.checkoutJwt());
        FrozenCheckout replacement=frozen(f.checkout,f.checkout.acpPayload(),resigned);
        // Valid new checkout signature cannot replace the exact artifact approved by the old Checkout Mandate.
        AuthorityContext changed=new AuthorityContext(f.facts,replacement,f.context.subjectId(),f.context.grantId(),1,f.context.issuer(),
            f.context.checkoutAudience(),f.context.paymentAudience(),f.context.checkoutNonce(),f.context.paymentNonce(),NOW,NOW.plusSeconds(300));
        assertThrows(CompositionException.class,()->f.protocols.verifier().verify(new AuthorityRequest(changed,f.issued),f.keys,NOW));
        ObjectNode payload=Ap2Crypto.parseJson(f.checkout.acpPayload().getBytes(StandardCharsets.UTF_8));
        payload.put("surprise",true);
        String raw=new String(Ap2Crypto.jsonBytes(payload),StandardCharsets.UTF_8);
        String signed=Ap2Crypto.sign(raw.getBytes(StandardCharsets.UTF_8),f.signer,f.provision.merchantSigner().keyHandle(),f.provision.merchantSigner().kid(),Ap2Crypto.CHECKOUT_TYPE);
        assertThrows(CompositionException.class,()->f.protocols.checkout().validate(f.facts,frozen(f.checkout,raw,signed),f.keys,NOW));
        String duplicate=f.checkout.acpPayload().replaceFirst("\\{","{\"id\":\"duplicate\",");
        FrozenCheckout malformed=frozen(f.checkout,duplicate,f.checkout.checkoutJwt());
        assertThrows(CompositionException.class,()->f.protocols.checkout().validate(f.facts,malformed,f.keys,NOW));
    }
    static FrozenCheckout frozen(FrozenCheckout original,String payload,String jwt) {
        return new FrozenCheckout(original.sessionId(),payload,jwt,Ap2Crypto.hash(jwt),ArtifactIntegrity.digest(jwt.getBytes(StandardCharsets.UTF_8)),
            ArtifactIntegrity.digest(payload.getBytes(StandardCharsets.UTF_8)),original.merchantIssuer(),original.handlerId(),original.protocolVersion());
    }
    @Test void roleTrustScopeRevocationAndExpiryCheckedAgainAfterRestartWithoutSigner() {
        Fixture f=new Fixture(); NativeComposition restarted=new NativeComposition(new JcaSignatureProvider());
        assertEquals(f.verify(f.issued),restarted.verifier().verify(new AuthorityRequest(f.context,f.issued),f.keys,NOW.plusSeconds(2)));
        KeyResolver missingMpp=(s,i,k,p,n)->p.equals(PROCESSOR_PAYMENT_MANDATE)?null:f.keys.resolve(s,i,k,p,n);
        assertThrows(CompositionException.class,()->restarted.verifier().verify(new AuthorityRequest(f.context,f.issued),missingMpp,NOW));
        KeyResolver revoked=(s,i,k,p,n)-> { TrustKey key=f.keys.resolve(s,i,k,p,n);
            return key==null?null:new TrustKey(key.scope(),key.issuer(),key.kid(),key.purpose(),key.publicKeySpki(),key.validFrom(),key.validUntil(),key.version()+1,true); };
        assertThrows(CompositionException.class,()->restarted.verifier().verify(new AuthorityRequest(f.context,f.issued),revoked,NOW));
        assertThrows(CompositionException.class,()->restarted.verifier().verify(new AuthorityRequest(f.context,f.issued),f.keys,NOW.plusSeconds(300)));
        KeyResolver wrongScope=(s,i,k,p,n)-> { TrustKey key=f.keys.resolve(s,i,k,p,n);
            return key==null?null:new TrustKey(new Scope("another",s.runId()),key.issuer(),key.kid(),key.purpose(),key.publicKeySpki(),key.validFrom(),key.validUntil(),key.version(),false); };
        assertThrows(CompositionException.class,()->restarted.verifier().verify(new AuthorityRequest(f.context,f.issued),wrongScope,NOW));
    }
    @Test void genuineSignatureProviderReplacementIsUsedAndProfileRejectsAlgorithmDowngradeBeforeProvider() {
        Fixture f=new Fixture(); AtomicInteger verifications=new AtomicInteger();
        SignatureProvider decorated=new SignatureProvider() {
            public String contractVersion() { return "1"; }
            public Set<String> supportedSuites() { return Set.of("ES256","future-suite"); }
            public byte[] sign(String s,String k,byte[] b) { return f.signer.sign(s,k,b); }
            public boolean verify(String s,byte[] k,byte[] b,byte[] sig) { assertEquals("ES256",s); verifications.incrementAndGet(); return f.signer.verify(s,k,b,sig); }
        };
        var replacement=new NativeComposition(decorated);
        replacement.checkout().validate(f.facts,f.checkout,f.keys,NOW);
        replacement.verifier().verify(new AuthorityRequest(f.context,f.issued),f.keys,NOW);
        assertTrue(verifications.get()>=7);
        String[] pieces=Ap2Crypto.issuerJwt(f.issued.paymentMandate()).split("\\.");
        ObjectNode header=Ap2Crypto.parseJson(Ap2Crypto.decode(pieces[0]));header.put("alg","future-suite");
        String bad=Ap2Crypto.encode(Ap2Crypto.jsonBytes(header))+"."+pieces[1]+"."+pieces[2]+"~";
        int before=verifications.get();
        assertThrows(CompositionException.class,()->replacement.verifier().verify(new AuthorityRequest(f.context,new IssuedAuthority(f.issued.checkoutMandate(),bad)),f.keys,NOW));
        assertEquals(before,verifications.get());
        SignatureProvider unsupported=new SignatureProvider() {
            public String contractVersion(){return "1";} public Set<String> supportedSuites(){return Set.of("other");}
            public byte[] sign(String s,String k,byte[] b){throw new AssertionError();}
            public boolean verify(String s,byte[] k,byte[] b,byte[] sig){throw new AssertionError();}
        };
        assertThrows(Ap2Exception.class,()->new NativeComposition(unsupported));
    }
    @Test void checkoutReplacementAndDelegationPreserveOriginalBytesAndSeparateNativeVerifier() {
        Fixture f=new Fixture(); AtomicInteger delegated=new AtomicInteger();
        CheckoutProtocol original=f.protocols.checkout();
        CheckoutProtocol replacement=new CheckoutProtocol() {
            public Descriptor describe(){return original.describe();}
            public FrozenCheckout freeze(CheckoutRequest request){assertEquals(f.facts,request.facts()); delegated.incrementAndGet();return f.checkout;}
            public CheckoutFacts validate(CheckoutFacts expected,FrozenCheckout checkout,KeyResolver keys,Instant now){delegated.incrementAndGet();return original.validate(expected,checkout,keys,now);}
        };
        FrozenCheckout frozen=replacement.freeze(new CheckoutRequest(f.facts,f.provision.merchantSigner()));
        replacement.validate(f.facts,frozen,f.keys,NOW);
        assertSame(f.checkout,frozen); assertEquals(2,delegated.get());
        assertEquals(f.verify(f.issued),new NativeComposition(new JcaSignatureProvider()).verifier().verify(new AuthorityRequest(f.context,f.issued),f.keys,NOW));
        Descriptor incompatible=new Descriptor("checkout-protocol","2","future",Set.of(PROFILE),original.describe().capabilities(),"simulator-core");
        assertThrows(CompositionException.class,()->incompatible.require("checkout-protocol",PROFILE,"immutable-checkout"));
    }
    @Test void genericRecordsDoNotHardcodeReferenceCurrenciesOrNativeLifetimeButReferenceExplicitlyRefusesThem() {
        Fixture f=new Fixture();
        CheckoutFacts jpy=new CheckoutFacts(f.scope,f.facts.intentId(),"merchant-demo",1,"JPY",1250,"fixture-credential-1",NOW.plusSeconds(900));
        assertThrows(CompositionException.class,()->f.protocols.checkout().freeze(new CheckoutRequest(jpy,f.provision.merchantSigner())));
        AuthorityContext longer=new AuthorityContext(f.facts,f.checkout,"customer-demo","fixture-grant-1",1,f.context.issuer(),"merchant-audience","payment-audience","checkout","payment",NOW,NOW.plusSeconds(301));
        assertThrows(CompositionException.class,()->f.protocols.issuer().issue(new IssuanceRequest(longer,f.provision.tapSigner())));
    }
    @Test void signerFailuresAreSanitizedAndCannotProduceAnAcceptedArtifact() {
        Fixture f=new Fixture();
        SignatureProvider failing=new SignatureProvider() {
            public String contractVersion(){return "1";} public Set<String> supportedSuites(){return Set.of("ES256");}
            public byte[] sign(String s,String k,byte[] b){throw new IllegalStateException("PRIVATE_SIGNER_CANARY");}
            public boolean verify(String s,byte[] k,byte[] b,byte[] sig){return f.signer.verify(s,k,b,sig);}
        };
        NativeComposition replacement=new NativeComposition(failing);
        var checkoutFailure=assertThrows(CompositionException.class,()->replacement.checkout().freeze(new CheckoutRequest(f.facts,f.provision.merchantSigner())));
        var mandateFailure=assertThrows(CompositionException.class,()->replacement.issuer().issue(new IssuanceRequest(f.context,f.provision.tapSigner())));
        assertEquals(Failure.SIGNING_UNAVAILABLE,checkoutFailure.failure()); assertEquals(Failure.SIGNING_UNAVAILABLE,mandateFailure.failure());
        assertFalse(checkoutFailure.toString().contains("CANARY")); assertNull(checkoutFailure.getCause());
        // Existing artifacts remain verifiable without invoking a broken/missing signer.
        assertEquals(f.verify(f.issued),replacement.verifier().verify(new AuthorityRequest(f.context,f.issued),f.keys,NOW));
    }
}
