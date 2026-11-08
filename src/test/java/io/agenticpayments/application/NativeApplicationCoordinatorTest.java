package io.agenticpayments.application;

import io.agenticpayments.http.HttpFailureMapper;

import io.agenticpayments.application.NativeApplicationCoordinator.*;
import io.agenticpayments.composition.*;
import io.agenticpayments.crypto.JcaSignatureProvider;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import static io.agenticpayments.composition.Composition.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeApplicationCoordinatorTest {
    private static final Instant NOW=Instant.parse("2026-11-06T10:00:00Z");

    private static final class Fixture {
        final JcaSignatureProvider signatures=new JcaSignatureProvider();
        final NativeComposition protocols=new NativeComposition(signatures);
        final Scope scope=new Scope("coordinator-test",UUID.randomUUID());
        final Provisioning provision=new FixtureKeyProvisioner(signatures).provision(scope,NOW);
        final CheckoutFacts facts=new CheckoutFacts(scope,UUID.randomUUID(),"merchant-demo",1,"EUR",1250,
            "fixture-credential-1",NOW.plusSeconds(900));
        final KeyResolver keys=(s,i,k,p,n)->provision.enrollments().stream()
            .filter(x->x.scope().equals(s)&&x.issuer().equals(i)&&x.kid().equals(k)&&x.purpose().equals(p)).findFirst().orElse(null);
        final SignedArtifactStore store=new InMemoryArtifactStore();
        final FrozenCheckout checkout=protocols.checkout().freeze(new CheckoutRequest(facts,provision.merchantSigner()));
        final AuthorityContext context=new AuthorityContext(facts,checkout,"customer-demo","fixture-grant-1",1,
            provision.tapSigner().issuer(),"merchant-audience","payment-audience","checkout-nonce","payment-nonce",NOW,NOW.plusSeconds(300));
        NativeApplicationCoordinator coordinator(SignedArtifactStore artifacts,AuthorityVerifier verifier) {
            return new NativeApplicationCoordinator(protocols.checkout(),protocols.issuer(),verifier,artifacts);
        }
        NativeApplicationCoordinator coordinator() { return coordinator(store,protocols.verifier()); }
        StoredAuthority stage(NativeApplicationCoordinator coordinator) {
            return coordinator.issueAndRetainAuthority(new IssuanceRequest(context,provision.tapSigner()));
        }
    }

    private static SignedArtifactStore decorate(SignedArtifactStore base,AtomicInteger reads,AtomicInteger writes,
                                                UnaryOperator<Artifact> transform) {
        return new SignedArtifactStore() {
            public Descriptor describe() { return base.describe(); }
            public ArtifactReference put(Scope scope,Artifact artifact) { writes.incrementAndGet(); return base.put(scope,artifact); }
            public Artifact get(Scope scope,ArtifactReference reference) { reads.incrementAndGet(); return transform.apply(base.get(scope,reference)); }
        };
    }

    @Test void decoratedResponsibilityPortsPreserveExactStagingAndReadOnlyIndependentVerification() throws Exception {
        Fixture f=new Fixture();
        AtomicInteger reads=new AtomicInteger(),writes=new AtomicInteger(),freezes=new AtomicInteger(),validations=new AtomicInteger();
        AtomicInteger issuances=new AtomicInteger(),verifications=new AtomicInteger();
        SignedArtifactStore artifacts=decorate(f.store,reads,writes,UnaryOperator.identity());
        CheckoutProtocol checkout=new CheckoutProtocol() {
            public Descriptor describe() { return f.protocols.checkout().describe(); }
            public FrozenCheckout freeze(CheckoutRequest request) { freezes.incrementAndGet(); return f.protocols.checkout().freeze(request); }
            public CheckoutFacts validate(CheckoutFacts expected,FrozenCheckout value,KeyResolver keys,Instant now) {
                validations.incrementAndGet(); return f.protocols.checkout().validate(expected,value,keys,now);
            }
        };
        AuthorityIssuer issuer=new AuthorityIssuer() {
            public Descriptor describe() { return f.protocols.issuer().describe(); }
            public IssuedAuthority issue(IssuanceRequest request) { issuances.incrementAndGet(); return f.protocols.issuer().issue(request); }
        };
        AuthorityVerifier verifier=new AuthorityVerifier() {
            public Descriptor describe() { return f.protocols.verifier().describe(); }
            public VerifiedAuthority verify(AuthorityRequest request,KeyResolver keys,Instant now) {
                verifications.incrementAndGet(); return f.protocols.verifier().verify(request,keys,now);
            }
        };
        var coordinator=new NativeApplicationCoordinator(checkout,issuer,verifier,artifacts);
        var frozen=coordinator.freezeCheckout(new CheckoutRequest(f.facts,f.provision.merchantSigner()));
        coordinator.validateCheckout(f.facts,frozen,f.keys,NOW);
        StoredCheckout stored=coordinator.retainCheckout(f.scope,frozen);
        FrozenCheckout reloadedCheckout=coordinator.reloadCheckout(f.scope,stored,frozen.checkoutDigest());
        assertEquals(frozen,reloadedCheckout);
        coordinator.validateCheckout(f.facts,reloadedCheckout,f.keys,NOW);
        var context=new AuthorityContext(f.facts,reloadedCheckout,f.context.subjectId(),f.context.grantId(),1,
            f.context.issuer(),f.context.checkoutAudience(),f.context.paymentAudience(),f.context.checkoutNonce(),f.context.paymentNonce(),NOW,f.context.expiresAt());
        StoredAuthority authority=coordinator.issueAndRetainAuthority(new IssuanceRequest(context,f.provision.tapSigner()));
        var reloaded=coordinator.reloadAuthority(f.scope,authority);
        var verified=coordinator.verifyAuthority(context,reloaded,f.keys,NOW);
        assertEquals(frozen.checkoutHash(),verified.checkoutHash());
        assertEquals(3,verified.authorizations().size());
        // Independent oracle for the persisted pre-extraction digest framing, not the coordinator helper itself.
        String legacyPairDigest="sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
            (authority.checkoutMandate().digest()+"\n"+authority.paymentMandate().digest()).getBytes(StandardCharsets.UTF_8)));
        assertEquals(legacyPairDigest,coordinator.authorityDigest(authority));
        coordinator.validateAuthorityDigest(authority,legacyPairDigest);
        assertEquals(verified,coordinator.verifyAuthority(context,coordinator.reloadAuthority(f.scope,authority),f.keys,NOW));
        assertEquals(1,freezes.get()); assertEquals(2,validations.get()); assertEquals(1,issuances.get());
        assertEquals(2,verifications.get()); assertEquals(4,writes.get()); assertEquals(6,reads.get());
        assertFalse(stored.toString().contains(stored.checkoutJwt().id()));
        assertFalse(authority.toString().contains(authority.checkoutNonce()));
        assertFalse(authority.toString().contains(authority.issuer()));
    }

    @Test void durableCheckoutDigestMismatchRejectsBeforeArtifactRetrieval() {
        Fixture f=new Fixture(); AtomicInteger reads=new AtomicInteger();
        var coordinator=f.coordinator(decorate(f.store,reads,new AtomicInteger(),UnaryOperator.identity()),f.protocols.verifier());
        var stored=coordinator.retainCheckout(f.scope,f.checkout);
        assertInvalid(()->coordinator.reloadCheckout(f.scope,stored,"another-checkout"));
        assertEquals(0,reads.get());
    }

    @Test void replacementStoreCannotSubstituteBytesOrReferenceMetadata() {
        Fixture f=new Fixture(); var stored=f.coordinator().retainCheckout(f.scope,f.checkout);
        List<UnaryOperator<Artifact>> substitutions=List.of(
            a->new Artifact("SUBSTITUTED_ARTIFACT".getBytes(StandardCharsets.UTF_8),a.mediaType(),a.profile()),
            a->new Artifact(a.bytes(),"application/other",a.profile()),
            a->new Artifact(a.bytes(),a.mediaType(),"another-profile"));
        for(var substitution:substitutions) {
            var coordinator=f.coordinator(decorate(f.store,new AtomicInteger(),new AtomicInteger(),substitution),f.protocols.verifier());
            var error=assertThrows(CompositionException.class,()->coordinator.reloadCheckout(f.scope,stored,f.checkout.checkoutDigest()));
            assertEquals(Failure.ARTIFACT_CONFLICT,error.failure());
        }
        ArtifactReference original=stored.acpPayload();
        ArtifactReference wrongLength=new ArtifactReference(original.id(),original.digest(),original.length()+1,original.mediaType(),original.profile());
        assertEquals(Failure.ARTIFACT_CONFLICT,assertThrows(CompositionException.class,
            ()->f.coordinator().reloadCheckout(f.scope,withPayload(stored,wrongLength),f.checkout.checkoutDigest())).failure());
    }

    @Test void foreignProfileRejectsBeforeStoreAndValidDigestDoesNotPermitMalformedUtf8() {
        Fixture f=new Fixture(); AtomicInteger reads=new AtomicInteger();
        var coordinator=f.coordinator(decorate(f.store,reads,new AtomicInteger(),UnaryOperator.identity()),f.protocols.verifier());
        StoredCheckout stored=coordinator.retainCheckout(f.scope,f.checkout);
        ArtifactReference original=stored.acpPayload();
        var foreign=new ArtifactReference(original.id(),original.digest(),original.length(),original.mediaType(),"foreign-profile");
        assertInvalid(()->coordinator.reloadCheckout(f.scope,withPayload(stored,foreign),f.checkout.checkoutDigest()));
        assertEquals(0,reads.get());
        var malformed=f.store.put(f.scope,new Artifact(new byte[]{(byte)0xC3,0x28},"application/json",PROFILE));
        assertInvalid(()->coordinator.reloadCheckout(f.scope,withPayload(stored,malformed),f.checkout.checkoutDigest()));
        assertEquals(1,reads.get());
    }

    @Test void issuedAuthorityMustBeReloadedFromImmutableStorageWithoutUsingIssuerReturnAsProof() {
        Fixture f=new Fixture(); var stored=f.stage(f.coordinator());
        AtomicInteger reads=new AtomicInteger();
        var broken=f.coordinator(decorate(f.store,reads,new AtomicInteger(),a->new Artifact(
            "ALTERED_MANDATE".getBytes(StandardCharsets.UTF_8),a.mediaType(),a.profile())),f.protocols.verifier());
        assertEquals(Failure.ARTIFACT_CONFLICT,assertThrows(CompositionException.class,
            ()->broken.reloadAuthority(f.scope,stored)).failure());
        assertEquals(1,reads.get());
        var readOnly=new NativeApplicationCoordinator(f.protocols.checkout(),new AuthorityIssuer() {
            public Descriptor describe() { return f.protocols.issuer().describe(); }
            public IssuedAuthority issue(IssuanceRequest request) { throw new AssertionError("Reverification must not issue"); }
        },f.protocols.verifier(),f.store);
        assertEquals(3,readOnly.verifyAuthority(f.context,readOnly.reloadAuthority(f.scope,stored),f.keys,NOW).authorizations().size());
        assertInvalid(()->readOnly.validateAuthorityDigest(stored,"incorrect-persisted-pair-digest"));
    }

    @Test void verifierOutputMustSatisfyCompleteRoleIssuerHashAndExpiryPolicy() {
        Fixture f=new Fixture(); var coordinator=f.coordinator();
        var issued=coordinator.reloadAuthority(f.scope,f.stage(coordinator));
        VerifiedAuthority valid=coordinator.verifyAuthority(f.context,issued,f.keys,NOW);
        var roles=valid.authorizations();
        List<VerifiedAuthority> invalid=List.of(
            withRoles(valid,roles.subList(0,2)),
            withRoles(valid,List.of(roles.get(0),roles.get(1),roles.get(1))),
            withRoles(valid,List.of(roles.get(0),roles.get(1),roles.get(2),new RoleAuthorization("EXTRA",f.context.issuer(),"extra",f.context.expiresAt()))),
            withRoles(valid,List.of(new RoleAuthorization("MERCHANT","another-issuer","id",f.context.expiresAt()),roles.get(1),roles.get(2))),
            withRoles(valid,List.of(new RoleAuthorization("MERCHANT",f.context.issuer(),"id",NOW),roles.get(1),roles.get(2))),
            withRoles(valid,List.of(new RoleAuthorization("MERCHANT",f.context.issuer(),"id",f.context.expiresAt().plusSeconds(1)),roles.get(1),roles.get(2))),
            new VerifiedAuthority("wrong-checkout-hash",valid.checkoutMandateHash(),valid.paymentMandateHash(),roles,valid.expiresAt()),
            new VerifiedAuthority(valid.checkoutHash(),valid.checkoutMandateHash(),valid.paymentMandateHash(),roles,NOW),
            new VerifiedAuthority(valid.checkoutHash(),valid.checkoutMandateHash(),valid.paymentMandateHash(),roles,f.context.expiresAt().plusSeconds(1)));
        for(var value:invalid) {
            AuthorityVerifier replacement=new AuthorityVerifier() {
                public Descriptor describe() { return f.protocols.verifier().describe(); }
                public VerifiedAuthority verify(AuthorityRequest request,KeyResolver keys,Instant now) { return value; }
            };
            assertInvalid(()->f.coordinator(f.store,replacement).verifyAuthority(f.context,issued,f.keys,NOW));
        }
    }

    private static StoredCheckout withPayload(StoredCheckout stored,ArtifactReference payload) {
        return new StoredCheckout(stored.sessionId(),payload,stored.checkoutJwt(),stored.checkoutHash(),stored.checkoutDigest(),
            stored.payloadDigest(),stored.merchantIssuer(),stored.handlerId(),stored.protocolVersion());
    }
    private static VerifiedAuthority withRoles(VerifiedAuthority value,List<RoleAuthorization> roles) {
        return new VerifiedAuthority(value.checkoutHash(),value.checkoutMandateHash(),value.paymentMandateHash(),roles,value.expiresAt());
    }
    private static void assertInvalid(org.junit.jupiter.api.function.Executable action) {
        PaymentException error=assertThrows(PaymentException.class,action);
        assertEquals(422,HttpFailureMapper.map(error).status()); assertEquals("AUTHORITY_BINDING_INVALID",error.code());
    }
}
