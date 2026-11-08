package io.agenticpayments.core;

import io.agenticpayments.http.HttpFailureMapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agenticpayments.application.PaymentException;
import io.agenticpayments.application.PaymentService;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.composition.NativeComposition;
import io.agenticpayments.infrastructure.*;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static io.agenticpayments.composition.Composition.*;
import static io.agenticpayments.core.CoreTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** PostgreSQL witnesses for the application/JDBC seam; no simulated financial persistence. */
class NativeCoordinationIntegrationTest {
    private static final ObjectMapper JSON=new ObjectMapper();

    private static Result quoteNative(PaymentService service,Session session) {
        var created=service.create(session,key(),new CreateCommand("merchant-demo","success",PROFILE,List.of("single-tender","exact-money")));
        return service.quote(session,uuid(created.body().get("intentId")),key(),new QuoteCommand(version(created),"cart-1250-eur",List.of(new Tender("simulator","synthetic-card","fixture-credential-1"))));
    }
    private static long count(DataSource ds,Session session,String table) throws Exception {
        return scalar(ds,"SELECT count(*) FROM "+table+" WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId());
    }
    private static void complete(PaymentService service,Session session,Result approved) {
        UUID intent=uuid(approved.body().get("intentId"));
        service.execute(session,intent,key(),new ExecuteCommand(version(approved),uuid(approved.body().get("attemptId"))));
        for(int n=0;n<5&&service.runNext(session);n++) { }
        assertEquals("SUCCEEDED",service.status(session,intent).body().get("state"));
        assertEquals("CONSUMED",service.status(session,intent).body().get("exposureState"));
    }

    @Test void approvalChecksOldMerchantBeforeProvisionAndNewTapWithFreshEnrollmentSnapshot() throws Exception {
        DataSource ds=database();Session session=isolated(ds);
        Result quoted=quoteNative(PaymentServices.local(ds),session);
        String oldMerchantKid=row(ds,"SELECT kid FROM native_trust_keys WHERE tenant_id=? AND run_id=? AND purpose='MERCHANT_CHECKOUT'",session.tenantId(),session.runId()).get("kid").toString();
        assertEquals(4,count(ds,session,"native_trust_keys"));

        // A new service has fresh ephemeral signing custody; old public enrollment remains durable.
        Ports fresh=NativeComposition.ports(new JdbcSignedArtifactStore(ds));
        var events=new ArrayList<String>();var snapshots=new ArrayList<EnrollmentSnapshot>();var lookups=new ArrayList<String>();
        var provisioned=new AtomicReference<Provisioning>();
        KeyProvisioner provisioner=new KeyProvisioner() {
            public Descriptor describe(){return fresh.provisioner().describe();}
            public Provisioning provision(Scope scope,Instant now){events.add("provision");var result=fresh.provisioner().provision(scope,now);provisioned.set(result);return result;}
        };
        KeyResolverFactory resolvers=new KeyResolverFactory() {
            public Descriptor describe(){return fresh.keyResolvers().describe();}
            public KeyResolver bind(EnrollmentSnapshot snapshot){events.add("bind:"+snapshots.size());snapshots.add(snapshot);var delegate=fresh.keyResolvers().bind(snapshot);
                return (scope,issuer,kid,purpose,now)->{lookups.add(purpose+":"+kid);return delegate.resolve(scope,issuer,kid,purpose,now);};}
        };
        CheckoutProtocol checkout=new CheckoutProtocol() {
            public Descriptor describe(){return fresh.checkout().describe();}
            public FrozenCheckout freeze(CheckoutRequest request){return fresh.checkout().freeze(request);}
            public CheckoutFacts validate(CheckoutFacts facts,FrozenCheckout frozen,KeyResolver keys,Instant now){events.add("checkout");return fresh.checkout().validate(facts,frozen,keys,now);}
        };
        AuthorityVerifier verifier=new AuthorityVerifier() {
            public Descriptor describe(){return fresh.verifier().describe();}
            public VerifiedAuthority verify(AuthorityRequest request,KeyResolver keys,Instant now){events.add("verify");return fresh.verifier().verify(request,keys,now);}
        };
        PaymentService service=PaymentServices.local(ds,new JdbcSimulatorAdapter(ds),new Ports(checkout,fresh.issuer(),verifier,provisioner,fresh.artifacts(),resolvers));
        Result approved=service.approve(session,uuid(quoted.body().get("intentId")),key(),approval(quoted));
        assertEquals(List.of("bind:0","checkout","provision","bind:1","verify"),events);
        String newTapKid=provisioned.get().tapSigner().kid();
        assertEquals(4,snapshots.getFirst().keys().size());assertEquals(8,snapshots.get(1).keys().size());
        assertTrue(snapshots.getFirst().keys().stream().anyMatch(k->k.purpose().equals(MERCHANT_CHECKOUT)&&k.kid().equals(oldMerchantKid)));
        assertFalse(snapshots.getFirst().keys().stream().anyMatch(k->k.kid().equals(newTapKid)));
        assertEquals(3,snapshots.get(1).keys().stream().filter(k->k.kid().equals(newTapKid)).count());
        assertTrue(lookups.contains(MERCHANT_CHECKOUT+":"+oldMerchantKid));
        for(String purpose:List.of(CHECKOUT_MANDATE,PAYMENT_MANDATE,PROCESSOR_PAYMENT_MANDATE)) assertTrue(lookups.contains(purpose+":"+newTapKid));
        events.clear();snapshots.clear();lookups.clear();UUID intent=uuid(approved.body().get("intentId"));
        service.execute(session,intent,key(),new ExecuteCommand(version(approved),uuid(approved.body().get("attemptId"))));
        assertEquals(List.of("bind:0","checkout","bind:1","verify"),events);
        assertNotSame(snapshots.getFirst(),snapshots.get(1));
        for(var snapshot:snapshots) assertEquals(8,snapshot.keys().size());
        for(int n=0;n<5&&service.runNext(session);n++) { }
        assertFalse(events.contains("provision"),"Execution and worker verification must not renew authority");
        assertEquals("SUCCEEDED",service.status(session,intent).body().get("state"));
        assertEquals(1,count(ds,session,"financial_operations"));assertEquals(1,count(ds,session,"simulator_operation_journal"));
    }

    // These literal records pin the pre-extraction JSON contract independently of production evidence classes.
    private record LegacyReference(String id,String digest,int length,String mediaType,String profile) {}
    private record LegacyCheckout(String sessionId,LegacyReference acpPayload,LegacyReference checkoutJwt,
                                  String checkoutHash,String checkoutDigest,String payloadDigest,String merchantIssuer,
                                  String handlerId,String protocolVersion) {}
    private record LegacyAuthority(LegacyReference checkoutMandate,LegacyReference paymentMandate,String issuer,
                                   String checkoutAudience,String paymentAudience,String checkoutNonce,String paymentNonce,
                                   String issuedAt,String expiresAt) {}
    private static String rewriteLegacyJson(DataSource ds,Session session,String table,String column,Class<?> legacyShape) throws Exception {
        String original=row(ds,"SELECT "+column+" FROM "+table+" WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId()).get(column).toString();
        String legacy=JSON.writeValueAsString(JSON.readValue(original,legacyShape));
        assertEquals(JSON.readTree(original),JSON.readTree(legacy),"Historical properties and JSON value types must be unchanged");
        try(var connection=ds.getConnection();var update=connection.prepareStatement("UPDATE "+table+" SET "+column+"=? WHERE tenant_id=? AND run_id=?")) {
            update.setString(1,legacy);update.setString(2,session.tenantId());update.setObject(3,session.runId());assertEquals(1,update.executeUpdate());
        }
        return legacy;
    }
    @Test void historicalCheckoutAndAuthorityJsonSurviveFreshServicesAndAcceptedReplay() throws Exception {
        DataSource ds=database();Session session=isolated(ds);Result quoted=quoteNative(PaymentServices.local(ds),session);
        UUID intent=uuid(quoted.body().get("intentId"));String command=key();
        String legacyCheckout=rewriteLegacyJson(ds,session,"native_checkouts","checkout_json",LegacyCheckout.class);
        LegacyCheckout checkout=JSON.readValue(legacyCheckout,LegacyCheckout.class);
        assertEquals(quoted.body().get("checkoutDigest"),checkout.checkoutDigest());
        PaymentService approvalService=PaymentServices.local(ds);
        Result approved=approvalService.approve(session,intent,command,approval(quoted));
        String legacyAuthority=rewriteLegacyJson(ds,session,"native_authorities","authority_json",LegacyAuthority.class);
        LegacyAuthority authority=JSON.readValue(legacyAuthority,LegacyAuthority.class);
        assertEquals(CLOCK,Instant.parse(authority.issuedAt()));assertEquals(CLOCK.plusSeconds(300),Instant.parse(authority.expiresAt()));
        long artifacts=count(ds,session,"native_artifacts"),enrollments=count(ds,session,"native_trust_keys");
        PaymentService restarted=PaymentServices.local(ds);Result replay=restarted.approve(session,intent,command,approval(quoted));
        assertTrue(replay.replayed());assertEquals(approved.body().get("attemptId").toString(),replay.body().get("attemptId").toString());
        complete(restarted,session,approved);
        assertEquals(artifacts,count(ds,session,"native_artifacts"));assertEquals(enrollments,count(ds,session,"native_trust_keys"));
        assertEquals(legacyCheckout,row(ds,"SELECT checkout_json FROM native_checkouts WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId()).get("checkout_json"));
        assertEquals(legacyAuthority,row(ds,"SELECT authority_json FROM native_authorities WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId()).get("authority_json"));
        assertEquals(1,count(ds,session,"payment_attempts"));assertEquals(3,count(ds,session,"native_replay_identities"));
        assertEquals(1,count(ds,session,"financial_operations"));assertEquals(1,count(ds,session,"simulator_operation_journal"));
    }

    @Test void stagedMandateReadFailureAfterProvisionalInsertsRollsBackAndSameCommandRecovers() throws Exception {
        DataSource ds=database();Session session=isolated(ds);Result quoted=quoteNative(PaymentServices.local(ds),session);
        long commands=count(ds,session,"commands"),enrollments=count(ds,session,"native_trust_keys"),audit=count(ds,session,"audit_events");
        var activeConnection=new ThreadLocal<Connection>();
        DataSource observed=(DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(proxy,method,args)->{
            try { Object result=method.invoke(ds,args);if(method.getName().equals("getConnection"))activeConnection.set((Connection)result);return result; }
            catch(InvocationTargetException failure){throw failure.getCause();}
        });
        Ports base=NativeComposition.ports(new JdbcSignedArtifactStore(ds));var failReads=new AtomicBoolean(true);
        Set<String> stagedMandates=new HashSet<>();var provisionalCounts=new ArrayList<Long>();
        SignedArtifactStore artifacts=new SignedArtifactStore() {
            public Descriptor describe(){return base.artifacts().describe();}
            public ArtifactReference put(Scope scope,Artifact artifact){var ref=base.artifacts().put(scope,artifact);stagedMandates.add(ref.id());return ref;}
            public Artifact get(Scope scope,ArtifactReference ref){
                if(failReads.get()&&stagedMandates.contains(ref.id())) {
                    // The active PostgreSQL transaction sees provisional rows invisible to outside readers.
                    for(String table:List.of("payment_attempts","native_authorities","native_challenges","native_replay_identities")) {
                        try(var witness=activeConnection.get().prepareStatement("SELECT count(*) FROM "+table+" WHERE tenant_id=? AND run_id=?")) {
                            witness.setString(1,session.tenantId());witness.setObject(2,session.runId());try(var rows=witness.executeQuery()){assertTrue(rows.next());provisionalCounts.add(rows.getLong(1));}
                        } catch(java.sql.SQLException failure){throw new AssertionError("Provisional admission witness failed",failure);}
                    }
                    throw new CompositionException(Failure.ARTIFACT_UNAVAILABLE);
                }
                return base.artifacts().get(scope,ref);
            }
        };
        PaymentService service=PaymentServices.local(observed,new JdbcSimulatorAdapter(ds),new Ports(base.checkout(),base.issuer(),base.verifier(),base.provisioner(),artifacts,base.keyResolvers()));
        UUID intent=uuid(quoted.body().get("intentId"));String command=key();
        PaymentException failure=assertThrows(PaymentException.class,()->service.approve(session,intent,command,approval(quoted)));
        assertEquals("TEMPORARILY_UNAVAILABLE",failure.code());assertEquals(503,HttpFailureMapper.map(failure).status());assertEquals("SAME_COMMAND",HttpFailureMapper.map(failure).retry());
        assertEquals(List.of(1L,1L,3L,0L),provisionalCounts);
        for(String table:List.of("payment_attempts","native_authorities","native_challenges","native_replay_identities","reservations","financial_operations","simulator_operation_journal")) assertEquals(0,count(ds,session,table),table);
        assertEquals(commands,count(ds,session,"commands"));assertEquals(enrollments,count(ds,session,"native_trust_keys"));
        assertEquals(audit,count(ds,session,"audit_events"));
        assertEquals(0,scalar(ds,"SELECT held FROM budgets WHERE tenant_id=? AND run_id=? AND budget_id='budget-eur'",session.tenantId(),session.runId()));
        assertEquals(0,scalar(ds,"SELECT count(*) FROM commands WHERE tenant_id=? AND run_id=? AND idempotency_key=?",session.tenantId(),session.runId(),command));
        assertEquals(quoted.body().get("state"),service.status(session,intent).body().get("state"));
        assertTrue(count(ds,session,"native_artifacts")>2,"Independent immutable staging may survive SQL rollback");
        failReads.set(false);Result approved=service.approve(session,intent,command,approval(quoted));assertFalse(approved.replayed());
        assertTrue(service.approve(session,intent,command,approval(quoted)).replayed());complete(service,session,approved);
        assertEquals(1,count(ds,session,"payment_attempts"));assertEquals(1,count(ds,session,"reservations"));assertEquals(3,count(ds,session,"native_replay_identities"));
        assertEquals(1,count(ds,session,"financial_operations"));assertEquals(1,count(ds,session,"simulator_operation_journal"));
        activeConnection.remove();
    }
}
