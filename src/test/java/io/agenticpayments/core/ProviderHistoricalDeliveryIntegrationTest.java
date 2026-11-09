package io.agenticpayments.core;

import io.agenticpayments.application.*;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.infrastructure.*;
import io.agenticpayments.infrastructure.provider.*;
import io.agenticpayments.sdk.*;
import io.agenticpayments.sdk.execution.v1.*;
import org.junit.jupiter.api.Test;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static io.agenticpayments.core.CoreTestSupport.*;
import static io.agenticpayments.core.ProviderEventAdmissionIntegrationTest.body;
import static org.junit.jupiter.api.Assertions.*;

/** One queue resolves complete historical configuration across independent synthetic accounts and revisions. */
class ProviderHistoricalDeliveryIntegrationTest {
    record Route(String account,String revision) {
        SyntheticProviderRegistration registration(Session session) {
            var binding=new ExecutionBinding("simulator",account,"eu-test","historical-config",revision,"fixture-credential-1",revision,"platform-owner");
            return SyntheticProviderRegistration.interactions(session.tenantId(),"merchant-demo",session.runId(),binding,CLOCK,CLOCK.plusSeconds(600));
        }
        ProviderEventConfiguration configuration(Session session) {
            var c=registration(session).configuration();
            return new ProviderEventConfiguration(c.tenantId(),c.merchantId(),c.runId(),c.binding(),c.offer(),"historical-policy",revision,"historical-key-"+account,revision);
        }
        byte[] keyBytes(){return ("synthetic-history-"+account+"-revision-"+revision+"-test-only").getBytes(StandardCharsets.US_ASCII);}
        ProviderEventRouteServices services(DataSource database,Session session) {
            return PaymentServices.providerEventDeliveryRoute(database,registration(session),configuration(session),new HmacProviderEventAuthenticator(keyBytes()),session);
        }
    }
    static final Route OLD=new Route("history-account","1"),CURRENT=new Route("history-account","2"),OTHER=new Route("other-account","1");
    record Scope(DataSource database,Session session) {
        Scope(){this(CoreTestSupport.database(),CoreTestSupport.session("provider-history-core",UUID.randomUUID()));DatabaseMigrator.migrate(database);PaymentServices.local(database).seedRun(session,CLOCK,20000);}
    }
    record Pending(Scope scope,Route route,ProviderEventRouteServices services,UUID intent,Result approved) {
        PaymentOperations payments(){return services.payments();}
        String state(){return payments().status(scope.session,intent).body().get("state").toString();}
        String exposure(){return payments().status(scope.session,intent).body().get("exposureState").toString();}
        ProviderExecutionBinding binding()throws Exception {
            var s=scope.session;var r=row(scope.database,"SELECT o.* FROM financial_operations o JOIN payment_attempts a USING(tenant_id,run_id,attempt_id) WHERE o.tenant_id=? AND o.run_id=? AND a.intent_id=?",s.tenantId(),s.runId(),intent);
            var identity=new OperationIdentity(new ExecutionScope(s.tenantId(),s.runId(),"merchant-demo"),intent,uuid(approved.body().get("attemptId")),(UUID)r.get("operation_id"),(UUID)r.get("adapter_key"),r.get("request_digest").toString());
            return new JdbcProviderExecutionBindings(scope.database).load(identity);
        }
        String accept(String event,ExecutionEvidence.State state)throws Exception {
            byte[] bytes=body(binding(),route.registration(scope.session),event,state);
            return services.acceptance().accept(scope.session,intent,bytes,sign(route.keyBytes(),bytes));
        }
    }
    static Pending pending(Scope scope,Route route) {
        var services=route.services(scope.database,scope.session);services.registerConfiguration();var payments=services.payments();var s=scope.session;
        var created=payments.create(s,key(),new CreateCommand("merchant-demo","delayed-success",JdbcPaymentStore.NATIVE_PROFILE,List.of("single-tender","exact-money")));
        UUID intent=uuid(created.body().get("intentId"));var quote=payments.quote(s,intent,key(),new QuoteCommand(version(created),"cart-1250-eur",List.of(new Tender("simulator","synthetic-card","fixture-credential-1"))));
        var approved=payments.approve(s,intent,key(),approval(quote));payments.execute(s,intent,key(),new ExecuteCommand(version(approved),uuid(approved.body().get("attemptId"))));payments.runNext(s);payments.runNext(s);
        return new Pending(scope,route,services,intent,approved);
    }
    static ProviderEventDeliveryWork worker(DataSource database,Session session,List<Route> routes) {
        var configured=routes.stream().map(route->route.services(database,session)).toList();
        var namespaces=new LinkedHashMap<ProviderEventDeliveryRoutes.Namespace,ProviderEventDeliveryRoutes.QuarantineResolved>();
        for(var service:configured) {
            var key=service.route().route();var namespace=new ProviderEventDeliveryRoutes.Namespace(key.tenantId(),key.merchantId(),key.runId(),key.binding().providerId(),key.binding().accountRef());
            namespaces.computeIfAbsent(namespace,n->PaymentServices.providerEventQuarantine(database,session,n));
        }
        return PaymentServices.providerEventDeliveryWorker(database,session,"merchant-demo",new ImmutableProviderEventDeliveryRoutes(configured.stream().map(ProviderEventRouteServices::route).toList(),namespaces.values()));
    }
    static void drain(ProviderEventDeliveryWork worker){for(int n=0;n<24;n++)if(!worker.runOnce())return;fail("Historical delivery did not drain its bounded workload.");}
    static void due(Scope scope)throws SQLException {
        try(var c=scope.database.getConnection();var p=c.prepareStatement("UPDATE provider_event_delivery SET available_at=clock_timestamp()-interval '1 second',lease_until=CASE WHEN status='LEASED' THEN clock_timestamp()-interval '1 second' ELSE NULL END WHERE tenant_id=? AND run_id=?")) {
            p.setString(1,scope.session.tenantId());p.setString(2,scope.session.runId().toString());p.executeUpdate();
        }
    }
    static long count(Scope scope,String table)throws SQLException{return scalar(scope.database,"SELECT count(*) FROM "+table+" WHERE tenant_id=? AND run_id=?",scope.session.tenantId(),table.startsWith("provider_")?scope.session.runId().toString():scope.session.runId());}
    static long peerCount(Pending pending)throws Exception {
        var peer=pending.route.registration(pending.scope.session).configuration().expectedLegacy(pending.binding().operation()).identity().scope();
        return scalar(pending.scope.database,"SELECT count(*) FROM simulator_operation_journal WHERE tenant_id=? AND run_id=?",peer.tenantId(),peer.runId());
    }
    static String sign(byte[] key,byte[] body) {
        try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(body));}catch(Exception failure){throw new AssertionError(failure);}
    }
    @Test void oneWorkerDeliversExactHistoricalPolicyAndIndependentAccountWithoutProviderIo()throws Exception {
        var scope=new Scope();var first=pending(scope,OLD);var second=pending(scope,CURRENT);var third=pending(scope,OTHER);
        var originals=List.of(first.binding(),second.binding(),third.binding());long submissions=peerCount(first);assertEquals(3,submissions);
        first.accept("same-account-scoped-id",ExecutionEvidence.State.CAPTURED);second.accept("current-capture",ExecutionEvidence.State.CAPTURED);third.accept("same-account-scoped-id",ExecutionEvidence.State.CAPTURED);
        var guarded=new ProviderAutomaticDeliveryIntegrationTest.DeliveryDataSource(scope.database,"no-provider");
        drain(worker(guarded,scope.session,List.of(OLD,CURRENT,OTHER)));
        for(var payment:List.of(first,second,third)){assertEquals("SUCCEEDED",payment.state());assertEquals("CONSUMED",payment.exposure());}
        assertEquals(originals,List.of(first.binding(),second.binding(),third.binding()));assertEquals(3,count(scope,"financial_operations"));assertEquals(submissions,peerCount(first));assertEquals(0,guarded.providerIo.get());
        assertEquals(3,scalar(scope.database,"SELECT count(*) FROM provider_event_delivery WHERE tenant_id=? AND run_id=? AND status='DONE'",scope.session.tenantId(),scope.session.runId().toString()));
    }
    @Test void missingHistoricalRouteRetriesWithoutUsingCurrentKeyAndRestorationCompletesOriginal()throws Exception {
        var scope=new Scope();var first=pending(scope,OLD);var second=pending(scope,CURRENT);var original=first.binding();first.accept("old-capture",ExecutionEvidence.State.CAPTURED);second.accept("new-capture",ExecutionEvidence.State.CAPTURED);
        drain(worker(scope.database,scope.session,List.of(CURRENT)));assertEquals("PENDING",first.state());assertEquals("HELD",first.exposure());assertEquals("SUCCEEDED",second.state());
        var missing=row(scope.database,"SELECT status,failure_code,attempts FROM provider_event_delivery WHERE tenant_id=? AND run_id=? AND operation_fingerprint=?",scope.session.tenantId(),scope.session.runId().toString(),original.operation().fingerprint());
        assertEquals("READY",missing.get("status"));assertEquals("CONFIGURATION_UNAVAILABLE",missing.get("failure_code"));assertTrue(((Number)missing.get("attempts")).intValue()>=1);
        assertEquals(1,count(scope,"provider_verified_events"));due(scope);drain(worker(scope.database,scope.session,List.of(OLD,CURRENT)));assertEquals("SUCCEEDED",first.state());assertEquals(original,first.binding());assertEquals(2,count(scope,"financial_operations"));
    }
    @Test void policyAndKeyRotationRoutesBothVersionsForTheSameImmutableOperation()throws Exception {
        var scope=new Scope();var payment=pending(scope,OLD);var original=payment.binding();var registration=OLD.registration(scope.session);var old=OLD.configuration(scope.session);
        var rotated=new ProviderEventConfiguration(old.tenantId(),old.merchantId(),old.runId(),old.binding(),old.offer(),old.policyReference(),"2",old.keyReference(),"2");
        byte[] rotatedKey="synthetic-policy-only-rotation-key-version-two".getBytes(StandardCharsets.US_ASCII);
        var current=PaymentServices.providerEventDeliveryRoute(scope.database,registration,rotated,new HmacProviderEventAuthenticator(rotatedKey),scope.session);current.registerConfiguration();
        byte[] pending=body(original,registration,"policy-one-pending",ExecutionEvidence.State.PENDING),capture=body(original,registration,"policy-two-capture",ExecutionEvidence.State.CAPTURED);
        assertEquals(ApplicationFailure.AUTHORITY_BINDING_INVALID,assertThrows(ApplicationException.class,()->payment.services.acceptance().accept(scope.session,payment.intent,pending,sign(rotatedKey,pending))).failure());
        assertEquals(ApplicationFailure.AUTHORITY_BINDING_INVALID,assertThrows(ApplicationException.class,()->current.acceptance().accept(scope.session,payment.intent,capture,sign(OLD.keyBytes(),capture))).failure());
        assertEquals(0,count(scope,"provider_event_envelopes"));assertEquals(0,count(scope,"provider_event_delivery"));
        payment.services.acceptance().accept(scope.session,payment.intent,pending,sign(OLD.keyBytes(),pending));
        current.acceptance().accept(scope.session,payment.intent,capture,sign(rotatedKey,capture));
        assertEquals("PENDING",payment.state());assertEquals(2,count(scope,"provider_event_delivery"));assertEquals(1,peerCount(payment));
        var guarded=new ProviderAutomaticDeliveryIntegrationTest.DeliveryDataSource(scope.database,"no-provider");
        var oldRoute=OLD.services(guarded,scope.session);
        var rotatedRoute=PaymentServices.providerEventDeliveryRoute(guarded,registration,rotated,new HmacProviderEventAuthenticator(rotatedKey),scope.session);
        assertEquals(oldRoute.route().route().binding(),rotatedRoute.route().route().binding());
        var worker=PaymentServices.providerEventDeliveryWorker(guarded,scope.session,"merchant-demo",new ImmutableProviderEventDeliveryRoutes(List.of(oldRoute.route(),rotatedRoute.route()),List.of()));
        assertTrue(worker.runOnce());assertEquals("PENDING",payment.state());assertTrue(worker.runOnce());assertEquals("SUCCEEDED",payment.state());assertFalse(worker.runOnce());
        assertEquals("CONSUMED",payment.exposure());assertEquals(original,payment.binding());assertEquals(1,count(scope,"financial_operations"));assertEquals(1,peerCount(payment));assertEquals(0,guarded.providerIo.get());
        assertEquals(2,count(scope,"provider_verified_events"));
        for(String version:List.of("1","2"))assertEquals(1,scalar(scope.database,"SELECT count(*) FROM provider_event_delivery WHERE tenant_id=? AND run_id=? AND status='DONE' AND policy_version=? AND key_version=? AND operation_fingerprint=?",scope.session.tenantId(),scope.session.runId().toString(),version,version,original.operation().fingerprint()));
    }
    @Test void retiredSigningConfigurationDoesNotBlockAuthenticatedHistoricalQuarantine()throws Exception {
        var scope=new Scope();var first=pending(scope,OLD);first.accept("shared-event",ExecutionEvidence.State.CAPTURED);drain(worker(scope.database,scope.session,List.of(OLD)));assertEquals("SUCCEEDED",first.state());
        var second=pending(scope,CURRENT);second.accept("shared-event",ExecutionEvidence.State.PENDING);
        // Only current event policy remains. Namespace dispute processing requires no old signing material.
        drain(worker(scope.database,scope.session,List.of(CURRENT)));
        assertEquals("SUCCEEDED",first.state());assertEquals("CONSUMED",first.exposure());assertEquals("PENDING",second.state());assertEquals("HELD",second.exposure());assertEquals(2,count(scope,"disputed_exposure"));
        assertEquals(2,scalar(scope.database,"SELECT count(*) FROM provider_event_delivery WHERE tenant_id=? AND run_id=? AND kind='QUARANTINE' AND status='DONE'",scope.session.tenantId(),scope.session.runId().toString()));
        assertEquals("1",row(scope.database,"SELECT key_version FROM provider_event_delivery WHERE tenant_id=? AND run_id=? AND kind='QUARANTINE' AND operation_fingerprint=?",scope.session.tenantId(),scope.session.runId().toString(),first.binding().operation().fingerprint()).get("key_version"));
    }
    @Test void concurrentGenericWorkersConvergeMixedJobsWithoutAnyAdditionalCharge()throws Exception {
        var scope=new Scope();var payments=List.of(pending(scope,OLD),pending(scope,CURRENT),pending(scope,OTHER),pending(scope,OTHER));var originals=new ArrayList<ProviderExecutionBinding>();
        for(int n=0;n<payments.size();n++){var payment=payments.get(n);originals.add(payment.binding());payment.accept("capture-"+n,ExecutionEvidence.State.CAPTURED);payment.accept("capture-"+n,ExecutionEvidence.State.CAPTURED);}
        long submitted=peerCount(payments.getFirst());assertEquals(4,submitted);var guarded=new ProviderAutomaticDeliveryIntegrationTest.DeliveryDataSource(scope.database,"no-provider");
        try(var pool=Executors.newFixedThreadPool(4)) {
            var start=new CountDownLatch(1);var tasks=new ArrayList<Future<?>>();
            for(int n=0;n<4;n++)tasks.add(pool.submit(()->{var worker=worker(guarded,scope.session,List.of(OLD,CURRENT,OTHER));start.await();drain(worker);return null;}));
            start.countDown();for(var task:tasks)task.get(30,TimeUnit.SECONDS);
        }
        drain(worker(guarded,scope.session,List.of(OLD,CURRENT,OTHER)));
        for(int n=0;n<payments.size();n++){assertEquals("SUCCEEDED",payments.get(n).state());assertEquals("CONSUMED",payments.get(n).exposure());assertEquals(originals.get(n),payments.get(n).binding());}
        assertEquals(4,count(scope,"financial_operations"));assertEquals(submitted,peerCount(payments.getFirst()));assertEquals(0,guarded.providerIo.get());
        assertEquals(8,scalar(scope.database,"SELECT count(*) FROM provider_event_delivery WHERE tenant_id=? AND run_id=? AND status='DONE'",scope.session.tenantId(),scope.session.runId().toString()));
    }
    @Test void freshJvmResolvesBothOriginalConfigurationsAcrossJournalAndCoreCrashGaps()throws Exception {
        for(String phase:List.of("journal","core")) {
            var scope=new Scope();var first=pending(scope,OLD);var second=pending(scope,CURRENT);var original=first.binding();
            first.accept("first-capture",ExecutionEvidence.State.CAPTURED);second.accept("second-capture",ExecutionEvidence.State.CAPTURED);
            assertEquals(73,child(scope,phase));due(scope);assertEquals(0,child(scope,"recover"));assertEquals("SUCCEEDED",first.state());assertEquals("SUCCEEDED",second.state());assertEquals(original,first.binding());
            assertEquals(2,count(scope,"financial_operations"));assertEquals(2,scalar(scope.database,"SELECT count(*) FROM provider_event_delivery WHERE tenant_id=? AND run_id=? AND status='DONE'",scope.session.tenantId(),scope.session.runId().toString()));
            long evidence=count(scope,"operation_evidence");assertEquals(0,child(scope,"recover"));assertEquals(evidence,count(scope,"operation_evidence"));
        }
    }
    @Test void registrationIsIdempotentButSameKeyCannotRebindHistoricalObservedTime()throws Exception {
        var scope=new Scope();var stable=OLD.services(scope.database,scope.session);stable.registerConfiguration();OLD.services(scope.database,scope.session).registerConfiguration();
        var original=OLD.registration(scope.session);var changed=SyntheticProviderRegistration.interactions(scope.session.tenantId(),"merchant-demo",scope.session.runId(),original.configuration().binding(),CLOCK.plusSeconds(1),CLOCK.plusSeconds(600));
        var rebound=PaymentServices.providerEventDeliveryRoute(scope.database,changed,OLD.configuration(scope.session),new HmacProviderEventAuthenticator(OLD.keyBytes()),scope.session);
        assertEquals(ApplicationFailure.CONFIGURATION_CHANGED,assertThrows(ApplicationException.class,rebound::registerConfiguration).failure());
        assertEquals(0,child(scope,"rebind"));
    }
    @Test void acceptingWithoutExplicitTrustedConfigurationRegistrationFailsClosed()throws Exception {
        var scope=new Scope();var services=OLD.services(scope.database,scope.session);
        // Admission uses the provider ports directly; automatic event acceptance additionally needs registered trusted metadata.
        var legacy=PaymentServices.providerInteractions(scope.database,OLD.registration(scope.session),OLD.configuration(scope.session),new HmacProviderEventAuthenticator(OLD.keyBytes()));
        var approved=ProviderEventAdmissionIntegrationTest.approved(scope.database,scope.session,legacy,"delayed-success");approved.dispatch();
        var binding=approved.binding();byte[] raw=body(binding,OLD.registration(scope.session),"unregistered",ExecutionEvidence.State.CAPTURED);
        assertEquals(ApplicationFailure.UNSUPPORTED_CAPABILITY,assertThrows(ApplicationException.class,()->services.acceptance().accept(scope.session,approved.intent(),raw,sign(OLD.keyBytes(),raw))).failure());
        assertEquals(0,count(scope,"provider_event_delivery"));assertEquals("PENDING",approved.state());
    }
    private static int child(Scope scope,String phase)throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var process=new ProcessBuilder(java,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),ProviderHistoricalDeliveryProcess.class.getName(),phase,scope.session.tenantId(),scope.session.runId().toString()).redirectErrorStream(true).start();
        try{assertTrue(process.waitFor(30,TimeUnit.SECONDS));assertEquals(Set.of("recover","rebind").contains(phase)?"PROVIDER_HISTORICAL_DELIVERY_OK":"",new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8));return process.exitValue();}
        finally{if(process.isAlive())process.destroyForcibly().waitFor(5,TimeUnit.SECONDS);}
    }
}
