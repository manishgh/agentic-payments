package io.agenticpayments.composition;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agenticpayments.ap2.Ap2Crypto;
import io.agenticpayments.application.CheckoutConfigurationResolver.ConfigurationIdentity;
import io.agenticpayments.application.NativeApplicationCoordinator;
import io.agenticpayments.application.PaymentException;
import io.agenticpayments.checkout.CheckoutSessions;
import io.agenticpayments.crypto.JcaSignatureProvider;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import static io.agenticpayments.composition.Composition.*;
import static org.junit.jupiter.api.Assertions.*;

class ObservedCheckoutProtocolTest {
    static final Instant NOW=Instant.parse("2026-11-06T10:00:00Z");
    static final class Fixture {
        final JcaSignatureProvider signatures=new JcaSignatureProvider();
        final NativeComposition protocols=new NativeComposition(signatures);
        final CheckoutProtocol checkout=protocols.externalCheckout();
        final Scope scope=new Scope("observed-test",UUID.randomUUID());
        final Provisioning provision=new FixtureKeyProvisioner(signatures).provision(scope,NOW);
        final CheckoutFacts facts=new CheckoutFacts(scope,UUID.randomUUID(),"merchant-demo",1,"USD",1750,"fixture-credential-1",NOW.plusSeconds(300));
        final CheckoutSessions.Binding binding=new CheckoutSessions.Binding(scope.tenantId(),"agent-demo","merchant-demo","synthetic");
        final ConfigurationIdentity configuration=new ConfigurationIdentity(binding,"v1","v1","synthetic-transport","1","a".repeat(64));
        final KeyResolver keys=(s,i,k,p,n)->provision.enrollments().stream().filter(x->x.scope().equals(s)&&x.issuer().equals(i)&&x.kid().equals(k)&&x.purpose().equals(p)).findFirst().orElse(null);
        ObjectNode tree() {
            var remoteFacts=new CheckoutFacts(scope,facts.intentId(),"merchant-demo",1,"USD",1750,"fixture-credential-1",NOW.plusSeconds(900));
            var tree=NativeComposition.acp(remoteFacts); tree.put("id","cs_unit_observation");
            ((ObjectNode)tree.get("line_items").get(0).get("item")).put("id","demo-item-alt"); return tree;
        }
        byte[] bytes() { return (" \n"+new String(Ap2Crypto.jsonBytes(tree()),StandardCharsets.UTF_8)+"\n ").getBytes(StandardCharsets.UTF_8); }
        ObservedCheckout observation(byte[] bytes,Instant observedAt) {
            var document=new CheckoutSessions.Document(new CheckoutSessions.Session(binding,"cs_unit_observation","usd"),"ready_for_payment",ACP_VERSION,bytes);
            return new ObservedCheckout(scope,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),2,configuration,observedAt,document,bytes);
        }
        ObservedCheckout observation() { return observation(bytes(),NOW); }
    }
    @Test void exactOriginalBytesAndDerivedPriceSurviveLocalAttestationAndIndependentAp2Verification() {
        var f=new Fixture(); var observed=f.observation();
        var terms=f.checkout.inspectObserved(observed,NOW);
        assertEquals(1750,terms.minorUnits()); assertEquals("USD",terms.currency());
        var frozen=f.checkout.freezeObserved(new ObservedCheckoutRequest(observed,f.facts,f.provision.merchantSigner()));
        assertArrayEquals(observed.bytes(),frozen.acpPayload().getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(observed.bytes(),Ap2Crypto.decode(frozen.checkoutJwt().split("\\.")[1]));
        assertEquals(f.facts,f.checkout.validate(f.facts,frozen,f.keys,NOW));
        var context=new AuthorityContext(f.facts,frozen,"customer-demo","fixture-grant-usd",1,f.provision.tapSigner().issuer(),"merchant-demo","processor-demo","nonce-c","nonce-p",NOW,NOW.plusSeconds(300));
        var authority=f.protocols.issuer().issue(new IssuanceRequest(context,f.provision.tapSigner()));
        assertEquals(3,f.protocols.verifier().verify(new AuthorityRequest(context,authority),f.keys,NOW).authorizations().size());
        byte[] copy=observed.bytes(); copy[0]='x'; assertNotEquals('x',observed.bytes()[0]);
        assertFalse(observed.toString().contains("cs_unit_observation"));
    }
    @Test void strictClosedPayloadRejectsUnknownFieldsWrongNestedTypesAndInconsistentMoney() {
        var f=new Fixture();
        List<Consumer<ObjectNode>> mutations=List.of(t->t.put("metadata","unsupported"),t->t.put("status","completed"),
                t->((ObjectNode)t.get("totals").get(0)).put("amount",1751),
                t->((ObjectNode)t.get("line_items").get(0)).put("quantity",1.0),
                t->((ObjectNode)t.get("line_items").get(0).get("item")).put("unit_amount","1750"),
                t->((ObjectNode)t.get("line_items").get(0).get("item")).put("unit_amount",Long.MAX_VALUE),
                t->((ObjectNode)t.get("capabilities").get("payment").get("handlers").get(0)).put("requires_delegate_payment",true));
        for(var mutate:mutations) {
            var tree=f.tree(); mutate.accept(tree);
            assertThrows(CompositionException.class,()->f.checkout.inspectObserved(f.observation(Ap2Crypto.jsonBytes(tree),NOW),NOW));
        }
    }
    @Test void duplicateMembersAndInvalidUtf8CannotBecomeAuthority() {
        var f=new Fixture();
        byte[] duplicate=new String(f.bytes(),StandardCharsets.UTF_8).replaceFirst("\\{","{\"currency\":\"usd\",").getBytes(StandardCharsets.UTF_8);
        assertThrows(CompositionException.class,()->f.checkout.inspectObserved(f.observation(duplicate,NOW),NOW));
        assertThrows(CompositionException.class,()->f.checkout.inspectObserved(f.observation(new byte[]{(byte)0xc3,0x28},NOW),NOW));
    }
    @Test void exactFreshnessAndRemoteExpiryBoundariesRejectWithoutRefreshingObservationTime() {
        var f=new Fixture();
        assertEquals(Failure.EXPIRED_AUTHORITY,assertThrows(CompositionException.class,()->f.checkout.inspectObserved(f.observation(),NOW.plusSeconds(300))).failure());
        assertEquals(Failure.EXPIRED_AUTHORITY,assertThrows(CompositionException.class,()->f.checkout.inspectObserved(f.observation(f.bytes(),NOW.plusSeconds(1)),NOW)).failure());
        var tree=f.tree(); tree.put("expires_at",NOW.toString());
        assertEquals(Failure.EXPIRED_AUTHORITY,assertThrows(CompositionException.class,()->f.checkout.inspectObserved(f.observation(Ap2Crypto.jsonBytes(tree),NOW),NOW)).failure());
    }
    @Test void oldProtocolImplementationsAndLocalFreezeRemainExplicitlyUnsupported() {
        var f=new Fixture(); var nativeCheckout=f.protocols.checkout();
        assertThrows(CompositionException.class,()->nativeCheckout.inspectObserved(f.observation(),NOW));
        assertThrows(CompositionException.class,()->nativeCheckout.freezeObserved(new ObservedCheckoutRequest(f.observation(),f.facts,f.provision.merchantSigner())));
        assertThrows(CompositionException.class,()->f.checkout.freeze(new CheckoutRequest(f.facts,f.provision.merchantSigner())));
        assertThrows(CompositionException.class,()->f.checkout.describe().require("checkout-protocol",PROFILE,"local-freeze"));
    }
    @Test void normalizedTreeNeverReplacesWhitespaceOrSignedByteIdentity() {
        var f=new Fixture(); var a=f.observation(); var b=f.observation(Ap2Crypto.jsonBytes(f.tree()),NOW);
        var first=f.checkout.freezeObserved(new ObservedCheckoutRequest(a,f.facts,f.provision.merchantSigner()));
        var second=f.checkout.freezeObserved(new ObservedCheckoutRequest(b,f.facts,f.provision.merchantSigner()));
        assertNotEquals(first.payloadDigest(),second.payloadDigest()); assertNotEquals(first.checkoutDigest(),second.checkoutDigest());
        assertEquals(f.checkout.inspectObserved(a,NOW),f.checkout.inspectObserved(b,NOW));
    }
    @Test void coordinatorChecksOutputDigestsAndExactPayloadEvenWhenAProtocolDecoratorReturnsSuccess() {
        var f=new Fixture(); var observation=f.observation(); var terms=f.checkout.inspectObserved(observation,NOW);
        var valid=f.checkout.freezeObserved(new ObservedCheckoutRequest(observation,f.facts,f.provision.merchantSigner()));
        var coordinator=new NativeApplicationCoordinator(f.checkout,f.protocols.issuer(),f.protocols.verifier(),new InMemoryArtifactStore(),EXTERNAL_PROFILE);
        coordinator.validateObservedBytes(observation,terms,f.facts,valid);
        var forged=new FrozenCheckout(valid.sessionId(),valid.acpPayload(),valid.checkoutJwt(),valid.checkoutHash(),"sha256:"+"a".repeat(64),
                valid.payloadDigest(),valid.merchantIssuer(),valid.handlerId(),valid.protocolVersion());
        assertEquals("AUTHORITY_BINDING_INVALID",assertThrows(PaymentException.class,
                ()->coordinator.validateObservedBytes(observation,terms,f.facts,forged)).code());
        var altered=new FrozenCheckout(valid.sessionId(),valid.acpPayload()+" ",valid.checkoutJwt(),valid.checkoutHash(),valid.checkoutDigest(),
                valid.payloadDigest(),valid.merchantIssuer(),valid.handlerId(),valid.protocolVersion());
        assertThrows(PaymentException.class,()->coordinator.validateObservedBytes(observation,terms,f.facts,altered));
    }
    @Test void currentTrustRevocationAndExpiryKeepAdmissionOwnerFailureCategories() {
        var f=new Fixture();
        var frozen=f.checkout.freezeObserved(new ObservedCheckoutRequest(f.observation(),f.facts,f.provision.merchantSigner()));
        for(var code:List.of("AUTHORITY_REVOKED","AUTHORITY_EXPIRED")) {
            int status=code.equals("AUTHORITY_REVOKED")?409:410;
            KeyResolver rejecting=(s,i,k,p,n)->{throw new PaymentException(status,code);};
            assertEquals(code,assertThrows(PaymentException.class,()->f.checkout.validate(f.facts,frozen,rejecting,NOW)).code());
        }
    }
    @Test void expiryRequiresStrictFourDigitUtcCalendarAndNoInstantParserNormalization() {
        var f=new Fixture();
        for(String expiry:List.of("+12026-11-06T10:15:00Z","2026-11-06T24:00:00Z","2026-11-06T10:15:60Z",
                "2026-11-06T10:60:00Z","2026-02-30T10:15:00Z","2026-11-06T10:15:00+00:00",
                "2026-11-06t10:15:00z","2026-11-06T10:15Z","2026-11-06T10:15:00.Z")) {
            var tree=f.tree(); tree.put("expires_at",expiry);
            assertEquals(Failure.INVALID_AUTHORITY,assertThrows(CompositionException.class,
                    ()->f.checkout.inspectObserved(f.observation(Ap2Crypto.jsonBytes(tree),NOW),NOW)).failure(),expiry);
        }
    }
    @Test void unrepresentableExpiryRejectsBeforeSigningWhileExactMicrosecondTextRemainsOriginal() {
        var f=new Fixture();
        for(String expiry:List.of("2026-11-06T10:01:00.000000001Z","2026-11-06T10:01:00.1234567Z")) {
            var tree=f.tree(); tree.put("expires_at",expiry);
            var observation=f.observation(Ap2Crypto.jsonBytes(tree),NOW);
            assertEquals(Failure.UNSUPPORTED_CAPABILITY,assertThrows(CompositionException.class,
                    ()->f.checkout.inspectObserved(observation,NOW)).failure());
            assertEquals(Failure.UNSUPPORTED_CAPABILITY,assertThrows(CompositionException.class,
                    ()->f.checkout.freezeObserved(new ObservedCheckoutRequest(observation,f.facts,f.provision.merchantSigner()))).failure());
        }
        var tree=f.tree(); tree.put("expires_at","2026-11-06T10:01:00.123456000Z");
        byte[] bytes=Ap2Crypto.jsonBytes(tree); var observation=f.observation(bytes,NOW);
        var terms=f.checkout.inspectObserved(observation,NOW);
        var facts=new CheckoutFacts(f.scope,f.facts.intentId(),"merchant-demo",1,"USD",1750,"fixture-credential-1",terms.expiresAt());
        var frozen=f.checkout.freezeObserved(new ObservedCheckoutRequest(observation,facts,f.provision.merchantSigner()));
        assertArrayEquals(bytes,frozen.acpPayload().getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(bytes,Ap2Crypto.decode(frozen.checkoutJwt().split("\\.")[1]));
        assertEquals(123456000,terms.expiresAt().getNano());
    }
}
