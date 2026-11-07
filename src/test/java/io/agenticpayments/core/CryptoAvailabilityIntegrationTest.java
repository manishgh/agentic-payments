package io.agenticpayments.core;

import io.agenticpayments.ap2.Ap2Crypto;
import io.agenticpayments.application.*;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.composition.*;
import io.agenticpayments.crypto.*;
import io.agenticpayments.infrastructure.*;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import static io.agenticpayments.composition.Composition.*;
import static io.agenticpayments.core.CoreTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** A replaceable verifier's retryable outage is not cryptographic rejection or financial no-effect evidence. */
class CryptoAvailabilityIntegrationTest {
    private static final class FaultingProvider implements SignatureProvider {
        final JcaSignatureProvider delegate=new JcaSignatureProvider();
        final AtomicReference<String> unavailableType=new AtomicReference<>();
        final AtomicBoolean rejectMandate=new AtomicBoolean();
        final AtomicInteger unavailableCalls=new AtomicInteger();
        public String contractVersion(){return CONTRACT_VERSION;}
        public Set<String> supportedSuites(){return Set.of(ES256);}
        public byte[] sign(String suite,String handle,byte[] input){return delegate.sign(suite,handle,input);}
        public boolean verify(String suite,byte[] key,byte[] input,byte[] signature){
            String headerPart=new String(input,StandardCharsets.US_ASCII).split("\\.",-1)[0];
            String type=Ap2Crypto.parseJson(Ap2Crypto.decode(headerPart)).get("typ").textValue();
            if(type.equals(unavailableType.get())){unavailableCalls.incrementAndGet();throw new UnavailableException();}
            if(rejectMandate.get()&&Ap2Crypto.MANDATE_TYPE.equals(type))return false;
            return delegate.verify(suite,key,input,signature);
        }
    }
    private record Fixture(DataSource ds,Session session,PaymentService service,FaultingProvider provider,UUID intent,UUID operation) {}
    private static Fixture ready() {
        DataSource ds=database();Session session=isolated(ds);FaultingProvider provider=new FaultingProvider();
        NativeComposition protocols=new NativeComposition(provider);
        Ports ports=new Ports(protocols.checkout(),protocols.issuer(),protocols.verifier(),new FixtureKeyProvisioner(provider.delegate),new JdbcSignedArtifactStore(ds));
        PaymentService service=PaymentServices.local(ds,new JdbcSimulatorAdapter(ds),ports);
        Result created=service.create(session,key(),new CreateCommand("merchant-demo","success",Composition.PROFILE,List.of("single-tender","exact-money")));
        UUID intent=uuid(created.body().get("intentId"));
        Result quote=service.quote(session,intent,key(),new QuoteCommand(version(created),"cart-1250-eur",List.of(new Tender("simulator","synthetic-card","fixture-credential-1"))));
        Result approved=service.approve(session,intent,key(),approval(quote));
        Result executing=service.execute(session,intent,key(),new ExecuteCommand(version(approved),uuid(approved.body().get("attemptId"))));
        service.runNext(session);assertEquals("READY",service.status(session,intent).body().get("state"));
        return new Fixture(ds,session,service,provider,intent,uuid(executing.body().get("operationId")));
    }
    private static long count(Fixture f,String table) throws Exception {
        return scalar(f.ds(),"SELECT count(*) FROM "+table+" WHERE tenant_id=? AND run_id=?",f.session().tenantId(),f.session().runId());
    }
    private static void retryableOutage(String artifactType) throws Exception {
        Fixture f=ready();long artifacts=count(f,"native_artifacts"),authorities=count(f,"native_authorities"),replay=count(f,"native_replay_identities");
        f.provider().unavailableType.set(artifactType);
        PaymentException failure=assertThrows(PaymentException.class,()->f.service().runNext(f.session()));
        assertEquals(503,failure.status());assertEquals("TEMPORARILY_UNAVAILABLE",failure.code());assertEquals("SAME_COMMAND",failure.retry());assertNull(failure.getCause());
        assertTrue(f.provider().unavailableCalls.get()>0);
        Result uncertain=f.service().status(f.session(),f.intent());
        assertEquals("READY",uncertain.body().get("state"));assertEquals("HELD",uncertain.body().get("exposureState"));assertEquals(f.operation(),uncertain.body().get("operationId"));
        assertEquals(1250,scalar(f.ds(),"SELECT held FROM budgets WHERE tenant_id=? AND run_id=? AND budget_id='budget-eur'",f.session().tenantId(),f.session().runId()));
        assertEquals(0,count(f,"simulator_operation_journal"));assertEquals(1,count(f,"financial_operations"));assertEquals(1,count(f,"payment_attempts"));
        assertEquals(artifacts,count(f,"native_artifacts"));assertEquals(authorities,count(f,"native_authorities"));assertEquals(replay,count(f,"native_replay_identities"));
        f.provider().unavailableType.set(null);f.service().runNext(f.session());
        Result recovered=f.service().status(f.session(),f.intent());
        assertEquals("SUCCEEDED",recovered.body().get("state"));assertEquals("CONSUMED",recovered.body().get("exposureState"));assertEquals(f.operation(),recovered.body().get("operationId"));
        assertEquals(1,count(f,"simulator_operation_journal"));assertEquals(1,count(f,"financial_operations"));assertEquals(1,count(f,"payment_attempts"));
        assertEquals(artifacts,count(f,"native_artifacts"));assertEquals(authorities,count(f,"native_authorities"));assertEquals(replay,count(f,"native_replay_identities"));
    }
    @Test void checkoutVerifierOutagePreservesReadyExposureAndRecoversSameOperation() throws Exception { retryableOutage(Ap2Crypto.CHECKOUT_TYPE); }
    @Test void mandateVerifierOutagePreservesReadyExposureAndRecoversSameOperation() throws Exception { retryableOutage(Ap2Crypto.MANDATE_TYPE); }
    @Test void definitiveInvalidSignatureStillBlocksUnadmittedOperation() throws Exception {
        Fixture f=ready();f.provider().rejectMandate.set(true);f.service().runNext(f.session());
        Result rejected=f.service().status(f.session(),f.intent());
        assertEquals("CANCELLED",rejected.body().get("state"));assertEquals("RELEASED",rejected.body().get("exposureState"));
        assertEquals(0,count(f,"simulator_operation_journal"));assertEquals(1,count(f,"financial_operations"));assertEquals(3,count(f,"native_replay_identities"));
    }
}
