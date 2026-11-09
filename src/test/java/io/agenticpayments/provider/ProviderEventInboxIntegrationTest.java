package io.agenticpayments.provider;

import io.agenticpayments.application.*;
import io.agenticpayments.infrastructure.DatabaseMigrator;
import io.agenticpayments.infrastructure.provider.*;
import io.agenticpayments.sdk.execution.v1.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static io.agenticpayments.provider.ProviderEventTestSupport.*;
import static io.agenticpayments.provider.ProviderJournalTestSupport.database;
import static io.agenticpayments.provider.ProviderJournalTestSupport.count;
import static io.agenticpayments.provider.ProviderJournalTestSupport.row;
import static org.junit.jupiter.api.Assertions.*;

class ProviderEventInboxIntegrationTest {
    private record Fixture(javax.sql.DataSource database,ProviderEventConfiguration configuration,ExecutionOperation operation){
        Fixture(){this(ProviderJournalTestSupport.database(),ProviderEventTestSupport.configuration(tenant()));}
        Fixture(javax.sql.DataSource database,ProviderEventConfiguration config){this(database,config,ProviderEventTestSupport.operation(config,"first"));DatabaseMigrator.migrate(database);}
        JdbcProviderEventInbox inbox(){return ProviderEventTestSupport.inbox(database,configuration);}
    }
    @Test void exactBytesStableDuplicateAndOriginalWeakerMeaningSurviveReconstruction(){
        var f=new Fixture();var inbox=f.inbox();byte[] body=body(f.operation,"pending",ExecutionEvidence.State.PENDING);byte[] original=body.clone();
        String retained=inbox.retain(f.operation,body,sign(body));body[0]='!';var verified=inbox.verify(new EventVerificationRequest(f.operation,retained));
        assertEquals(ExecutionEvidence.State.PENDING,inbox.normalize(f.operation,verified).state());
        String duplicate=inbox.retain(f.operation,original,sign(original));assertEquals(verified,f.inbox().verify(new EventVerificationRequest(f.operation,duplicate)));
        var capture=f.inbox().verify(new EventVerificationRequest(f.operation,retain(f.inbox(),f.operation,"capture",ExecutionEvidence.State.CAPTURED)));
        assertEquals(ExecutionEvidence.State.CAPTURED,f.inbox().normalize(f.operation,capture).state());
        assertEquals(ExecutionEvidence.State.PENDING,f.inbox().normalize(f.operation,verified).state());
        assertEquals(2,count(f.database,"SELECT count(*) FROM provider_verified_events WHERE tenant_id=?",f.configuration.tenantId()));
        assertArrayEquals(original,(byte[])row(f.database,"SELECT raw_body FROM provider_event_envelopes WHERE envelope_ref=?",retained).get("raw_body"));
    }
    @Test void signatureIsCheckedBeforeDecoderAndInvalidInputsCannotQuarantine(){
        var f=new Fixture();var calls=new AtomicInteger();var decoder=new SyntheticProviderEventDecoder(ProviderEventTestSupport::resource);
        var inbox=new JdbcProviderEventInbox(f.database,f.configuration,new HmacProviderEventAuthenticator(KEY),(op,raw)->{calls.incrementAndGet();return decoder.decode(op,raw);});
        byte[] raw="not-json-sensitive-canary".getBytes(StandardCharsets.US_ASCII);var ref=inbox.retain(f.operation,raw,"0".repeat(64));
        var failure=assertThrows(ExecutionFailure.class,()->inbox.verify(new EventVerificationRequest(f.operation,ref)));
        assertEquals(ExecutionFailure.Code.INVALID_EVENT,failure.code());assertEquals(0,calls.get());assertFalse(failure.toString().contains("canary"));
        assertThrows(ExecutionFailure.class,()->inbox.retain(f.operation,new byte[16385],"0".repeat(64)));
        inbox.requireHealthy(f.operation);assertEquals(0,count(f.database,"SELECT count(*) FROM provider_verified_events WHERE tenant_id=?",f.configuration.tenantId()));
    }
    @Test void signedWrongMoneyResourceAccountAndOperationRejectWithoutPoisoning(){
        var f=new Fixture();String valid=new String(body(f.operation,"event",ExecutionEvidence.State.CAPTURED),StandardCharsets.UTF_8);
        for(String changed:List.of(valid.replace("\"1250\"","\"1251\""),valid.replace(resource(f.operation),"wrong-resource"),valid.replace("event-account","wrong-account"),
                valid.replace(f.operation.fingerprint(),"sha256:"+"0".repeat(64)),valid.replace("CAPTURED","AUTHORIZED"),valid.replace("\"version\":","\"extra\":true,\"version\":"))){
            byte[] raw=changed.getBytes(StandardCharsets.UTF_8);String ref=f.inbox().retain(f.operation,raw,sign(raw));
            assertThrows(ExecutionFailure.class,()->f.inbox().verify(new EventVerificationRequest(f.operation,ref)));f.inbox().requireHealthy(f.operation);
        }
        assertEquals(0,count(f.database,"SELECT count(*) FROM provider_verified_events WHERE tenant_id=?",f.configuration.tenantId()));
    }
    @Test void referenceConstructionAndCrossOperationOrScopeReplayCannotEstablishProvenance(){
        var f=new Fixture();var fake=new VerifiedEventReference("fabricated","event",f.operation.fingerprint(),BINDING.digest());
        assertThrows(ExecutionFailure.class,()->f.inbox().normalize(f.operation,fake));
        String envelope=retain(f.inbox(),f.operation,"event",ExecutionEvidence.State.PENDING);var real=f.inbox().verify(new EventVerificationRequest(f.operation,envelope));
        var changed=new VerifiedEventReference(real.verifiedEventRef(),"other",f.operation.fingerprint(),BINDING.digest());assertThrows(ExecutionFailure.class,()->f.inbox().normalize(f.operation,changed));
        var other=operation(f.configuration,"second");assertThrows(ExecutionFailure.class,()->f.inbox().verify(new EventVerificationRequest(other,envelope)));
        var foreign=configuration(tenant());assertThrows(ExecutionFailure.class,()->inbox(f.database,foreign).verify(new EventVerificationRequest(f.operation,envelope)));
        var policy=new ProviderEventConfiguration(f.configuration.tenantId(),"merchant","run",BINDING,OFFER,"event-policy","2","event-key","1");
        assertThrows(ExecutionFailure.class,()->inbox(f.database,policy).normalize(f.operation,real));
        f.inbox().requireHealthy(f.operation);
    }
    @Test void exactPayloadMutationAndCrossOperationEventIdOwnershipRetainBothAndQuarantine(){
        for(boolean crossOperation:List.of(false,true)){
            var f=new Fixture();String first=retain(f.inbox(),f.operation,"same-event",ExecutionEvidence.State.PENDING);var verified=f.inbox().verify(new EventVerificationRequest(f.operation,first));
            var disputed=crossOperation?operation(f.configuration,"second"):f.operation;
            String second=retain(f.inbox(),disputed,"same-event",ExecutionEvidence.State.CAPTURED);
            assertThrows(ProviderEvidenceConflict.class,()->f.inbox().verify(new EventVerificationRequest(disputed,second)));
            assertThrows(ProviderEvidenceConflict.class,()->f.inbox().requireHealthy(f.operation));assertThrows(ProviderEvidenceConflict.class,()->f.inbox().requireHealthy(disputed));
            assertThrows(ProviderEvidenceConflict.class,()->f.inbox().normalize(f.operation,verified));
            assertEquals(1,count(f.database,"SELECT count(*) FROM provider_event_conflicts c JOIN provider_event_envelopes e ON c.disputed_envelope_ref=e.envelope_ref WHERE e.tenant_id=?",f.configuration.tenantId()));
            assertEquals(2,count(f.database,"SELECT count(*) FROM provider_event_envelopes WHERE tenant_id=?",f.configuration.tenantId()));
        }
    }
    @Test void authenticatedWhitespaceChangeUnderSameEventIdIsAnExactEnvelopeConflict(){
        var f=new Fixture();byte[] raw=body(f.operation,"same-event",ExecutionEvidence.State.PENDING);var first=f.inbox().retain(f.operation,raw,sign(raw));f.inbox().verify(new EventVerificationRequest(f.operation,first));
        byte[] changed=(new String(raw,StandardCharsets.UTF_8)+" ").getBytes(StandardCharsets.UTF_8);var second=f.inbox().retain(f.operation,changed,sign(changed));
        assertThrows(ProviderEvidenceConflict.class,()->f.inbox().verify(new EventVerificationRequest(f.operation,second)));
    }
    @Test void authenticatedEventIdCannotEscapeOwnershipByChangingConfigurationOrKeyPolicyRevision(){
        var f=new Fixture();String first=retain(f.inbox(),f.operation,"same-event",ExecutionEvidence.State.PENDING);f.inbox().verify(new EventVerificationRequest(f.operation,first));
        var changedBinding=new ExecutionBinding(BINDING.providerId(),BINDING.accountRef(),BINDING.region(),BINDING.configurationRef(),"2",BINDING.credentialRef(),"2",BINDING.executionOwnerId());
        var changedConfig=new ProviderEventConfiguration(f.configuration.tenantId(),"merchant","run",changedBinding,OFFER,"event-policy","2","event-key","2");
        var changedOperation=operation(changedConfig,"second");var changedInbox=inbox(f.database,changedConfig);
        String second=retain(changedInbox,changedOperation,"same-event",ExecutionEvidence.State.PENDING);
        assertThrows(ProviderEvidenceConflict.class,()->changedInbox.verify(new EventVerificationRequest(changedOperation,second)));
        assertThrows(ProviderEvidenceConflict.class,()->f.inbox().requireHealthy(f.operation));
        assertThrows(ProviderEvidenceConflict.class,()->changedInbox.requireHealthy(changedOperation));
        assertEquals(2,count(f.database,"SELECT count(*) FROM provider_event_quarantine WHERE tenant_id=?",f.configuration.tenantId()));
    }
    @Test void concurrentDuplicatesHaveOneStableVerifiedOwner()throws Exception{
        var f=new Fixture();var refs=new ArrayList<String>();for(int n=0;n<8;n++)refs.add(retain(f.inbox(),f.operation,"same",ExecutionEvidence.State.CAPTURED));
        try(var pool=Executors.newFixedThreadPool(8)){
            var start=new CountDownLatch(1);var futures=new ArrayList<Future<VerifiedEventReference>>();for(String ref:refs)futures.add(pool.submit(()->{start.await();return f.inbox().verify(new EventVerificationRequest(f.operation,ref));}));
            start.countDown();var unique=new HashSet<VerifiedEventReference>();for(var future:futures)unique.add(future.get(15,TimeUnit.SECONDS));assertEquals(1,unique.size());
        }
        assertEquals(1,count(f.database,"SELECT count(*) FROM provider_verified_events WHERE tenant_id=?",f.configuration.tenantId()));
    }
    @Test void authenticConflictGatesEveryExecutionResponsibilityBeforeDelegate(){
        var f=new Fixture();String first=retain(f.inbox(),f.operation,"same",ExecutionEvidence.State.PENDING);f.inbox().verify(new EventVerificationRequest(f.operation,first));
        String second=retain(f.inbox(),f.operation,"same",ExecutionEvidence.State.CAPTURED);assertThrows(ProviderEvidenceConflict.class,()->f.inbox().verify(new EventVerificationRequest(f.operation,second)));
        var guards=new ProviderEventGuards(f.inbox());var invoked=new AtomicInteger();
        assertThrows(ProviderEvidenceConflict.class,()->guards.preparer(op->{invoked.incrementAndGet();return null;}).prepare(f.operation));
        assertThrows(ProviderEvidenceConflict.class,()->guards.submitter(request->{invoked.incrementAndGet();return null;}).submit(new SubmissionRequest(f.operation,Optional.empty())));
        assertThrows(ProviderEvidenceConflict.class,()->guards.reconciler(op->{invoked.incrementAndGet();return null;}).reconcile(f.operation));
        assertThrows(ProviderEvidenceConflict.class,()->guards.fencer(op->{invoked.incrementAndGet();return null;}).fence(f.operation));
        var guardedEvidence=guards.evidenceVerifier(new ProviderEvidenceVerifier(){
            public ExecutionEvidence verify(ExecutionOperation op,ExecutionEvidence received){invoked.incrementAndGet();return received;}
            public ExecutionEvidence retained(ExecutionOperation op,ExecutionEvidence.State state){invoked.incrementAndGet();return null;}
        });
        assertThrows(ProviderEvidenceConflict.class,()->guardedEvidence.verify(f.operation,null));
        assertThrows(ProviderEvidenceConflict.class,()->guardedEvidence.retained(f.operation,ExecutionEvidence.State.CAPTURED));
        assertEquals(0,invoked.get());
    }
    @Test void freshJvmRecoversRawAndVerifiedCommitBoundaries()throws Exception{
        for(String phase:List.of("raw","verified")){
            var f=new Fixture();assertEquals(73,child(f,phase));
            var stored=row(f.database,"SELECT envelope_ref FROM provider_event_envelopes WHERE tenant_id=?",f.configuration.tenantId());assertNotNull(stored);
            var ref=f.inbox().verify(new EventVerificationRequest(f.operation,stored.get("envelope_ref").toString()));assertEquals(ExecutionEvidence.State.PENDING,f.inbox().normalize(f.operation,ref).state());
            assertEquals(1,count(f.database,"SELECT count(*) FROM provider_verified_events WHERE tenant_id=?",f.configuration.tenantId()));
            assertEquals(0,child(f,"replay"));
        }
    }
    private static int child(Fixture f,String phase)throws Exception{
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var child=new ProcessBuilder(java,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),ProviderEventCrashProcess.class.getName(),phase,f.configuration.tenantId()).redirectErrorStream(true).start();
        try{assertTrue(child.waitFor(30,TimeUnit.SECONDS));var output=new String(child.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertEquals(phase.equals("replay")?"PROVIDER_EVENT_OK":"",output);return child.exitValue();}
        finally{if(child.isAlive())child.destroyForcibly().waitFor(5,TimeUnit.SECONDS);}
    }
}
