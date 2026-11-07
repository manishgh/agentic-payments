package io.agenticpayments.core;

import io.agenticpayments.application.*;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.composition.*;
import io.agenticpayments.infrastructure.*;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.UnaryOperator;
import static io.agenticpayments.composition.Composition.*;
import static io.agenticpayments.core.CoreTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real admission uses registered key/decorator contracts while retaining authoritative trust ordering. */
class CompositionRegistrationIntegrationTest {
    private static Ports withKeys(Ports base,KeyResolverFactory keys) {
        return new Ports(base.checkout(),base.issuer(),base.verifier(),base.provisioner(),base.artifacts(),keys);
    }
    private static Result quote(PaymentService service,Session session) {
        Result created=service.create(session,key(),new CreateCommand("merchant-demo","success",Composition.PROFILE,List.of("single-tender","exact-money")));
        return service.quote(session,uuid(created.body().get("intentId")),key(),new QuoteCommand(version(created),"cart-1250-eur",List.of(new Tender("simulator","synthetic-card","fixture-credential-1"))));
    }
    @Test void registeredKeyServiceDecoratorIsInvokedAtQuoteApprovalAndDispatch() throws Exception {
        DataSource ds=database();Session session=isolated(ds);Ports base=NativeComposition.ports(new JdbcSignedArtifactStore(ds));
        AtomicInteger binds=new AtomicInteger(),lookups=new AtomicInteger();
        KeyResolverFactory decorated=new KeyResolverFactory() {
            public Descriptor describe(){return base.keyResolvers().describe();}
            public KeyResolver bind(EnrollmentSnapshot snapshot){binds.incrementAndGet();KeyResolver delegate=base.keyResolvers().bind(snapshot);
                return (s,i,k,p,n)->{lookups.incrementAndGet();return delegate.resolve(s,i,k,p,n);};}
        };
        PaymentService service=PaymentServices.local(ds,new JdbcSimulatorAdapter(ds),withKeys(base,decorated));
        Result quoted=quote(service,session);int quotedCalls=lookups.get();assertTrue(quotedCalls>0);
        UUID id=uuid(quoted.body().get("intentId"));Result approved=service.approve(session,id,key(),approval(quoted));
        assertTrue(lookups.get()>quotedCalls);int approvedCalls=lookups.get();
        service.execute(session,id,key(),new ExecuteCommand(version(approved),uuid(approved.body().get("attemptId"))));
        for(int n=0;n<5&&service.runNext(session);n++){}
        assertTrue(lookups.get()>approvedCalls);assertTrue(binds.get()>2);assertEquals("SUCCEEDED",service.status(session,id).body().get("state"));
        assertEquals(1,scalar(ds,"SELECT count(*) FROM simulator_operation_journal WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId()));
    }
    @Test void registeredResolverCannotSubstituteScopeKeyVersionOrRevocationStateBeforeReservation() throws Exception {
        List<UnaryOperator<TrustKey>> mutations=List.of(
            k->new TrustKey(new Scope("another-tenant",k.scope().runId()),k.issuer(),k.kid(),k.purpose(),k.publicKeySpki(),k.validFrom(),k.validUntil(),k.version(),false),
            k->new TrustKey(k.scope(),k.issuer(),k.kid(),k.purpose(),"AA",k.validFrom(),k.validUntil(),k.version(),false),
            k->new TrustKey(k.scope(),k.issuer(),k.kid(),k.purpose(),k.publicKeySpki(),k.validFrom(),k.validUntil(),k.version()+1,false),
            k->new TrustKey(k.scope(),k.issuer(),k.kid(),k.purpose(),k.publicKeySpki(),k.validFrom(),k.validUntil(),k.version(),true));
        for(UnaryOperator<TrustKey> mutation:mutations) {
            DataSource ds=database();Session session=isolated(ds);Ports base=NativeComposition.ports(new JdbcSignedArtifactStore(ds));AtomicBoolean alter=new AtomicBoolean();
            KeyResolverFactory replacement=new KeyResolverFactory() {
                public Descriptor describe(){return base.keyResolvers().describe();}
                public KeyResolver bind(EnrollmentSnapshot snapshot){KeyResolver delegate=base.keyResolvers().bind(snapshot);
                    return (s,i,k,p,n)->{TrustKey value=delegate.resolve(s,i,k,p,n);return alter.get()?mutation.apply(value):value;};}
            };
            PaymentService service=PaymentServices.local(ds,new JdbcSimulatorAdapter(ds),withKeys(base,replacement));Result quoted=quote(service,session);alter.set(true);
            assertEquals("AUTHORITY_BINDING_INVALID",assertThrows(PaymentException.class,()->service.approve(session,uuid(quoted.body().get("intentId")),key(),approval(quoted))).code());
            assertEquals(0,scalar(ds,"SELECT count(*) FROM reservations WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId()));
            assertEquals(0,scalar(ds,"SELECT count(*) FROM native_replay_identities WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId()));
        }
    }
    private static CheckoutProtocol ownerDecorator(CheckoutProtocol delegate,AtomicBoolean conflicting) {
        return new CheckoutProtocol() {
            public Descriptor describe(){Descriptor d=delegate.describe();return new Descriptor(d.contract(),d.version(),d.implementation(),d.profiles(),d.capabilities(),conflicting.get()?"another-execution-owner":d.owner());}
            public FrozenCheckout freeze(CheckoutRequest request){return delegate.freeze(request);}
            public CheckoutFacts validate(CheckoutFacts facts,FrozenCheckout frozen,KeyResolver keys,Instant now){return delegate.validate(facts,frozen,keys,now);}
        };
    }
    @Test void conflictingOwnerIsRefusedBeforeIntentCreationAndRecheckedAtDispatch() throws Exception {
        DataSource ds=database();Session session=isolated(ds);Ports base=NativeComposition.ports(new JdbcSignedArtifactStore(ds));AtomicBoolean conflicting=new AtomicBoolean(true);
        Ports ports=new Ports(ownerDecorator(base.checkout(),conflicting),base.issuer(),base.verifier(),base.provisioner(),base.artifacts(),base.keyResolvers());
        PaymentService service=PaymentServices.local(ds,new JdbcSimulatorAdapter(ds),ports);
        assertEquals("UNSUPPORTED_CAPABILITY",assertThrows(PaymentException.class,()->quote(service,session)).code());
        assertEquals(0,scalar(ds,"SELECT count(*) FROM payment_intents WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId()));
        conflicting.set(false);Result quoted=quote(service,session);UUID id=uuid(quoted.body().get("intentId"));
        Result approved=service.approve(session,id,key(),approval(quoted));service.execute(session,id,key(),new ExecuteCommand(version(approved),uuid(approved.body().get("attemptId"))));
        service.runNext(session);assertEquals("READY",service.status(session,id).body().get("state"));
        conflicting.set(true);service.runNext(session);
        assertEquals("CANCELLED",service.status(session,id).body().get("state"));assertEquals("RELEASED",service.status(session,id).body().get("exposureState"));
        assertEquals(0,scalar(ds,"SELECT count(*) FROM simulator_operation_journal WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId()));
    }
}
