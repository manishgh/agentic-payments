package io.agenticpayments.core;

import io.agenticpayments.application.*;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.composition.NativeComposition;
import io.agenticpayments.infrastructure.*;
import io.agenticpayments.infrastructure.provider.*;
import io.agenticpayments.sdk.*;
import io.agenticpayments.sdk.execution.v1.*;
import io.agenticpayments.sdk.execution.v1.compat.LocalFakeExecutionAdapter;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.UnaryOperator;
import static io.agenticpayments.core.CoreTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native authority, durable authentication and the original financial operation across separate commits. */
class ProviderAuthenticationAdmissionIntegrationTest {
    static final ExecutionBinding BINDING=new ExecutionBinding("simulator","auth-admission-account","eu-test","auth-admission-config","1","fixture-credential-1","1","platform-owner");
    static SyntheticProviderRegistration registration(Session session) {
        return SyntheticProviderRegistration.interactions(session.tenantId(),"merchant-demo",session.runId(),BINDING,CLOCK,CLOCK.plusSeconds(600));
    }
    static PaymentService service(DataSource database,Session session,UnaryOperator<SyntheticAuthenticationDecisions> decorate) {
        var registration=registration(session);var configuration=registration.configuration();var journal=new JdbcCompatibilityBindingJournal(database,configuration);
        var peer=new JdbcSimulatorAdapter(database);var bridge=new LocalFakeExecutionAdapter(peer,journal,BINDING,configuration.tenantId(),configuration.merchantId(),configuration.runId(),
                registration.observedAt(),registration.actionExpiresAt(),configuration::scenarioFor,configuration.offer());
        var authentication=new JdbcSyntheticAuthentication(database,configuration);
        var eventConfiguration=new ProviderEventConfiguration(configuration.tenantId(),configuration.merchantId(),configuration.runId(),BINDING,configuration.offer(),"synthetic-event-policy","1","synthetic-event-key","1");
        var inbox=new JdbcProviderEventInbox(database,eventConfiguration,new HmacProviderEventAuthenticator("synthetic-auth-tests-only-key-32-bytes".getBytes(StandardCharsets.US_ASCII)),
                new SyntheticProviderEventDecoder(operation->"legacy-resource-"+configuration.expectedLegacy(operation).identity().adapterKey()));
        var interactions=new ProviderInteractionPorts(authentication,authentication,decorate.apply(authentication),inbox,inbox,inbox,inbox);
        return PaymentServices.providerInteractions(database,registration,bridge,bridge,bridge,Optional.of(bridge),journal,interactions,NativeComposition.ports(new JdbcSignedArtifactStore(database))).payments();
    }
    static final class Fixture {
        final DataSource database=database();final Session session=isolated(database);final AtomicInteger issuerCalls=new AtomicInteger();
        final PaymentService service=service(database,session,delegate->(binding,subject,request,decision,now)->{issuerCalls.incrementAndGet();delegate.decide(binding,subject,request,decision,now);});
        final UUID intent,attempt,action;
        Fixture() {
            var created=service.create(session,key(),new CreateCommand("merchant-demo","challenge-then-success",JdbcPaymentStore.NATIVE_PROFILE,List.of("single-tender","exact-money")));
            intent=uuid(created.body().get("intentId"));
            var quote=service.quote(session,intent,key(),new QuoteCommand(version(created),"cart-1250-eur",List.of(new Tender("simulator","synthetic-card","fixture-credential-1"))));
            var approved=service.approve(session,intent,key(),approval(quote));attempt=uuid(approved.body().get("attemptId"));
            service.execute(session,intent,key(),new ExecuteCommand(version(approved),attempt));service.runNext(session);
            assertEquals("WAITING_ACTION",state());action=uuid(service.status(session,intent).body().get("actionId"));
        }
        String state(){return service.status(session,intent).body().get("state").toString();}
        String exposure(){return service.status(session,intent).body().get("exposureState").toString();}
        Result complete(String command,String decision){return service.completeAction(session,action,command,new CompleteActionCommand(1,decision));}
        long coreCount(String table)throws Exception{return scalar(database,"SELECT count(*) FROM "+table+" WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId());}
        long providerCount(String table)throws Exception{return scalar(database,"SELECT count(*) FROM "+table+" WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId().toString());}
        ProviderExecutionBinding binding()throws Exception {
            var row=row(database,"SELECT binding_json FROM payment_operation_routes WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId());
            return new ProviderAdmissionCodec().decode(row.get("binding_json").toString());
        }
        Map<String,Object> reservation()throws Exception{return row(database,"SELECT * FROM provider_action_commands WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId());}
        long peerCharges()throws Exception {
            var identity=registration(session).configuration().expectedLegacy(binding().operation()).identity();
            return scalar(database,"SELECT count(*) FROM simulator_operation_journal WHERE tenant_id=? AND run_id=? AND adapter_key=?",identity.scope().tenantId(),identity.scope().runId(),identity.adapterKey());
        }
    }
    @Test void nativeChallengeCompletesDurablyBeforeOneOriginalFinancialDispatch()throws Exception {
        var fixture=new Fixture();var original=fixture.binding();String command=key();
        assertEquals(0,fixture.providerCount("provider_compatibility_evidence"));assertEquals(0,fixture.peerCharges());
        assertEquals(1,fixture.providerCount("provider_authentication_actions"));assertEquals("HELD",fixture.exposure());
        var accepted=fixture.complete(command,"approve");assertEquals("VERIFIED",accepted.body().get("state"));assertEquals("READY",fixture.state());
        assertEquals(1,fixture.coreCount("provider_action_commands"));assertEquals(1,fixture.issuerCalls.get());assertEquals(0,fixture.peerCharges());
        assertEquals(0,fixture.providerCount("provider_compatibility_evidence"));
        var replay=fixture.complete(command,"approve");assertTrue(replay.replayed());assertEquals(accepted.body().get("state"),replay.body().get("state"));
        assertEquals(accepted.body().get("actionId").toString(),replay.body().get("actionId").toString());assertEquals(1,fixture.issuerCalls.get());
        var reopened=service(fixture.database,fixture.session,UnaryOperator.identity());reopened.runNext(fixture.session);
        assertEquals("SUCCEEDED",fixture.state());assertEquals("CONSUMED",fixture.exposure());assertEquals(1,fixture.peerCharges());
        assertEquals(original,fixture.binding());assertEquals(1,fixture.coreCount("financial_operations"));
        reopened.reconcile(fixture.session,fixture.intent,false);assertEquals(1,fixture.peerCharges());
    }
    @Test void changedReservedRequestIsRejectedBeforeSecondIssuerCallAfterResponseLoss()throws Exception {
        var fixture=new Fixture();String command=key();var calls=new AtomicInteger();
        var losesResponse=service(fixture.database,fixture.session,delegate->(binding,subject,request,decision,now)->{
            calls.incrementAndGet();delegate.decide(binding,subject,request,decision,now);throw new IllegalStateException("lost-response-sensitive-canary");
        });
        var error=assertThrows(ApplicationException.class,()->losesResponse.completeAction(fixture.session,fixture.action,command,new CompleteActionCommand(1,"approve")));
        assertEquals(ApplicationFailure.TEMPORARILY_UNAVAILABLE_SAME_COMMAND,error.failure());assertNull(error.getCause());assertFalse(error.getMessage().contains("canary"));
        assertEquals("WAITING_ACTION",fixture.state());var reserved=fixture.reservation();
        assertThrows(ApplicationException.class,()->losesResponse.completeAction(fixture.session,fixture.action,command,new CompleteActionCommand(1,"deny")));
        assertThrows(ApplicationException.class,()->losesResponse.completeAction(fixture.session,fixture.action,key(),new CompleteActionCommand(1,"approve")));
        assertThrows(ApplicationException.class,()->losesResponse.completeAction(fixture.session,fixture.action,key(),new CompleteActionCommand(1,"deny")));
        assertEquals(1,calls.get());assertEquals(reserved,fixture.reservation());
        service(fixture.database,fixture.session,UnaryOperator.identity()).completeAction(fixture.session,fixture.action,command,new CompleteActionCommand(1,"approve"));
        assertEquals("READY",fixture.state());assertEquals(reserved,fixture.reservation());fixture.service.runNext(fixture.session);assertEquals(1,fixture.peerCharges());
    }
    @Test void concurrentDifferentCommandsCannotIssueOppositeProviderDecisions()throws Exception {
        var fixture=new Fixture();var barrier=new CyclicBarrier(2);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var results=new ArrayList<Future<Boolean>>();
            for(String decision:List.of("approve","deny"))results.add(executor.submit(()->{
                barrier.await(10,TimeUnit.SECONDS);try{fixture.complete(key(),decision);return true;}catch(ApplicationException rejected){return false;}
            }));
            int accepted=0;for(var result:results)if(result.get(20,TimeUnit.SECONDS))accepted++;
            assertEquals(1,accepted);assertEquals(1,fixture.issuerCalls.get());assertEquals(1,fixture.coreCount("provider_action_commands"));
            assertEquals(0,fixture.peerCharges());assertTrue(Set.of("READY","FAILED").contains(fixture.state()));
        }
    }
    @Test void deniedAuthenticationReleasesOnlyUnadmittedExposure()throws Exception {
        var fixture=new Fixture();fixture.complete(key(),"deny");assertEquals("FAILED",fixture.state());assertEquals("RELEASED",fixture.exposure());
        fixture.service.runNext(fixture.session);assertEquals(0,fixture.peerCharges());
        assertEquals(0,scalar(fixture.database,"SELECT count(*) FROM financial_operations WHERE tenant_id=? AND run_id=? AND admitted_sequence IS NOT NULL",fixture.session.tenantId(),fixture.session.runId()));
    }
    @Test void coreVerifiedFlagWithoutRetainedProviderAuthenticationCannotAdmit()throws Exception {
        var fixture=new Fixture();fixture.complete(key(),"approve");
        try(var connection=fixture.database.getConnection();var statement=connection.prepareStatement("UPDATE customer_action_provider_bindings SET authentication_json=NULL WHERE tenant_id=? AND run_id=?")) {
            statement.setString(1,fixture.session.tenantId());statement.setObject(2,fixture.session.runId());assertEquals(1,statement.executeUpdate());
        }
        assertThrows(ApplicationException.class,()->fixture.service.runNext(fixture.session));assertEquals("READY",fixture.state());assertEquals(0,fixture.peerCharges());
        assertEquals(0,scalar(fixture.database,"SELECT count(*) FROM financial_operations WHERE tenant_id=? AND run_id=? AND admitted_sequence IS NOT NULL",fixture.session.tenantId(),fixture.session.runId()));
    }
    @Test void wrongSubjectExpiryRevocationAndWrongCompositionNeverBypassAuthentication()throws Exception {
        var fixture=new Fixture();var other=new Session(fixture.session.tenantId(),fixture.session.runId(),"another-customer",fixture.session.roles(),fixture.session.allowedMerchants(),fixture.session.allowedGrants());
        assertThrows(ApplicationException.class,()->fixture.service.completeAction(other,fixture.action,key(),new CompleteActionCommand(1,"approve")));
        assertThrows(ApplicationException.class,()->PaymentServices.local(fixture.database).completeAction(fixture.session,fixture.action,key(),new CompleteActionCommand(1,"approve")));
        var oldRegistration=new SyntheticProviderRegistration(fixture.session.tenantId(),"merchant-demo",fixture.session.runId(),BINDING,CLOCK,CLOCK.plusSeconds(600));
        assertThrows(ApplicationException.class,()->PaymentServices.providerJournal(fixture.database,oldRegistration).completeAction(fixture.session,fixture.action,key(),new CompleteActionCommand(1,"approve")));
        assertEquals("WAITING_ACTION",fixture.state());assertEquals(0,fixture.issuerCalls.get());assertEquals(0,fixture.coreCount("provider_action_commands"));
        fixture.service.revoke(fixture.session,"fixture-grant-1",key(),new RevokeCommand("test-revocation"));
        assertThrows(ApplicationException.class,()->fixture.complete(key(),"approve"));assertEquals(0,fixture.issuerCalls.get());assertEquals(0,fixture.peerCharges());
        var expired=new Fixture();expired.service.advanceClock(expired.session,CLOCK.plusSeconds(600));
        assertThrows(ApplicationException.class,()->expired.complete(key(),"approve"));assertEquals(0,expired.issuerCalls.get());assertEquals(0,expired.peerCharges());
    }
    @Test void revocationAndExpiryAfterProviderDecisionCannotPublishDispatch()throws Exception {
        for(boolean revoked:List.of(true,false)) {
            var fixture=new Fixture();var completion=service(fixture.database,fixture.session,delegate->(binding,subject,request,decision,now)->{
                delegate.decide(binding,subject,request,decision,now);
                if(revoked)fixture.service.revoke(fixture.session,"fixture-grant-1",key(),new RevokeCommand("test-revocation"));
                else fixture.service.advanceClock(fixture.session,CLOCK.plusSeconds(600));
            });
            assertThrows(ApplicationException.class,()->completion.completeAction(fixture.session,fixture.action,key(),new CompleteActionCommand(1,"approve")));
            assertEquals(0,fixture.peerCharges());assertEquals(0,scalar(fixture.database,"SELECT count(*) FROM outbox WHERE tenant_id=? AND run_id=? AND kind='DISPATCH'",fixture.session.tenantId(),fixture.session.runId()));
            assertEquals(1,scalar(fixture.database,"SELECT count(*) FROM provider_authentication_actions WHERE tenant_id=? AND run_id=? AND observation_json IS NOT NULL",fixture.session.tenantId(),fixture.session.runId().toString()));
        }
    }
    @Test void coreRequestProviderDecisionAndCoreFinishRecoverAcrossJvmGaps()throws Exception {
        for(String mode:List.of("before-issuer","after-decision","after-core-finish")) {
            var fixture=new Fixture();String command=key();var original=fixture.binding();
            assertEquals(73,child(fixture,command,mode));var retained=fixture.reservation();
            assertEquals(mode.equals("after-core-finish")?"READY":"WAITING_ACTION",fixture.state());assertEquals(0,fixture.peerCharges());
            assertEquals(0,child(fixture,command,"recover"));assertEquals(retained,fixture.reservation());
            assertEquals("READY",fixture.state());fixture.service.runNext(fixture.session);assertEquals("SUCCEEDED",fixture.state());
            assertEquals(1,fixture.peerCharges());assertEquals(original,fixture.binding());assertEquals(1,fixture.coreCount("financial_operations"));
        }
    }
    private static int child(Fixture fixture,String command,String mode)throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var process=new ProcessBuilder(java,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),ProviderAuthenticationAdmissionCrashProcess.class.getName(),
                mode,fixture.session.tenantId(),fixture.session.runId().toString(),fixture.action.toString(),command).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30,TimeUnit.SECONDS),"Core authentication child exceeded deadline");
            String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            assertEquals(process.exitValue()==73?"":"AUTH_CORE_OK",output);return process.exitValue();
        }finally{if(process.isAlive())process.destroyForcibly().waitFor(5,TimeUnit.SECONDS);}
    }
}
