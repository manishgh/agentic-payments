package io.agenticpayments.provider;

import io.agenticpayments.application.*;
import io.agenticpayments.application.ProviderEventConfigurationStore.Configuration;
import io.agenticpayments.application.ProviderEventDeliveryQueue.Policy;
import io.agenticpayments.application.ProviderEventDeliveryRoutes.EventRoute;
import io.agenticpayments.domain.PaymentRules;
import io.agenticpayments.infrastructure.DatabaseMigrator;
import io.agenticpayments.infrastructure.provider.JdbcProviderEventConfigurations;
import io.agenticpayments.sdk.execution.v1.ExecutionBinding;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static io.agenticpayments.provider.ProviderJournalTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class ProviderEventConfigurationStoreIntegrationTest {
    static Configuration configuration(String tenant,String descriptor) {
        return new Configuration(new EventRoute(tenant,"merchant","run",new ExecutionBinding("provider-a","account","region","configuration","1","credential","1","owner"),
                new Policy("policy","1","key","1")),descriptor);
    }
    private String tenant(){return "event-config-"+UUID.randomUUID().toString().replace("-","");}
    private void failure(ApplicationFailure expected,Runnable action){assertEquals(expected,assertThrows(ApplicationException.class,action::run).failure());}
    @Test void verificationDoesNotEnrollAndRepeatedRegistrationSurvivesIndependentProcess()throws Exception {
        var database=database();DatabaseMigrator.migrate(database);var store=new JdbcProviderEventConfigurations(database);
        var config=configuration(tenant(),PaymentRules.sha256("immutable-nonsecret-descriptor"));
        failure(ApplicationFailure.UNSUPPORTED_CAPABILITY,()->store.verify(config));
        assertEquals(0,count(database,"SELECT count(*) FROM provider_event_configurations WHERE route_json::jsonb->>'tenantId'=?",config.route().tenantId()));
        store.register(config);var original=row(database,"SELECT * FROM provider_event_configurations WHERE route_json::jsonb->>'tenantId'=?",config.route().tenantId());
        store.register(config);new JdbcProviderEventConfigurations(database).verify(config);
        assertEquals(original,row(database,"SELECT * FROM provider_event_configurations WHERE route_json::jsonb->>'tenantId'=?",config.route().tenantId()));
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var process=new ProcessBuilder(java,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                ProviderEventConfigurationRestartProcess.class.getName(),config.route().tenantId(),config.descriptorDigest()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30,TimeUnit.SECONDS));
            assertEquals("EVENT_CONFIGURATION_VERIFIED",new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8));assertEquals(0,process.exitValue());
        } finally {if(process.isAlive())process.destroyForcibly().waitFor(5,TimeUnit.SECONDS);}
    }
    @Test void concurrentConflictingEnrollmentCommitsExactlyOneImmutableOwner()throws Exception {
        var database=database();DatabaseMigrator.migrate(database);var first=configuration(tenant(),PaymentRules.sha256("descriptor-a"));
        var second=new Configuration(first.route(),PaymentRules.sha256("descriptor-b"));var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var results=new ArrayList<Future<Optional<ApplicationFailure>>>();
            for(var config:List.of(first,second))results.add(pool.submit(()->{
                ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new AssertionError("Enrollment start unavailable");
                try {new JdbcProviderEventConfigurations(database).register(config);return Optional.empty();}
                catch(ApplicationException rejected){return Optional.of(rejected.failure());}
            }));
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();var outcomes=new ArrayList<Optional<ApplicationFailure>>();
            for(var result:results)outcomes.add(result.get(20,TimeUnit.SECONDS));
            assertEquals(1,outcomes.stream().filter(Optional::isEmpty).count());
            assertEquals(1,outcomes.stream().filter(v->v.equals(Optional.of(ApplicationFailure.CONFIGURATION_CHANGED))).count());
        }
        assertEquals(1,count(database,"SELECT count(*) FROM provider_event_configurations WHERE route_json::jsonb->>'tenantId'=?",first.route().tenantId()));
        String retained=row(database,"SELECT descriptor_digest FROM provider_event_configurations WHERE route_json::jsonb->>'tenantId'=?",first.route().tenantId()).get("descriptor_digest").toString();
        var winner=retained.equals(first.descriptorDigest())?first:second;var loser=winner==first?second:first;
        var restarted=new JdbcProviderEventConfigurations(database);restarted.verify(winner);restarted.register(winner);
        failure(ApplicationFailure.CONFIGURATION_CHANGED,()->restarted.verify(loser));failure(ApplicationFailure.CONFIGURATION_CHANGED,()->restarted.register(loser));
    }
    @Test void completeScopeBindingAndPolicyKeyAllowIndependentHistoricalCommitments() {
        var database=database();DatabaseMigrator.migrate(database);var base=configuration(tenant(),PaymentRules.sha256("base"));var r=base.route();var b=r.binding();
        var routes=new ArrayList<EventRoute>(List.of(r,new EventRoute(r.tenantId(),"other-merchant",r.runId(),b,r.policy()),
                new EventRoute(r.tenantId(),r.merchantId(),"other-run",b,r.policy())));
        for(var binding:List.of(new ExecutionBinding("provider-b",b.accountRef(),b.region(),b.configurationRef(),b.configurationVersion(),b.credentialRef(),b.credentialVersion(),b.executionOwnerId()),
                new ExecutionBinding(b.providerId(),"other-account",b.region(),b.configurationRef(),b.configurationVersion(),b.credentialRef(),b.credentialVersion(),b.executionOwnerId()),
                new ExecutionBinding(b.providerId(),b.accountRef(),"other-region",b.configurationRef(),b.configurationVersion(),b.credentialRef(),b.credentialVersion(),b.executionOwnerId()),
                new ExecutionBinding(b.providerId(),b.accountRef(),b.region(),"other-config",b.configurationVersion(),b.credentialRef(),b.credentialVersion(),b.executionOwnerId()),
                new ExecutionBinding(b.providerId(),b.accountRef(),b.region(),b.configurationRef(),"2",b.credentialRef(),b.credentialVersion(),b.executionOwnerId()),
                new ExecutionBinding(b.providerId(),b.accountRef(),b.region(),b.configurationRef(),b.configurationVersion(),"other-credential",b.credentialVersion(),b.executionOwnerId()),
                new ExecutionBinding(b.providerId(),b.accountRef(),b.region(),b.configurationRef(),b.configurationVersion(),b.credentialRef(),"2",b.executionOwnerId()),
                new ExecutionBinding(b.providerId(),b.accountRef(),b.region(),b.configurationRef(),b.configurationVersion(),b.credentialRef(),b.credentialVersion(),"other-owner")))
            routes.add(new EventRoute(r.tenantId(),r.merchantId(),r.runId(),binding,r.policy()));
        for(var policy:List.of(new Policy("other-policy","1","key","1"),new Policy("policy","2","key","1"),new Policy("policy","1","other-key","1"),new Policy("policy","1","key","2")))
            routes.add(new EventRoute(r.tenantId(),r.merchantId(),r.runId(),b,policy));
        var store=new JdbcProviderEventConfigurations(database);var configs=new ArrayList<Configuration>();
        for(int n=0;n<routes.size();n++){var config=new Configuration(routes.get(n),PaymentRules.sha256("descriptor-"+n));configs.add(config);store.register(config);}
        for(var config:configs)new JdbcProviderEventConfigurations(database).verify(config);
        assertEquals(routes.size(),count(database,"SELECT count(*) FROM provider_event_configurations WHERE route_json::jsonb->>'tenantId'=?",r.tenantId()));
        var otherTenant=new Configuration(new EventRoute("other-"+r.tenantId(),r.merchantId(),r.runId(),b,r.policy()),base.descriptorDigest());
        failure(ApplicationFailure.UNSUPPORTED_CAPABILITY,()->store.verify(otherTenant));store.register(otherTenant);store.verify(otherTenant);
    }
    @Test void alteredRetainedRouteFailsClosedAndStorageContainsOnlyDescriptorDigest() {
        var database=database();DatabaseMigrator.migrate(database);String secretCanary="descriptor-input-secret-CANARY";
        var config=configuration(tenant(),PaymentRules.sha256(secretCanary));var store=new JdbcProviderEventConfigurations(database);store.register(config);
        var retained=row(database,"SELECT * FROM provider_event_configurations WHERE route_json::jsonb->>'tenantId'=?",config.route().tenantId());
        assertEquals(config.descriptorDigest(),retained.get("descriptor_digest"));assertFalse(retained.toString().contains(secretCanary));
        assertEquals(Set.of("route_digest","route_json","descriptor_digest","registered_at"),retained.keySet());
        execute(database,"UPDATE provider_event_configurations SET route_json=? WHERE route_digest=?","{}",retained.get("route_digest"));
        failure(ApplicationFailure.CONFIGURATION_CHANGED,()->store.verify(config));failure(ApplicationFailure.CONFIGURATION_CHANGED,()->store.register(config));
        assertEquals("{}",row(database,"SELECT route_json FROM provider_event_configurations WHERE route_digest=?",retained.get("route_digest")).get("route_json"));
    }
}
