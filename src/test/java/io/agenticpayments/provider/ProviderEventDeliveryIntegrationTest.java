package io.agenticpayments.provider;

import io.agenticpayments.application.*;
import io.agenticpayments.infrastructure.DatabaseMigrator;
import io.agenticpayments.infrastructure.provider.*;
import io.agenticpayments.sdk.execution.v1.*;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.Duration;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static io.agenticpayments.application.ProviderEventDeliveryQueue.*;
import static io.agenticpayments.provider.ProviderEventTestSupport.*;
import static io.agenticpayments.provider.ProviderJournalTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class ProviderEventDeliveryIntegrationTest {
    private record Fixture(DataSource database,ProviderEventConfiguration configuration,ExecutionOperation operation){
        Fixture(){this(ProviderJournalTestSupport.database(),ProviderEventTestSupport.configuration(tenant()));}
        Fixture(DataSource database,ProviderEventConfiguration configuration){this(database,configuration,ProviderEventTestSupport.operation(configuration,"first"));DatabaseMigrator.migrate(database);}
        JdbcProviderEventInbox inbox(){return ProviderEventTestSupport.inbox(database,configuration);}
        JdbcProviderEventDeliveryQueue queue(){return new JdbcProviderEventDeliveryQueue(database,configuration.tenantId(),configuration.merchantId(),configuration.runId());}
        String accept(String event){byte[] raw=body(operation,event,ExecutionEvidence.State.PENDING);return inbox().accept(operation,raw,sign(raw));}
        long envelopes(){return count(database,"SELECT count(*) FROM provider_event_envelopes WHERE tenant_id=?",configuration.tenantId());}
        long deliveries(){return count(database,"SELECT count(*) FROM provider_event_delivery WHERE tenant_id=?",configuration.tenantId());}
        void expire(Claim claim){execute(database,"UPDATE provider_event_delivery SET lease_until=clock_timestamp()-interval '1 second' WHERE delivery_ref=?",claim.delivery().deliveryReference());}
    }
    @Test void acceptanceAuthenticatesBeforeAtomicRetentionAndManualRetentionStaysManual(){
        var f=new Fixture();byte[] raw=body(f.operation,"event",ExecutionEvidence.State.PENDING);
        assertThrows(ExecutionFailure.class,()->f.inbox().accept(f.operation,raw,"0".repeat(64)));
        assertEquals(0,f.envelopes());assertEquals(0,f.deliveries());
        f.inbox().retain(f.operation,raw,sign(raw));assertEquals(0,f.deliveries());
        String receipt=f.accept("event");assertEquals(2,f.envelopes());assertEquals(1,f.deliveries());
        var claim=f.queue().claim(Duration.ofMinutes(1)).orElseThrow();assertEquals(receipt,claim.delivery().envelopeReference().orElseThrow());
        assertEquals(f.operation,claim.delivery().operation());assertEquals(8,claim.maxAttempts());assertEquals(1,claim.attempt());
        assertEquals(new Policy("event-policy","1","event-key","1"),claim.delivery().policy());
    }
    @Test void queueInsertFailureRollsBackRawAndLostCommitAcknowledgementLeavesBothDurable(){
        var f=new Fixture();byte[] raw=body(f.operation,"event",ExecutionEvidence.State.PENDING);
        DataSource failed=new ForwardingDataSource(f.database){@Override Connection wrap(Connection c){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            if(method.getName().equals("prepareStatement")&&args[0].toString().startsWith("INSERT INTO provider_event_delivery"))throw new SQLException("synthetic insert failure");return invoke(method,c,args);
        });}};
        assertThrows(ExecutionFailure.class,()->inbox(failed,f.configuration).accept(f.operation,raw,sign(raw)));
        assertEquals(0,f.envelopes());assertEquals(0,f.deliveries());
        DataSource lost=new ForwardingDataSource(f.database){@Override Connection wrap(Connection c){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            Object result=invoke(method,c,args);if(method.getName().equals("commit"))throw new SQLException("synthetic acknowledgement loss");return result;
        });}};
        assertThrows(ExecutionFailure.class,()->inbox(lost,f.configuration).accept(f.operation,raw,sign(raw)));
        assertEquals(1,f.envelopes());assertEquals(1,f.deliveries());
        f.accept("event");assertEquals(2,f.deliveries());
        var worker=new DeliverProviderEvents(f.queue(),delivery->{var ref=f.inbox().verify(new EventVerificationRequest(delivery.operation(),delivery.envelopeReference().orElseThrow()));f.inbox().normalize(delivery.operation(),ref);},Duration.ofMinutes(1),Duration.ofSeconds(1),Duration.ofMinutes(1));
        assertTrue(worker.runOnce());assertTrue(worker.runOnce());assertFalse(worker.runOnce());
        assertEquals(1,count(f.database,"SELECT count(*) FROM provider_verified_events WHERE tenant_id=?",f.configuration.tenantId()));
    }
    @Test void staleLeaseCannotAcknowledgeOrRetryAndExhaustedProcessDeathBecomesDead(){
        var f=new Fixture();f.accept("event");var first=f.queue().claim(Duration.ofMinutes(1)).orElseThrow();
        assertTrue(f.queue().claim(Duration.ofMinutes(1)).isEmpty());f.expire(first);
        assertFalse(f.queue().settle(first,Resolution.done()));
        var second=f.queue().claim(Duration.ofMinutes(1)).orElseThrow();assertEquals(2,second.attempt());assertNotEquals(first.leaseToken(),second.leaseToken());
        assertFalse(f.queue().settle(first,new Resolution(Disposition.RETRY,Failure.INTERNAL,Duration.ZERO)));
        var foreign=new JdbcProviderEventDeliveryQueue(f.database,tenant(),"merchant","run");assertFalse(foreign.settle(second,Resolution.done()));
        assertTrue(f.queue().settle(second,Resolution.done()));
        f.accept("second");var last=f.queue().claim(Duration.ofMinutes(1)).orElseThrow();
        execute(f.database,"UPDATE provider_event_delivery SET attempts=max_attempts,lease_until=clock_timestamp()-interval '1 second' WHERE delivery_ref=?",last.delivery().deliveryReference());
        assertTrue(f.queue().claim(Duration.ofMinutes(1)).isEmpty());
        var dead=row(f.database,"SELECT status,failure_code FROM provider_event_delivery WHERE delivery_ref=?",last.delivery().deliveryReference());
        assertEquals("DEAD",dead.get("status"));assertEquals("ATTEMPTS_EXHAUSTED",dead.get("failure_code"));
    }
    @Test void concurrentClaimsAreDistinctAndRetryDelayIsDurable()throws Exception{
        var f=new Fixture();for(int n=0;n<8;n++)f.accept("event-"+n);
        try(var executor=Executors.newFixedThreadPool(8)){
            var barrier=new CountDownLatch(1);var futures=new ArrayList<Future<Claim>>();
            for(int n=0;n<8;n++)futures.add(executor.submit(()->{barrier.await();return f.queue().claim(Duration.ofMinutes(1)).orElseThrow();}));
            barrier.countDown();var ids=new HashSet<String>();for(var result:futures){var claim=result.get(15,TimeUnit.SECONDS);ids.add(claim.delivery().deliveryReference());assertTrue(f.queue().settle(claim,new Resolution(Disposition.RETRY,Failure.TEMPORARILY_UNAVAILABLE,Duration.ofMinutes(1))));}
            assertEquals(8,ids.size());assertTrue(f.queue().claim(Duration.ofMinutes(1)).isEmpty());
        }
        execute(f.database,"UPDATE provider_event_delivery SET available_at=clock_timestamp()-interval '1 second' WHERE tenant_id=?",f.configuration.tenantId());
        assertEquals(2,f.queue().claim(Duration.ofMinutes(1)).orElseThrow().attempt());
    }
    @Test void conflictAfterAcknowledgedOriginalQueuesBothOwnersAndPreservesOriginalPolicies(){
        var f=new Fixture();String first=f.accept("same-event");f.inbox().verify(new EventVerificationRequest(f.operation,first));
        var completed=f.queue().claim(Duration.ofMinutes(1)).orElseThrow();assertTrue(f.queue().settle(completed,Resolution.done()));
        var binding=ProviderEventTestSupport.BINDING;
        var changed=new ExecutionBinding(binding.providerId(),binding.accountRef(),binding.region(),binding.configurationRef(),"2",binding.credentialRef(),"2",binding.executionOwnerId());
        var config=new ProviderEventConfiguration(f.configuration.tenantId(),"merchant","run",changed,ProviderEventTestSupport.OFFER,"event-policy","2","event-key","2");
        var second=ProviderEventTestSupport.operation(config,"second");var other=inbox(f.database,config);byte[] raw=body(second,"same-event",ExecutionEvidence.State.PENDING);
        var envelope=other.accept(second,raw,sign(raw));assertThrows(ProviderEvidenceConflict.class,()->other.verify(new EventVerificationRequest(second,envelope)));
        assertEquals(2,count(f.database,"SELECT count(*) FROM provider_event_delivery WHERE tenant_id=? AND kind='QUARANTINE'",config.tenantId()));
        var versions=new HashMap<String,String>();
        for(int n=0;n<2;n++){
            var job=f.queue().claim(Duration.ofMinutes(1)).orElseThrow();assertEquals(Kind.QUARANTINE,job.delivery().kind());
            versions.put(job.delivery().operation().fingerprint(),job.delivery().policy().keyVersion());
            // Current inbox can resolve a historical owner's authenticated dispute without its old key/configuration.
            other.requireQuarantined(job.delivery().operation());assertTrue(f.queue().settle(job,Resolution.done()));
        }
        assertEquals("1",versions.get(f.operation.fingerprint()));assertEquals("2",versions.get(second.fingerprint()));
        assertThrows(ExecutionFailure.class,()->other.requireQuarantined(ProviderEventTestSupport.operation(config,"forged")));
    }
    @Test void automaticDuplicateOfManualOwnerStillSchedulesBothConflictedOwners(){
        var f=new Fixture();var raw=body(f.operation,"same",ExecutionEvidence.State.PENDING);
        var manual=f.inbox().retain(f.operation,raw,sign(raw));f.inbox().verify(new EventVerificationRequest(f.operation,manual));
        var automatic=f.inbox().accept(f.operation,raw,sign(raw));f.inbox().verify(new EventVerificationRequest(f.operation,automatic));
        var claim=f.queue().claim(Duration.ofMinutes(1)).orElseThrow();assertTrue(f.queue().settle(claim,Resolution.done()));
        var second=ProviderEventTestSupport.operation(f.configuration,"second");raw=body(second,"same",ExecutionEvidence.State.PENDING);
        var other=f.inbox().retain(second,raw,sign(raw));assertThrows(ProviderEvidenceConflict.class,()->f.inbox().verify(new EventVerificationRequest(second,other)));
        assertEquals(2,count(f.database,"SELECT count(*) FROM provider_event_delivery WHERE tenant_id=? AND kind='QUARANTINE'",f.configuration.tenantId()));
    }
    @Test void concurrentAcceptanceAndManualConflictCannotMissAutomaticParticipation()throws Exception{
        var f=new Fixture();var firstRaw=body(f.operation,"same",ExecutionEvidence.State.PENDING);
        var first=f.inbox().retain(f.operation,firstRaw,sign(firstRaw));f.inbox().verify(new EventVerificationRequest(f.operation,first));
        var second=ProviderEventTestSupport.operation(f.configuration,"second");var secondRaw=body(second,"same",ExecutionEvidence.State.PENDING);
        var disputed=f.inbox().retain(second,secondRaw,sign(secondRaw));
        var paused=new CountDownLatch(1);var release=new CountDownLatch(1);var conflictStarted=new CountDownLatch(1);
        DataSource accepting=new ForwardingDataSource(f.database){@Override Connection wrap(Connection c){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            if(method.getName().equals("prepareStatement")&&args[0].toString().startsWith("INSERT INTO provider_event_delivery")){paused.countDown();if(!release.await(15,TimeUnit.SECONDS))throw new SQLException("Test synchronization expired.");}
            return invoke(method,c,args);
        });}};
        DataSource conflicting=new ForwardingDataSource(f.database){@Override Connection wrap(Connection c){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            if(method.getName().equals("prepareStatement")&&args[0].toString().contains("pg_advisory_xact_lock"))conflictStarted.countDown();return invoke(method,c,args);
        });}};
        try(var pool=Executors.newFixedThreadPool(2)){
            var accepted=pool.submit(()->inbox(accepting,f.configuration).accept(f.operation,firstRaw,sign(firstRaw)));
            try{
                assertTrue(paused.await(15,TimeUnit.SECONDS));
                // Independent connection proves acceptance owns the operation serialization boundary.
                try(var c=f.database.getConnection();var p=c.prepareStatement("SELECT pg_try_advisory_xact_lock(hashtextextended(?,0))")){
                    c.setAutoCommit(false);p.setString(1,"provider-event-delivery:"+f.operation.fingerprint());try(var result=p.executeQuery()){assertTrue(result.next());assertFalse(result.getBoolean(1));}c.rollback();
                }
                var conflict=pool.submit(()->assertThrows(ProviderEvidenceConflict.class,()->inbox(conflicting,f.configuration).verify(new EventVerificationRequest(second,disputed))));
                assertTrue(conflictStarted.await(15,TimeUnit.SECONDS));release.countDown();assertNotNull(accepted.get(15,TimeUnit.SECONDS));conflict.get(15,TimeUnit.SECONDS);
            }finally{release.countDown();}
        }
        assertEquals(2,count(f.database,"SELECT count(*) FROM provider_event_delivery WHERE tenant_id=? AND kind='QUARANTINE'",f.configuration.tenantId()));
    }
    @Test void freshJvmRecoversAcceptedClaimedVerifiedAndPreAcknowledgementBoundaries()throws Exception{
        for(String phase:List.of("accepted","claimed","verified","before-ack")){
            var f=new Fixture();if(!phase.equals("accepted"))f.accept("event");
            assertEquals(73,child(f,phase));assertEquals(1,f.envelopes());assertEquals(1,f.deliveries());
            execute(f.database,"UPDATE provider_event_delivery SET lease_until=clock_timestamp()-interval '1 second' WHERE tenant_id=? AND status='LEASED'",f.configuration.tenantId());
            assertEquals(0,child(f,"recover"));
            assertEquals(1,count(f.database,"SELECT count(*) FROM provider_event_delivery WHERE tenant_id=? AND status='DONE'",f.configuration.tenantId()));
            assertEquals(1,count(f.database,"SELECT count(*) FROM provider_verified_events WHERE tenant_id=?",f.configuration.tenantId()));
        }
    }
    private static int child(Fixture f,String phase)throws Exception{
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var process=new ProcessBuilder(java,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),ProviderEventDeliveryCrashProcess.class.getName(),phase,f.configuration.tenantId()).redirectErrorStream(true).start();
        try{
            assertTrue(process.waitFor(30,TimeUnit.SECONDS));var output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            assertEquals(phase.equals("recover")?"PROVIDER_EVENT_DELIVERY_OK":"",output);return process.exitValue();
        }finally{if(process.isAlive())process.destroyForcibly().waitFor(5,TimeUnit.SECONDS);}
    }
}
