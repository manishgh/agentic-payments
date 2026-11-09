package io.agenticpayments.provider;

import io.agenticpayments.application.*;
import io.agenticpayments.infrastructure.*;
import io.agenticpayments.infrastructure.provider.*;
import io.agenticpayments.sdk.execution.v1.*;
import org.junit.jupiter.api.Test;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static io.agenticpayments.provider.ProviderEventTestSupport.body;
import static io.agenticpayments.provider.ProviderJournalTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL acceptance/routing for distinct synthetic providers; this does not enable core vendor execution. */
class ProviderHistoricalInboxRoutingIntegrationTest {
    record Route(ProviderEventConfiguration configuration,ExecutionOperation operation,byte[] key) {
        JdbcProviderEventInbox inbox(DataSource database) {
            return new JdbcProviderEventInbox(database,configuration,new HmacProviderEventAuthenticator(key),new SyntheticProviderEventDecoder(ProviderEventTestSupport::resource));
        }
        ProviderEventDeliveryRoutes.Resolved resolved(DataSource database,Map<String,ExecutionEvidence> observed) {
            var inbox=inbox(database);var c=configuration;var policy=new ProviderEventDeliveryQueue.Policy(c.policyReference(),c.policyVersion(),c.keyReference(),c.keyVersion());
            return new ProviderEventDeliveryRoutes.Resolved(ProviderEventDeliveryRoutes.EventRoute.from(operation,policy),delivery->{
                assertEquals(operation,delivery.operation());var reference=inbox.verify(new EventVerificationRequest(operation,delivery.envelopeReference().orElseThrow()));
                var evidence=inbox.normalize(operation,reference);observed.put(operation.fingerprint(),evidence);
            });
        }
        String accept(DataSource database,String event) {
            byte[] raw=body(operation,event,ExecutionEvidence.State.CAPTURED);return inbox(database).accept(operation,raw,sign(key,raw));
        }
    }
    static Route route(String tenant,String run,String provider,String revision) {
        var binding=new ExecutionBinding(provider,"shared-account","eu-test","event-config",revision,"synthetic-credential",revision,"owner");
        var offer=new ExecutionOffer(ExecutionOffer.VERSION,"synthetic-routed-events-1",provider,"1",List.of(new ExecutionOffer.Combination(
                "synthetic-card","synthetic-reference",Set.of(),Set.of(OperationPurpose.CHARGE),ExecutionOffer.PreparationEffect.EFFECT_FREE,
                Set.of(ExecutionOffer.Capability.VERIFIED_EVENTS),List.of(new ExecutionOffer.CurrencyLimit("EUR",2,1,999999)))));
        var config=new ProviderEventConfiguration(tenant,"merchant",run,binding,offer,"event-policy",revision,"event-key-"+provider,revision);
        var money=new ExactMoney("EUR",2,1250);var selection=CapabilitySelection.select(offer,new CapabilitySelection.Requirement(ExecutionOffer.VERSION,offer.profileVersion(),provider,"1",binding.accountRef(),binding.region(),
                "synthetic-card","synthetic-reference",Set.of(),OperationPurpose.CHARGE,ExecutionOffer.PreparationEffect.EFFECT_FREE,Set.of(ExecutionOffer.Capability.VERIFIED_EVENTS),money),binding);
        var identity=new ExecutionIdentity(tenant,"merchant",run,"intent-"+provider+revision,"attempt-"+provider+revision,"operation-"+provider+revision,"caller-"+provider+revision);
        return new Route(config,ExecutionOperation.create(identity,binding,selection,money,OperationPurpose.CHARGE),("synthetic-routing-"+provider+"-"+revision+"-test-only-key").getBytes(StandardCharsets.US_ASCII));
    }
    static String sign(byte[] key,byte[] raw) {
        try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(raw));}catch(Exception failure){throw new AssertionError(failure);}
    }
    static PaymentService.Session session(String tenant,UUID run){return new PaymentService.Session(tenant,run,"runner",Set.of("runner:write"),Set.of("merchant"),Set.of());}
    static ProviderEventDeliveryWork worker(DataSource database,PaymentService.Session session,List<Route> routes,Map<String,ExecutionEvidence> observed) {
        return PaymentServices.providerEventDeliveryWorker(database,session,"merchant",new ImmutableProviderEventDeliveryRoutes(routes.stream().map(route->route.resolved(database,observed)).toList(),List.of()));
    }
    static void drain(ProviderEventDeliveryWork worker){for(int n=0;n<12;n++)if(!worker.runOnce())return;fail("Mixed provider inbox delivery did not drain.");}
    @Test void sharedQueueRoutesDistinctProvidersAndExactHistoricalRevisionUnderConcurrentWorkers()throws Exception {
        var database=database();DatabaseMigrator.migrate(database);String tenant=ProviderEventTestSupport.tenant();UUID run=UUID.randomUUID();var session=session(tenant,run);
        var first=route(tenant,run.toString(),"synthetic-provider-one","1");var second=route(tenant,run.toString(),"synthetic-provider-two","1");var historical=route(tenant,run.toString(),"synthetic-provider-one","2");
        var routes=List.of(first,second,historical);var observed=new ConcurrentHashMap<String,ExecutionEvidence>();
        first.accept(database,"provider-scoped-id");second.accept(database,"provider-scoped-id");historical.accept(database,"revision-two-id");
        for(var route:routes)route.accept(database,"duplicate-"+route.operation.identity().operationId());
        try(var pool=Executors.newFixedThreadPool(3)) {
            var start=new CountDownLatch(1);var tasks=new ArrayList<Future<?>>();
            for(int n=0;n<3;n++)tasks.add(pool.submit(()->{start.await();drain(worker(database,session,routes,observed));return null;}));
            start.countDown();for(var task:tasks)task.get(30,TimeUnit.SECONDS);
        }
        drain(worker(database,session,routes,observed));assertEquals(3,observed.size());
        for(var route:routes){var evidence=observed.get(route.operation.fingerprint());assertEquals(route.operation,evidence.operation());assertEquals(ExecutionEvidence.State.CAPTURED,evidence.state());assertEquals(ExecutionEvidence.Source.VERIFIED_EVENT,evidence.source());}
        assertEquals(6,count(database,"SELECT count(*) FROM provider_event_delivery WHERE tenant_id=? AND status='DONE'",tenant));
        assertEquals(6,count(database,"SELECT count(*) FROM provider_verified_events WHERE tenant_id=?",tenant));assertEquals(0,count(database,"SELECT count(*) FROM provider_event_quarantine WHERE tenant_id=?",tenant));
        assertEquals(0,count(database,"SELECT count(*) FROM financial_operations WHERE tenant_id=?",tenant));assertEquals(0,count(database,"SELECT count(*) FROM simulator_operation_journal WHERE tenant_id=?",tenant));
    }
    @Test void unavailableProviderDoesNotFallBackToSameAccountOrNewerRevision() {
        var database=database();DatabaseMigrator.migrate(database);String tenant=ProviderEventTestSupport.tenant();UUID run=UUID.randomUUID();var session=session(tenant,run);
        var old=route(tenant,run.toString(),"synthetic-provider-one","1");var newer=route(tenant,run.toString(),"synthetic-provider-one","2");var other=route(tenant,run.toString(),"synthetic-provider-two","1");
        old.accept(database,"old-event");newer.accept(database,"new-event");other.accept(database,"other-event");var observed=new ConcurrentHashMap<String,ExecutionEvidence>();
        drain(worker(database,session,List.of(newer,other),observed));assertFalse(observed.containsKey(old.operation.fingerprint()));assertEquals(2,observed.size());
        var missing=row(database,"SELECT status,failure_code,attempts FROM provider_event_delivery WHERE tenant_id=? AND operation_fingerprint=?",tenant,old.operation.fingerprint());
        assertEquals("READY",missing.get("status"));assertEquals("CONFIGURATION_UNAVAILABLE",missing.get("failure_code"));assertTrue(((Number)missing.get("attempts")).intValue()>=1);
        execute(database,"UPDATE provider_event_delivery SET available_at=clock_timestamp()-interval '1 second' WHERE tenant_id=?",tenant);
        drain(worker(database,session,List.of(old,newer,other),observed));assertEquals(3,observed.size());assertEquals(old.operation,observed.get(old.operation.fingerprint()).operation());
        assertEquals(3,count(database,"SELECT count(*) FROM provider_event_delivery WHERE tenant_id=? AND status='DONE'",tenant));
    }
}
