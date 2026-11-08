package io.agenticpayments.core;

import io.agenticpayments.application.*;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.infrastructure.*;
import io.agenticpayments.infrastructure.provider.*;
import io.agenticpayments.composition.NativeComposition;
import io.agenticpayments.sdk.*;
import io.agenticpayments.sdk.execution.v1.*;
import io.agenticpayments.sdk.execution.v1.compat.*;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.nio.file.Path;
import static io.agenticpayments.core.CoreTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Core, provider journal and peer are independently inspected across their real commit boundaries. */
class ProviderAdmissionIntegrationTest {
    static final ExecutionBinding BINDING=new ExecutionBinding("simulator","admission-account","eu-test","admission-config","1","fixture-credential-1","1","platform-owner");
    static SyntheticProviderRegistration registration(Session s){return new SyntheticProviderRegistration(s.tenantId(),"merchant-demo",s.runId(),BINDING,CLOCK,CLOCK.plusSeconds(600));}
    record Fixture(DataSource ds,Session session,SyntheticProviderRegistration registration,PaymentService service,UUID intent,Result approved){
        void execute(){service.execute(session,intent,key(),new ExecuteCommand(version(approved),uuid(approved.body().get("attemptId"))));}
        String state(){return service.status(session,intent).body().get("state").toString();}
        String exposure(){return service.status(session,intent).body().get("exposureState").toString();}
        long count(String table)throws Exception{return scalar(ds,"SELECT count(*) FROM "+table+" WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId());}
        long providerCount(String table)throws Exception{return scalar(ds,"SELECT count(*) FROM "+table+" WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId().toString());}
        OperationIdentity identity()throws Exception{
            var r=row(ds,"SELECT * FROM financial_operations WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId());
            return new OperationIdentity(new ExecutionScope(session.tenantId(),session.runId(),"merchant-demo"),intent,uuid(approved.body().get("attemptId")),(UUID)r.get("operation_id"),(UUID)r.get("adapter_key"),r.get("request_digest").toString());
        }
        ProviderExecutionBinding binding()throws Exception{return new JdbcProviderExecutionBindings(ds).load(identity());}
        CompatibilityBindingJournal journal(){return new JdbcCompatibilityBindingJournal(ds,registration.configuration());}
        ChargeOperation peerOperation()throws Exception{return registration.configuration().expectedLegacy(binding().operation());}
    }
    static Fixture approved(String scenario){
        DataSource ds=database();Session s=isolated(ds);var registration=registration(s);var service=PaymentServices.providerJournal(ds,registration);
        var c=service.create(s,key(),new CreateCommand("merchant-demo",scenario,JdbcPaymentStore.NATIVE_PROFILE,List.of("single-tender","exact-money")));
        UUID intent=uuid(c.body().get("intentId"));var q=service.quote(s,intent,key(),new QuoteCommand(version(c),"cart-1250-eur",List.of(new Tender("simulator","synthetic-card","fixture-credential-1"))));
        return new Fixture(ds,s,registration,service,intent,service.approve(s,intent,key(),approval(q)));
    }
    @Test void nativeAdmissionUsesOneRetainedProviderRouteAndOneDifferentPeerIdentity()throws Exception{
        var f=approved("success");f.execute();
        assertEquals(1,f.count("payment_operation_routes"));assertEquals(0,f.providerCount("provider_compatibility_bindings"));
        var binding=f.binding();assertEquals(f.identity(),binding.coreOperation().identity());assertEquals("fixture-credential-1",binding.credentialReference());
        assertEquals(f.registration.binding(binding.coreOperation()),binding);f.service.runNext(f.session);
        assertEquals("READY",f.state());assertEquals(1,f.providerCount("provider_compatibility_bindings"));
        assertEquals(false,row(f.ds,"SELECT dispatch_claimed FROM provider_compatibility_bindings WHERE tenant_id=? AND run_id=?",f.session.tenantId(),f.session.runId().toString()).get("dispatch_claimed"));
        f.service.runNext(f.session);assertEquals("SUCCEEDED",f.state());assertEquals("CONSUMED",f.exposure());
        assertEquals(true,row(f.ds,"SELECT dispatch_claimed FROM provider_compatibility_bindings WHERE tenant_id=? AND run_id=?",f.session.tenantId(),f.session.runId().toString()).get("dispatch_claimed"));
        var peer=f.peerOperation();assertNotEquals(f.identity(),peer.identity());assertEquals(0,f.count("simulator_operation_journal"));
        assertEquals(1,scalar(f.ds,"SELECT count(*) FROM simulator_operation_journal WHERE tenant_id=? AND run_id=? AND adapter_key=?",peer.identity().scope().tenantId(),peer.identity().scope().runId(),peer.identity().adapterKey()));
        long evidence=f.count("operation_evidence"),audit=f.count("audit_events");
        var restarted=PaymentServices.providerJournal(f.ds,registration(f.session));restarted.reconcile(f.session,f.intent,false);
        assertEquals(evidence,f.count("operation_evidence"));assertEquals(audit,f.count("audit_events"));assertEquals(binding,f.binding());
    }
    @Test void responseLossAndPendingRecoverOriginalOperationWithoutResubmission()throws Exception{
        for(String scenario:List.of("response-loss-after-acceptance","delayed-success")){
            var f=approved(scenario);f.execute();f.service.runNext(f.session);f.service.runNext(f.session);
            assertEquals(scenario.startsWith("response")?"UNKNOWN":"PENDING",f.state());assertEquals("HELD",f.exposure());var original=f.identity();
            if(scenario.equals("delayed-success"))new JdbcSimulatorAdapter(f.ds).finishPending(f.peerOperation().identity());
            PaymentServices.providerJournal(f.ds,registration(f.session)).reconcile(f.session,f.intent,false);
            assertEquals("SUCCEEDED",f.state());assertEquals("CONSUMED",f.exposure());assertEquals(original,f.identity());assertEquals(1,f.count("financial_operations"));
            assertEquals(1,f.providerCount("provider_compatibility_bindings"));
        }
    }
    @Test void legacyAndJournalRegistrationsCannotAdoptEachOthersOperations()throws Exception{
        var f=approved("success");f.execute();
        assertThrows(ApplicationException.class,()->PaymentServices.local(f.ds).runNext(f.session));assertEquals("PREPARING",f.state());assertEquals(0,f.providerCount("provider_compatibility_bindings"));
        var changed=new ExecutionBinding("simulator","other-account","eu-test","admission-config","1","fixture-credential-1","1","platform-owner");
        var replacement=new SyntheticProviderRegistration(f.session.tenantId(),"merchant-demo",f.session.runId(),changed,CLOCK,CLOCK.plusSeconds(600));
        assertThrows(ApplicationException.class,()->PaymentServices.providerJournal(f.ds,replacement).runNext(f.session));assertEquals(0,f.providerCount("provider_compatibility_bindings"));
        DataSource ds=database();Session s=isolated(ds);var legacy=PaymentServices.local(ds);UUID intent=preparing(legacy,s,"success");
        assertThrows(ApplicationException.class,()->PaymentServices.providerJournal(ds,registration(s)).runNext(s));assertEquals("PREPARING",legacy.status(s,intent).body().get("state"));
        legacy.runNext(s);legacy.runNext(s);assertEquals("SUCCEEDED",legacy.status(s,intent).body().get("state"));
    }
    @Test void unsupportedChallengeCannotCreateOperationOrProviderBinding()throws Exception{
        var f=approved("challenge-then-success");assertThrows(ApplicationException.class,f::execute);
        assertEquals("APPROVED",f.state());assertEquals("HELD",f.exposure());assertEquals(0,f.count("financial_operations"));assertEquals(0,f.count("payment_operation_routes"));assertEquals(0,f.providerCount("provider_compatibility_bindings"));
    }
    @Test void revokedNativeAuthorityBlocksProviderClaimAfterEffectFreePreparation()throws Exception{
        var f=approved("success");f.execute();f.service.runNext(f.session);f.service.revoke(f.session,"fixture-grant-1",key(),new RevokeCommand("test-revocation"));f.service.runNext(f.session);
        assertEquals("CANCELLED",f.state());assertEquals("RELEASED",f.exposure());
        assertEquals(false,row(f.ds,"SELECT dispatch_claimed FROM provider_compatibility_bindings WHERE tenant_id=? AND run_id=?",f.session.tenantId(),f.session.runId().toString()).get("dispatch_claimed"));
        assertEquals(LookupResult.Status.NOT_FOUND,new JdbcSimulatorAdapter(f.ds).lookup(f.peerOperation().identity()).status());
    }
    @Test void missingRouteNeverFallsBackOrReconstructsOwnership()throws Exception{
        var f=approved("success");f.execute();
        try(var c=f.ds.getConnection();var p=c.prepareStatement("DELETE FROM payment_operation_routes WHERE tenant_id=? AND run_id=?")){p.setString(1,f.session.tenantId());p.setObject(2,f.session.runId());p.executeUpdate();}
        assertThrows(ApplicationException.class,()->f.service.runNext(f.session));assertThrows(ApplicationException.class,()->PaymentServices.local(f.ds).runNext(f.session));
        assertEquals("PREPARING",f.state());assertEquals("HELD",f.exposure());assertEquals(0,f.providerCount("provider_compatibility_bindings"));
    }
    @Test void lostAdmissionAcknowledgementDoesNotCreateJournalClaimAndGenuineFenceResolvesIt()throws Exception{
        var f=approved("success");f.execute();f.service.runNext(f.session);
        var fault=new WorkerAdmissionFaultDataSource(f.ds,f.session,true,"08006");
        assertThrows(ApplicationException.class,()->PaymentServices.providerJournal(fault,f.registration).runNext(f.session));assertNotNull(fault.witness);assertEquals("ADMITTED",f.state());
        assertEquals(false,row(f.ds,"SELECT dispatch_claimed FROM provider_compatibility_bindings WHERE tenant_id=? AND run_id=?",f.session.tenantId(),f.session.runId().toString()).get("dispatch_claimed"));
        f.service.runNext(f.session);assertEquals("UNKNOWN",f.state());assertEquals("HELD",f.exposure());
        f.service.reconcile(f.session,f.intent,true);assertEquals("FAILED",f.state());assertEquals("RELEASED",f.exposure());
        assertEquals(Outcome.NO_EFFECT,new JdbcSimulatorAdapter(f.ds).lookup(f.peerOperation().identity()).fact().orElseThrow().outcome());
    }
    @Test void durableJournalContradictionsFreezeCoreIncludingAfterTerminalSettlement()throws Exception{
        for(String scenario:List.of("success","delayed-success","definitive-no-effect")){
            var f=approved(scenario);f.execute();f.service.runNext(f.session);f.service.runNext(f.session);
            String state=f.state(),exposure=f.exposure();var op=f.binding().operation();
            var opposite=scenario.equals("definitive-no-effect")?ExecutionEvidence.State.PENDING:ExecutionEvidence.State.NO_EFFECT_FENCED;
            var source=opposite==ExecutionEvidence.State.NO_EFFECT_FENCED?ExecutionEvidence.Source.FENCE:ExecutionEvidence.Source.RECONCILIATION;
            var conflicting=new ExecutionEvidence(op,"contradiction-"+UUID.randomUUID(),Optional.empty(),source,CLOCK.plusSeconds(1),opposite,Optional.empty());
            assertEquals(ExecutionFailure.Code.EVIDENCE_CONFLICT,assertThrows(ExecutionFailure.class,()->f.journal().record(conflicting)).code());
            assertThrows(ProviderEvidenceConflict.class,()->f.service.reconcile(f.session,f.intent,false));
            assertEquals(state,f.state());assertEquals(exposure,f.exposure());
            assertEquals(true,row(f.ds,"SELECT frozen FROM budgets WHERE tenant_id=? AND run_id=? AND budget_id='budget-eur'",f.session.tenantId(),f.session.runId()).get("frozen"));
            assertEquals(1,f.count("disputed_exposure"));assertEquals(1250,scalar(f.ds,"SELECT minor_units FROM disputed_exposure WHERE tenant_id=? AND run_id=?",f.session.tenantId(),f.session.runId()));
            long audit=f.count("audit_events"),evidence=f.count("operation_evidence");
            assertThrows(ProviderEvidenceConflict.class,()->PaymentServices.providerJournal(f.ds,registration(f.session)).reconcile(f.session,f.intent,false));
            assertEquals(audit,f.count("audit_events"));assertEquals(evidence,f.count("operation_evidence"));assertEquals(1,f.count("disputed_exposure"));
        }
    }
    @Test void routeAndOperationPublicationRollBackTogetherOnCommandFailure()throws Exception{
        var f=approved("success");var fault=new ExecutionCommandFaultDataSource(f.ds,f.session,f.intent,true,"XX000");
        var worker=PaymentServices.providerJournal(fault,f.registration);
        assertThrows(ApplicationException.class,()->worker.execute(f.session,f.intent,key(),new ExecuteCommand(version(f.approved),uuid(f.approved.body().get("attemptId")))));
        assertNotNull(fault.witness);assertEquals(1,fault.witness.operations());assertEquals("PREPARING",fault.witness.state());
        assertEquals("APPROVED",f.state());assertEquals("HELD",f.exposure());assertEquals(0,f.count("financial_operations"));assertEquals(0,f.count("payment_operation_routes"));assertEquals(0,f.providerCount("provider_compatibility_bindings"));
        f.execute();f.service.runNext(f.session);f.service.runNext(f.session);assertEquals("SUCCEEDED",f.state());
    }
    @Test void abruptProcessLossAcrossAdmissionClaimPeerAndJournalNeverReacquiresSend()throws Exception{
        for(String phase:List.of("admission","claim","acceptance","journal")){
            var f=approved("success");f.execute();f.service.runNext(f.session);var identity=f.identity();
            assertEquals(73,child(f,phase));assertEquals("ADMITTED",f.state());assertEquals("HELD",f.exposure());assertEquals(0,f.count("operation_evidence"));
            boolean accepted=Set.of("acceptance","journal").contains(phase);
            var peer=new JdbcSimulatorAdapter(f.ds);assertEquals(accepted?LookupResult.Status.FOUND:LookupResult.Status.NOT_FOUND,peer.lookup(f.peerOperation().identity()).status());
            assertEquals(!phase.equals("admission"),row(f.ds,"SELECT dispatch_claimed FROM provider_compatibility_bindings WHERE tenant_id=? AND run_id=?",f.session.tenantId(),f.session.runId().toString()).get("dispatch_claimed"));
            assertEquals(0,child(f,"recover"));assertEquals(accepted?"SUCCEEDED":"UNKNOWN",f.state());assertEquals(accepted?"CONSUMED":"HELD",f.exposure());
            assertEquals(identity,f.identity());assertEquals(1,f.count("financial_operations"));assertEquals(1,f.providerCount("provider_compatibility_bindings"));
            if(!accepted){assertEquals(0,child(f,"fence"));assertEquals("FAILED",f.state());assertEquals("RELEASED",f.exposure());}
            assertEquals(accepted?Outcome.SUCCEEDED:Outcome.NO_EFFECT,peer.lookup(f.peerOperation().identity()).fact().orElseThrow().outcome());
            assertEquals(1,scalar(f.ds,"SELECT count(*) FROM simulator_operation_journal WHERE tenant_id=? AND run_id=?",f.peerOperation().identity().scope().tenantId(),f.peerOperation().identity().scope().runId()));
        }
    }
    @Test void concurrentObservationCannotCreateAnotherSenderAndOnlyPeerFenceStopsAnAdmittedSender()throws Exception{
        for(boolean beforeClaim:List.of(false,true)){
            var f=approved("success");f.execute();f.service.runNext(f.session);
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var sends=new AtomicInteger();
            var peer=new WorkerOwnershipIntegrationTest.Adapter(f.ds){public Fact execute(ChargeOperation operation){sends.incrementAndGet();if(!beforeClaim){entered.countDown();WorkerOwnershipIntegrationTest.await(release);}return super.execute(operation);}};
            var config=f.registration.configuration();var journal=new JdbcCompatibilityBindingJournal(f.ds,config);
            var bridge=new LocalFakeExecutionAdapter(peer,journal,config.binding(),config.tenantId(),config.merchantId(),config.runId(),CLOCK,CLOCK.plusSeconds(600),config::scenarioFor);
            FinancialSubmitter submit=request->{if(beforeClaim){entered.countDown();WorkerOwnershipIntegrationTest.await(release);}return bridge.submit(request);};
            var service=PaymentServices.providerJournal(f.ds,f.registration,bridge,submit,bridge,Optional.of(bridge),journal,NativeComposition.ports(new JdbcSignedArtifactStore(f.ds)));
            var pool=Executors.newSingleThreadExecutor();
            try{
                var delayed=pool.submit(()->service.runNext(f.session));WorkerOwnershipIntegrationTest.await(entered);
                assertEquals("ADMITTED",f.state());service.runNext(f.session);assertEquals("UNKNOWN",f.state());assertEquals("HELD",f.exposure());
                service.reconcile(f.session,f.intent,true);assertEquals("FAILED",f.state());assertEquals("RELEASED",f.exposure());
                release.countDown();delayed.get(15,TimeUnit.SECONDS);
                assertEquals(beforeClaim?0:1,sends.get());assertEquals("FAILED",f.state());assertEquals(1,f.count("financial_operations"));assertEquals(1,f.count("operation_evidence"));
                assertEquals(Outcome.NO_EFFECT,peer.lookup(f.peerOperation().identity()).fact().orElseThrow().outcome());
            }finally{release.countDown();pool.shutdownNow();}
        }
    }
    @Test void providerRegistrationCannotChangeApprovedCheckoutCredential()throws Exception{
        DataSource ds=database();Session s=isolated(ds);var service=PaymentServices.providerJournal(ds,registration(s));
        var q=quoted(service,s,"success");UUID id=uuid(q.body().get("intentId"));var a=service.approve(s,id,key(),approval(q));
        try(var c=ds.getConnection();var p=c.prepareStatement("UPDATE checkout_snapshots SET credential_reference='changed-credential' WHERE tenant_id=? AND run_id=?")){p.setString(1,s.tenantId());p.setObject(2,s.runId());p.executeUpdate();}
        assertThrows(ApplicationException.class,()->service.execute(s,id,key(),new ExecuteCommand(version(a),uuid(a.body().get("attemptId")))));
        assertEquals("APPROVED",service.status(s,id).body().get("state"));assertEquals(0,scalar(ds,"SELECT count(*) FROM financial_operations WHERE tenant_id=? AND run_id=?",s.tenantId(),s.runId()));
        assertEquals(0,scalar(ds,"SELECT count(*) FROM payment_operation_routes WHERE tenant_id=? AND run_id=?",s.tenantId(),s.runId()));
    }
    @Test void publicConstructionCannotPairLegacyExecutorWithProviderRegistration(){
        for(var constructor:JdbcPaymentStore.class.getConstructors()){
            var types=List.of(constructor.getParameterTypes());assertFalse(types.contains(PaymentAdapter.class)&&types.contains(ProviderExecutionRegistration.class));
        }
    }
    private static int child(Fixture f,String mode)throws Exception{
        Path javaExecutable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");
        var process=new ProcessBuilder(javaExecutable.toString(),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),ProviderAdmissionCrashProcess.class.getName(),mode,f.session.tenantId(),f.session.runId().toString(),f.intent.toString()).redirectErrorStream(true).start();
        try{
            assertTrue(process.waitFor(30,TimeUnit.SECONDS),"Provider recovery child exceeded deadline");
            String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            if(Set.of("recover","fence").contains(mode))assertEquals("PROVIDER_RECOVERY_OK",output.strip());else assertTrue(output.isBlank(),"Crash child must not expose payloads or configuration");
            return process.exitValue();
        }finally{if(process.isAlive())process.destroyForcibly().waitFor(5,TimeUnit.SECONDS);}
    }
}
