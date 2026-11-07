package io.agenticpayments.core;

import io.agenticpayments.application.*;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.infrastructure.*;
import io.agenticpayments.composition.NativeComposition;
import io.agenticpayments.composition.FixtureKeyProvisioner;
import io.agenticpayments.composition.Composition.*;
import io.agenticpayments.crypto.JcaSignatureProvider;
import org.junit.jupiter.api.*;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.Path;
import static io.agenticpayments.core.CoreTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Financial witnesses for the explicit native composition; all tests require real PostgreSQL. */
class DurableAp2AdmissionTest {
    private static DataSource ds;
    private Session session;
    private PaymentService service;
    @BeforeAll static void migrate() { ds=database(); DatabaseMigrator.migrate(ds); }
    @BeforeEach void seed() { session=session("native-financial-test",UUID.randomUUID()); service=PaymentServices.local(ds); service.seedRun(session,CLOCK,1250); }
    private Result quoteNative(String scenario) {
        var created=service.create(session,key(),new CreateCommand("merchant-demo",scenario,JdbcPaymentStore.NATIVE_PROFILE,List.of("single-tender","exact-money")));
        return service.quote(session,uuid(created.body().get("intentId")),key(),new QuoteCommand(version(created),"cart-1250-eur",List.of(new Tender("simulator","synthetic-card","fixture-credential-1"))));
    }
    private long count(String table) throws Exception { return scalar(ds,"SELECT count(*) FROM "+table+" WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId()); }
    private Result approve(Result quote,String command) { return service.approve(session,uuid(quote.body().get("intentId")),command,approval(quote)); }
    private UUID execute(Result approved) {
        UUID intent=uuid(approved.body().get("intentId"));
        service.execute(session,intent,key(),new ExecuteCommand(version(approved),uuid(approved.body().get("attemptId")))); return intent;
    }
    @Test void restartVerifiesRetainedAuthorityAndNeverSignsOnAcceptedRetry() throws Exception {
        var quote=quoteNative("success"); String command=key(); var approved=approve(quote,command);
        assertEquals("ap2-v0.2-direct-tap-synthetic-0.1",approved.body().get("authorityProfile"));
        long artifacts=count("native_artifacts"),keys=count("native_trust_keys");
        service=PaymentServices.local(ds);
        var replay=approve(quote,command); assertTrue(replay.replayed());
        assertEquals(approved.body().get("attemptId").toString(),replay.body().get("attemptId").toString());
        assertEquals(artifacts,count("native_artifacts")); assertEquals(keys,count("native_trust_keys"));
        UUID intent=execute(approved); service=PaymentServices.local(ds);
        for(int n=0;n<5&&service.runNext(session);n++) { }
        var status=service.status(session,intent);
        assertEquals("SUCCEEDED",status.body().get("state")); assertEquals("CONSUMED",status.body().get("exposureState"));
        assertEquals(JdbcPaymentStore.NATIVE_PROFILE,status.body().get("profile"));
        assertEquals(1,count("payment_attempts")); assertEquals(1,count("native_authorities"));
        assertEquals(3,count("native_challenges")); assertEquals(3,count("native_replay_identities"));
        assertEquals(1,count("simulator_operation_journal"));
    }
    @Test void concurrentDifferentApprovalCommandsReserveExactlyOnce() throws Exception {
        var quote=quoteNative("success"); var start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        try {
            var requests=new ArrayList<Future<String>>();
            for(int n=0;n<2;n++) requests.add(pool.submit(()->{start.await();try { approve(quote,key()); return "ACCEPTED"; }catch(PaymentException e) { return e.code(); }}));
            start.countDown(); var results=new ArrayList<String>();
            for(var request:requests) results.add(request.get(20,TimeUnit.SECONDS));
            assertEquals(1,results.stream().filter("ACCEPTED"::equals).count());
        } finally { pool.shutdownNow(); }
        assertEquals(1,count("payment_attempts")); assertEquals(1,count("reservations")); assertEquals(3,count("native_replay_identities"));
        assertEquals(1250,scalar(ds,"SELECT held FROM budgets WHERE tenant_id=? AND run_id=? AND budget_id='budget-eur'",session.tenantId(),session.runId()));
    }
    @Test void exactAuthorityExpiryBlocksDispatchAndReleasesOnlyUnadmittedExposure() throws Exception {
        var quote=quoteNative("success"); var approved=approve(quote,key()); UUID intent=execute(approved);
        service.runNext(session); assertEquals("READY",service.status(session,intent).body().get("state"));
        service.advanceClock(session,CLOCK.plusSeconds(300)); service.runNext(session);
        var status=service.status(session,intent); assertEquals("EXPIRED",status.body().get("state")); assertEquals("RELEASED",status.body().get("exposureState"));
        assertEquals(0,count("simulator_operation_journal")); assertEquals(3,count("native_replay_identities"));
    }
    @Test void nativeExpiryWhileWaitingForSeparateCustomerActionCannotAdmit() throws Exception {
        UUID intent=execute(approve(quoteNative("challenge-then-success"),key()));
        service.runNext(session); assertEquals("WAITING_ACTION",service.status(session,intent).body().get("state"));
        service.advanceClock(session,CLOCK.plusSeconds(300)); service.runNext(session);
        assertEquals("EXPIRED",service.status(session,intent).body().get("state")); assertEquals(0,count("simulator_operation_journal"));
    }
    @Test void admittedUnknownReconcilesOriginalOperationAfterExpiryAndGrantRevocation() throws Exception {
        UUID intent=execute(approve(quoteNative("response-loss-after-acceptance"),key()));
        service.runNext(session); service.runNext(session);
        var before=service.status(session,intent); assertEquals("UNKNOWN",before.body().get("state")); assertEquals("HELD",before.body().get("exposureState"));
        service.advanceClock(session,CLOCK.plusSeconds(3600));
        service.revoke(session,"fixture-grant-1",key(),new RevokeCommand("test-revocation"));
        service=PaymentServices.local(ds); service.reconcile(session,intent,false);
        var after=service.status(session,intent); assertEquals("SUCCEEDED",after.body().get("state")); assertEquals("CONSUMED",after.body().get("exposureState"));
        assertEquals(before.body().get("operationId"),after.body().get("operationId")); assertEquals(1,count("financial_operations")); assertEquals(1,count("simulator_operation_journal"));
    }
    @Test void corruptRetainedArtifactPreventsExecutionWithoutRenewal() throws Exception {
        var quote=quoteNative("success"); var approved=approve(quote,key()); long original=count("native_artifacts");
        try(var c=ds.getConnection();var p=c.prepareStatement("UPDATE native_artifacts SET content=convert_to('corrupt-native-artifact','UTF8') WHERE tenant_id=? AND run_id=?")) { p.setString(1,session.tenantId());p.setObject(2,session.runId());p.executeUpdate(); }
        var failure=assertThrows(PaymentException.class,()->execute(approved));
        assertTrue(Set.of("AUTHORITY_BINDING_INVALID","TEMPORARILY_UNAVAILABLE").contains(failure.code()));
        assertEquals(0,count("financial_operations")); assertEquals(original,count("native_artifacts")); assertEquals(3,count("native_replay_identities"));
    }
    @Test void acceptedApprovalReplayAfterExpiryReturnsHistoricalDecisionWithoutNewExposure() throws Exception {
        var quote=quoteNative("success");String command=key();var approved=approve(quote,command);
        service.advanceClock(session,CLOCK.plusSeconds(300));
        assertTrue(approve(quote,command).replayed()); assertEquals(1,count("reservations"));
        assertEquals("AUTHORITY_EXPIRED",assertThrows(PaymentException.class,()->execute(approved)).code());
        service.runNext(session); assertEquals("RELEASED",service.status(session,uuid(quote.body().get("intentId"))).body().get("exposureState"));
    }
    @Test void nativeProfileCannotUseLegacyOnlyComposition() {
        var unavailable=new PaymentService(new JdbcPaymentStore(ds,new JdbcSimulatorAdapter(ds)));
        var failure=assertThrows(PaymentException.class,()->unavailable.create(session,key(),new CreateCommand("merchant-demo","success",JdbcPaymentStore.NATIVE_PROFILE,List.of())));
        assertEquals("UNSUPPORTED_CAPABILITY",failure.code());
    }
    @Test void faultingIssuerRollsBackAttemptAndFinancialAuthority() throws Exception {
        var quote=quoteNative("success");
        var ports=NativeComposition.ports(new JdbcSignedArtifactStore(ds));
        AuthorityIssuer unavailableIssuer=new AuthorityIssuer() {
            public Descriptor describe() { return ports.issuer().describe(); }
            public IssuedAuthority issue(IssuanceRequest request) { throw new CompositionException(Failure.SIGNING_UNAVAILABLE); }
        };
        service=PaymentServices.local(ds,new JdbcSimulatorAdapter(ds),new Ports(ports.checkout(),unavailableIssuer,ports.verifier(),ports.provisioner(),ports.artifacts()));
        assertEquals("TEMPORARILY_UNAVAILABLE",assertThrows(PaymentException.class,()->approve(quote,key())).code());
        for(String table:List.of("payment_attempts","reservations","native_authorities","native_challenges","native_replay_identities")) assertEquals(0,count(table),table);
        service=PaymentServices.local(ds); assertEquals("APPROVED",approve(quote,key()).body().get("state"));
    }
    @Test void corruptedIssuerArtifactIsIndependentlyRejectedBeforeReservation() throws Exception {
        var quote=quoteNative("success"); var ports=NativeComposition.ports(new JdbcSignedArtifactStore(ds));
        AuthorityIssuer corruptedIssuer=new AuthorityIssuer() {
            public Descriptor describe() { return ports.issuer().describe(); }
            public IssuedAuthority issue(IssuanceRequest request) {
                var issued=ports.issuer().issue(request);
                // Change a payload byte, retaining the original signature.
                String token=issued.paymentMandate(); int split=token.indexOf('.')+1;
                token=token.substring(0,split)+(token.charAt(split)=='A'?'B':'A')+token.substring(split+1);
                return new IssuedAuthority(issued.checkoutMandate(),token);
            }
        };
        service=PaymentServices.local(ds,new JdbcSimulatorAdapter(ds),new Ports(ports.checkout(),corruptedIssuer,ports.verifier(),ports.provisioner(),ports.artifacts()));
        assertEquals("AUTHORITY_BINDING_INVALID",assertThrows(PaymentException.class,()->approve(quote,key())).code());
        for(String table:List.of("payment_attempts","reservations","native_authorities","native_challenges","native_replay_identities")) assertEquals(0,count(table),table);
    }
    @Test void publicKeyRevocationBeforeAdmissionOrdersCancellationWithoutASecondSend() throws Exception {
        UUID intent=execute(approve(quoteNative("success"),key())); service.runNext(session);
        var key=row(ds,"SELECT issuer,kid,purpose FROM native_trust_keys WHERE tenant_id=? AND run_id=? AND purpose='MERCHANT_CHECKOUT' LIMIT 1",session.tenantId(),session.runId());
        new JdbcPaymentStore(ds,new JdbcSimulatorAdapter(ds)).revokeTrustKey(session,key.get("issuer").toString(),key.get("kid").toString(),key.get("purpose").toString());
        service.runNext(session);
        assertEquals("CANCELLED",service.status(session,intent).body().get("state")); assertEquals("RELEASED",service.status(session,intent).body().get("exposureState"));
        assertEquals(0,count("simulator_operation_journal")); assertEquals(3,count("native_replay_identities"));
    }
    @Test void sqlFaultAfterReplayInsertionRollsBackAuthorityAndCanRetrySameCommand() throws Exception {
        var quote=quoteNative("success"); var fault=new DurableAp2FaultDataSource(ds);
        service=PaymentServices.local(fault); String command=key();
        assertEquals("TEMPORARILY_UNAVAILABLE",assertThrows(PaymentException.class,()->approve(quote,command)).code());
        assertEquals(3,fault.replayBeforeFault.get());
        for(String table:List.of("payment_attempts","reservations","native_authorities","native_challenges","native_replay_identities")) assertEquals(0,count(table),table);
        assertEquals(0,scalar(ds,"SELECT held FROM budgets WHERE tenant_id=? AND run_id=? AND budget_id='budget-eur'",session.tenantId(),session.runId()));
        assertEquals("APPROVED",approve(quote,command).body().get("state"));
        assertEquals(1,count("payment_attempts")); assertEquals(1,count("reservations")); assertEquals(3,count("native_replay_identities"));
    }
    @Test void differentNativeIntentsCannotRacePastTheSameExactBudget() throws Exception {
        var one=quoteNative("success");var two=quoteNative("success");var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var futures=new ArrayList<Future<String>>();
            for(var quote:List.of(one,two)) futures.add(pool.submit(()->{start.await();try { approve(quote,key());return "OK"; }catch(PaymentException e) { return e.code(); }}));
            start.countDown();var results=List.of(futures.getFirst().get(20,TimeUnit.SECONDS),futures.get(1).get(20,TimeUnit.SECONDS));
            assertEquals(1,results.stream().filter("OK"::equals).count());assertTrue(results.contains("BUDGET_EXCEEDED"));
        } finally { pool.shutdownNow(); }
        assertEquals(1,count("native_authorities"));assertEquals(1,count("reservations"));assertEquals(3,count("native_replay_identities"));
    }
    @Test void requoteInvalidatesStaleExactCheckoutBeforeIssuance() throws Exception {
        var old=quoteNative("success");UUID intent=uuid(old.body().get("intentId"));
        var current=service.quote(session,intent,key(),new QuoteCommand(version(old),"cart-1250-eur",List.of(new Tender("simulator","synthetic-card","fixture-credential-1"))));
        assertNotEquals(old.body().get("checkoutDigest"),current.body().get("checkoutDigest"));
        var stale=new ApproveCommand(version(current),((Number)old.body().get("checkoutRevision")).intValue(),old.body().get("checkoutDigest").toString(),"fixture-grant-1");
        assertEquals("CHECKOUT_CHANGED",assertThrows(PaymentException.class,()->service.approve(session,intent,key(),stale)).code());
        assertEquals(0,count("native_authorities"));assertEquals(0,count("native_replay_identities"));assertEquals("APPROVED",approve(current,key()).body().get("state"));
    }
    @Test void transientArtifactReadFailureRetainsHeldExposureUntilOriginalGateCanRetry() throws Exception {
        UUID intent=execute(approve(quoteNative("success"),key()));service.runNext(session);
        var ports=NativeComposition.ports(new JdbcSignedArtifactStore(ds));
        SignedArtifactStore unavailable=new SignedArtifactStore() {
            public Descriptor describe() { return ports.artifacts().describe(); }
            public ArtifactReference put(Scope scope,Artifact artifact) { return ports.artifacts().put(scope,artifact); }
            public Artifact get(Scope scope,ArtifactReference reference) { throw new CompositionException(Failure.ARTIFACT_UNAVAILABLE); }
        };
        service=PaymentServices.local(ds,new JdbcSimulatorAdapter(ds),new Ports(ports.checkout(),ports.issuer(),ports.verifier(),ports.provisioner(),unavailable));
        assertEquals("TEMPORARILY_UNAVAILABLE",assertThrows(PaymentException.class,()->service.runNext(session)).code());
        assertEquals("READY",service.status(session,intent).body().get("state"));assertEquals("HELD",service.status(session,intent).body().get("exposureState"));assertEquals(0,count("simulator_operation_journal"));
        service=PaymentServices.local(ds);service.runNext(session);assertEquals("SUCCEEDED",service.status(session,intent).body().get("state"));assertEquals(1,count("financial_operations"));
    }
    @Test void actualProcessCrashAfterNativeAdmissionRecoversWithOriginalOperation() throws Exception {
        UUID intent=execute(approve(quoteNative("success"),key()));service.runNext(session);
        UUID operation=uuid(service.status(session,intent).body().get("operationId"));
        assertEquals(73,child("crash-after-journal-commit",intent,operation));
        assertEquals("ADMITTED",service.status(session,intent).body().get("state"));assertEquals("HELD",service.status(session,intent).body().get("exposureState"));
        service.advanceClock(session,CLOCK.plusSeconds(3600)); service.revoke(session,"fixture-grant-1",key(),new RevokeCommand("test-revocation"));
        assertEquals(0,child("recover",intent,operation));assertEquals(0,child("recover-replay",intent,operation));
        assertEquals("SUCCEEDED",service.status(session,intent).body().get("state"));assertEquals(1,count("simulator_operation_journal"));assertEquals(1,count("financial_operations"));
    }
    private int child(String mode,UUID intent,UUID operation) throws Exception {
        Path executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var process=new ProcessBuilder(executable.toString(),"-cp",classpath,CrashWindowProcess.class.getName(),mode,session.tenantId(),session.runId().toString(),intent.toString(),operation.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30,TimeUnit.SECONDS));String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(mode.startsWith("recover")?"RECOVERY_OK":"",output.strip());return process.exitValue();
        } finally { if(process.isAlive())process.destroyForcibly().waitFor(5,TimeUnit.SECONDS); }
    }
    private void orderedKeyRevocation(boolean revokeFirst) throws Exception {
        UUID intent=execute(approve(quoteNative("success"),key()));service.runNext(session);
        var key=row(ds,"SELECT issuer,kid,purpose FROM native_trust_keys WHERE tenant_id=? AND run_id=? AND purpose='MERCHANT_CHECKOUT' LIMIT 1",session.tenantId(),session.runId());
        var winner=new RunLockBarrierDataSource(ds,true);var loser=new RunLockBarrierDataSource(ds,false);
        var first=PaymentServices.local(winner);var second=PaymentServices.local(loser);var pool=Executors.newFixedThreadPool(2);
        try {
            var one=pool.submit(()->{
                if(revokeFirst) new JdbcPaymentStore(winner,new JdbcSimulatorAdapter(winner)).revokeTrustKey(session,key.get("issuer").toString(),key.get("kid").toString(),key.get("purpose").toString());
                else first.runNext(session);
            });
            assertTrue(winner.acquired.await(10,TimeUnit.SECONDS));
            var two=pool.submit(()->{
                if(revokeFirst) second.runNext(session);
                else new JdbcPaymentStore(loser,new JdbcSimulatorAdapter(loser)).revokeTrustKey(session,key.get("issuer").toString(),key.get("kid").toString(),key.get("purpose").toString());
            });
            assertTrue(loser.entered.await(10,TimeUnit.SECONDS));assertEquals(1,loser.acquired.getCount());winner.resume.countDown();
            one.get(20,TimeUnit.SECONDS);two.get(20,TimeUnit.SECONDS);
        } finally { winner.resume.countDown();loser.resume.countDown();pool.shutdownNow(); }
        assertEquals(revokeFirst?"CANCELLED":"SUCCEEDED",service.status(session,intent).body().get("state"));
        assertEquals(revokeFirst?"RELEASED":"CONSUMED",service.status(session,intent).body().get("exposureState"));assertEquals(revokeFirst?0:1,count("simulator_operation_journal"));
    }
    @Test void keyRevocationWinningRunLockPreventsDispatch() throws Exception { orderedKeyRevocation(true); }
    @Test void admissionWinningRunLockSurvivesLaterKeyRevocation() throws Exception { orderedKeyRevocation(false); }
    @Test void alteredPersistedPublicKeyUnderExistingIdentityCannotAuthorizeDispatch() throws Exception {
        UUID intent=execute(approve(quoteNative("success"),key()));service.runNext(session);
        try(var c=ds.getConnection();var p=c.prepareStatement("UPDATE native_trust_keys SET public_key=decode('00','hex') WHERE tenant_id=? AND run_id=? AND purpose='MERCHANT_CHECKOUT'")) { p.setString(1,session.tenantId());p.setObject(2,session.runId());p.executeUpdate(); }
        service.runNext(session);assertEquals("CANCELLED",service.status(session,intent).body().get("state"));assertEquals("RELEASED",service.status(session,intent).body().get("exposureState"));assertEquals(0,count("simulator_operation_journal"));
    }
    @Test void independentlySignedSameIssuerJwtIdAcrossRunsAndKeysIsConsumedOnlyOnce() throws Exception {
        String sharedIssuer="urn:synthetic:cross-run:"+UUID.randomUUID(),repeatedId=UUID.randomUUID().toString();
        var signatures=new JcaSignatureProvider();var nativeProtocols=new NativeComposition(signatures,()->repeatedId);
        var fixtureProvisioner=new FixtureKeyProvisioner(signatures);
        KeyProvisioner sharedIssuerProvisioner=new KeyProvisioner() {
            public Descriptor describe() { return fixtureProvisioner.describe(); }
            public Provisioning provision(Scope scope,java.time.Instant now) {
                var fixture=fixtureProvisioner.provision(scope,now);var enrollments=new ArrayList<TrustKey>();
                for(var key:fixture.enrollments()) enrollments.add(new TrustKey(key.scope(),key.purpose().equals("MERCHANT_CHECKOUT")?key.issuer():sharedIssuer,key.kid(),key.purpose(),key.publicKeySpki(),key.validFrom(),key.validUntil(),key.version(),key.revoked()));
                return new Provisioning(enrollments,fixture.merchantSigner(),new SigningIdentity(sharedIssuer,fixture.tapSigner().kid(),fixture.tapSigner().keyHandle()));
            }
        };
        service=PaymentServices.local(ds,new JdbcSimulatorAdapter(ds),new Ports(nativeProtocols.checkout(),nativeProtocols.issuer(),nativeProtocols.verifier(),sharedIssuerProvisioner,new JdbcSignedArtifactStore(ds)));
        Session first=session;Result q1=quoteNative("success");
        session=session(first.tenantId(),UUID.randomUUID());service.seedRun(session,CLOCK,1250);Session second=session;Result q2=quoteNative("success");
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var one=pool.submit(()->{start.await();try {service.approve(first,uuid(q1.body().get("intentId")),key(),approval(q1));return "OK";}catch(PaymentException e) {return e.code();}});
            var two=pool.submit(()->{start.await();try {service.approve(second,uuid(q2.body().get("intentId")),key(),approval(q2));return "OK";}catch(PaymentException e) {return e.code();}});
            start.countDown();var results=List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS));
            assertEquals(1,results.stream().filter("OK"::equals).count());assertTrue(results.contains("AUTHORITY_REPLAY"));
        } finally {pool.shutdownNow();}
        assertEquals(3,scalar(ds,"SELECT count(*) FROM native_replay_identities WHERE tenant_id=? AND issuer=? AND jti=?",first.tenantId(),sharedIssuer,repeatedId));
        assertEquals(1,scalar(ds,"SELECT count(*) FROM payment_attempts WHERE tenant_id=? AND run_id IN (?,?)",first.tenantId(),first.runId(),second.runId()));
        assertEquals(1,scalar(ds,"SELECT count(*) FROM reservations WHERE tenant_id=? AND run_id IN (?,?)",first.tenantId(),first.runId(),second.runId()));
        assertEquals(1250,scalar(ds,"SELECT sum(held) FROM budgets WHERE tenant_id=? AND run_id IN (?,?)",first.tenantId(),first.runId(),second.runId()));
    }
    @Test void legacyApprovalImplementationCannotReserveNativeIntent() throws Exception {
        var quote=quoteNative("success");var legacy=new PaymentService(new JdbcPaymentStore(ds,new JdbcSimulatorAdapter(ds)));
        assertEquals("UNSUPPORTED_CAPABILITY",assertThrows(PaymentException.class,()->legacy.approve(session,uuid(quote.body().get("intentId")),key(),approval(quote))).code());
        assertEquals(0,count("payment_attempts"));assertEquals(0,count("reservations"));
    }
    @Test void faultyReplacementStoreCannotSubstituteArtifactReferenceIdentity() throws Exception {
        var ports=NativeComposition.ports(new JdbcSignedArtifactStore(ds));
        Map<String,Artifact> retained=new HashMap<>();
        SignedArtifactStore faulty=new SignedArtifactStore() {
            public Descriptor describe() { return ports.artifacts().describe(); }
            public ArtifactReference put(Scope scope,Artifact artifact) {
                var reference=io.agenticpayments.composition.ArtifactIntegrity.reference(artifact);
                retained.put(reference.digest(),artifact);
                return new ArtifactReference("0".repeat(64),reference.digest(),reference.length(),reference.mediaType(),reference.profile());
            }
            public Artifact get(Scope scope,ArtifactReference reference) { return retained.get(reference.digest()); }
        };
        service=PaymentServices.local(ds,new JdbcSimulatorAdapter(ds),new Ports(ports.checkout(),ports.issuer(),ports.verifier(),ports.provisioner(),faulty));
        var quote=quoteNative("success");
        assertThrows(PaymentException.class,()->approve(quote,key()));
        for(String table:List.of("payment_attempts","reservations","native_authorities","native_replay_identities")) assertEquals(0,count(table),table);
    }
    @Test void separatelyCommittingAdmissionBackendCannotAdvertiseOnlyCrudCompatibility() throws Exception {
        var delegate=new JdbcPaymentStore(ds,new JdbcSimulatorAdapter(ds));
        var unsafe=(PaymentStore)java.lang.reflect.Proxy.newProxyInstance(PaymentStore.class.getClassLoader(),new Class<?>[]{PaymentStore.class},(proxy,method,args)->{
            if(method.getName().equals("admissionCapabilities")) return Set.of("independent-grant-crud","independent-budget-crud");
            if(method.getName().equals("create")) fail("Incompatible admission must fail selection before backend mutation");
            try { return method.invoke(delegate,args); }catch(java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
        });
        var unsupported=new PaymentService(unsafe);
        assertEquals("UNSUPPORTED_CAPABILITY",assertThrows(PaymentException.class,()->unsupported.create(session,key(),new CreateCommand("merchant-demo","success",JdbcPaymentStore.NATIVE_PROFILE,List.of()))).code());
        assertEquals(0,count("payment_intents"));assertEquals(0,count("reservations"));
    }
}
