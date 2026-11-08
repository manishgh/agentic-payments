package io.agenticpayments.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agenticpayments.infrastructure.provider.*;
import io.agenticpayments.sdk.*;
import io.agenticpayments.sdk.execution.v1.*;
import io.agenticpayments.sdk.execution.v1.compat.*;
import io.agenticpayments.provider.ProviderJournalTestSupport.Observer;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static io.agenticpayments.provider.ProviderJournalTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independently authored PostgreSQL/child-process witnesses; no in-memory peer durability claims. */
class IndependentProviderJournalIntegrationTest {
    @Test void completeImmutableMappingAndNanosecondEvidenceSurviveReconstruction(){
        Fixture f=new Fixture();var journal=f.journal();assertEquals(f.legacy,f.config.expectedLegacy(f.operation));assertEquals(f.legacy,journal.bind(f.operation,f.legacy));
        var original=immutable(f.binding());assertEquals(f.operation.fingerprint(),original.get("operation_fingerprint"));
        assertEquals(f.legacy.identity().adapterKey(),original.get("legacy_adapter_key"));assertEquals(f.legacy.identity().scope().runId(),original.get("legacy_run_id"));
        assertEquals("provider-compatibility-json-1",original.get("codec_version"));assertEquals(f.legacy,f.journal().bind(f.operation,f.legacy));assertEquals(original,immutable(f.binding()));
        var fact=evidence(f.operation,"nanos-original",ExecutionEvidence.State.CAPTURED);assertEquals(fact,journal.record(fact));assertEquals(fact,f.journal().read(f.operation).orElseThrow());
        assertEquals(fact,journal.record(fact));assertEquals(1,f.evidence());
        var stored=row(f.database,"SELECT observed_at,state,source FROM provider_compatibility_evidence WHERE tenant_id=? AND merchant_id=? AND run_id=? AND caller_key=?",namespace(f.operation));
        assertEquals(NOW.toString(),stored.get("observed_at"));assertEquals("CAPTURED",stored.get("state"));assertEquals(original,immutable(f.binding()));
        assertEquals(0,count(f.database,"SELECT count(*) FROM payment_intents WHERE tenant_id=?",f.config.tenantId()));assertEquals(0,f.peerRows());
    }
    @Test void completeIdentityAndMappingMutationsCannotReuseAcceptedNamespace(){
        Fixture f=new Fixture();f.journal().bind(f.operation,f.legacy);var original=immutable(f.binding());var id=f.operation.identity();
        for(var changed:List.of(
                new ExecutionIdentity(id.tenantId(),id.merchantId(),id.runId(),"other-intent",id.attemptId(),id.operationId(),id.callerIdempotencyKey()),
                new ExecutionIdentity(id.tenantId(),id.merchantId(),id.runId(),id.intentId(),"other-attempt",id.operationId(),id.callerIdempotencyKey()),
                new ExecutionIdentity(id.tenantId(),id.merchantId(),id.runId(),id.intentId(),id.attemptId(),"other-operation",id.callerIdempotencyKey()))){
            var op=operation(f.config,changed,f.operation.money());rejected(()->f.journal().bind(op,legacy(op,"success")));
        }
        for(var money:List.of(new ExactMoney("EUR",2,1251),new ExactMoney("USD",2,1250))){var op=operation(f.config,id,money);rejected(()->f.journal().bind(op,legacy(op,"success")));}
        ChargeOperation wrongScenario=new ChargeOperation(f.legacy.identity(),f.legacy.money(),"delayed-success");rejected(()->f.journal().bind(f.operation,wrongScenario));
        var wrongLegacyId=new OperationIdentity(f.legacy.identity().scope(),UUID.randomUUID(),f.legacy.identity().attemptId(),f.legacy.identity().operationId(),f.legacy.identity().adapterKey(),f.legacy.identity().requestDigest());
        rejected(()->f.journal().bind(f.operation,new ChargeOperation(wrongLegacyId,f.legacy.money(),"success")));
        for(var changedConfig:List.of(
                new ProviderJournalConfiguration(f.config.tenantId(),f.config.merchantId(),f.config.runId(),BINDING,ProviderJournalConfiguration.MAPPING_POLICY_VERSION,"other-policy","1",ignored->"success"),
                new ProviderJournalConfiguration(f.config.tenantId(),f.config.merchantId(),f.config.runId(),BINDING,ProviderJournalConfiguration.MAPPING_POLICY_VERSION,"independent-scenario-policy","2",ignored->"success"))){
            rejected(()->new JdbcCompatibilityBindingJournal(f.database,changedConfig).bind(f.operation,f.legacy));
        }
        assertEquals(original,immutable(f.binding()));assertEquals(1,f.bindings());assertEquals(0,f.peerRows());assertFalse((Boolean)f.binding().get("conflicted"));
    }
    @Test void operationAndLegacyAliasesCannotCreateAnotherNamespaceWhileSeparateScopesCanReuseText(){
        Fixture f=new Fixture();f.journal().bind(f.operation,f.legacy);var id=f.operation.identity();
        var aliasId=new ExecutionIdentity(id.tenantId(),id.merchantId(),id.runId(),id.intentId(),id.attemptId(),id.operationId(),"another-caller");
        var alias=operation(f.config,aliasId,f.operation.money());rejected(()->f.journal().bind(alias,legacy(alias,"success")));
        assertEquals(1,f.bindings());assertTrue(count(f.database,"SELECT count(*) FROM provider_compatibility_conflicts WHERE requested_tenant_id=? AND requested_merchant_id=? AND requested_run_id=? AND requested_caller_key=?",namespace(alias))>=1);
        var other=operation(f.config,"other-key","other-operation");rejected(()->f.journal().bind(other,f.legacy));assertEquals(1,f.bindings());
        var config=configuration(f.config.tenantId()+"-other",f.config.merchantId(),f.config.runId());var scoped=operation(config,id.callerIdempotencyKey(),id.operationId());
        var journal=new JdbcCompatibilityBindingJournal(f.database,config);assertEquals(legacy(scoped,"success"),journal.bind(scoped,legacy(scoped,"success")));assertTrue(journal.claim(new SubmissionRequest(scoped,Optional.empty())).firstDispatchOwner());
        assertFalse((Boolean)f.binding().get("dispatch_claimed"));assertEquals(0,f.peerRows());
    }
    @Test void directWrongScopeRefusesBeforeOpeningDatabaseConnection(){
        Fixture f=new Fixture();AtomicInteger connections=new AtomicInteger();DataSource counted=new ForwardingDataSource(f.database){@Override Connection wrap(Connection c){connections.incrementAndGet();return c;}};
        var journal=new JdbcCompatibilityBindingJournal(counted,f.config);var otherConfig=configuration("other-tenant",f.config.merchantId(),f.config.runId());var other=operation(otherConfig,"caller-primary","operation-primary");
        rejected(()->journal.bind(other,legacy(other,"success")));rejected(()->journal.claim(new SubmissionRequest(other,Optional.empty())));rejected(()->journal.read(other));rejected(()->journal.record(evidence(other,"wrong-scope",ExecutionEvidence.State.UNKNOWN)));
        assertEquals(0,connections.get());assertEquals(0,f.bindings());
    }
    @Test void anyPriorEvidenceBlocksFirstOwnerAndPendingHistorySurvivesLaterUnknown(){
        for(var state:List.of(ExecutionEvidence.State.UNKNOWN,ExecutionEvidence.State.PENDING,ExecutionEvidence.State.CAPTURED,ExecutionEvidence.State.NO_EFFECT_FENCED)){
            Fixture f=new Fixture();var journal=f.journal();journal.bind(f.operation,f.legacy);var first=evidence(f.operation,"before-owner",state);journal.record(first);
            if(state==ExecutionEvidence.State.PENDING){journal.record(evidence(f.operation,"later-unknown",ExecutionEvidence.State.UNKNOWN));assertEquals(first,journal.read(f.operation).orElseThrow());}
            var claim=journal.claim(new SubmissionRequest(f.operation,Optional.empty()));assertFalse(claim.firstDispatchOwner());assertTrue(claim.knownEvidence().isPresent());
            assertFalse((Boolean)f.binding().get("dispatch_claimed"));assertTrue((Boolean)f.binding().get("dispatch_blocked"));
            f.bridge().submit(new SubmissionRequest(f.operation,Optional.empty()));assertEquals(0,f.invocations("EXECUTE"));assertEquals(0,f.peerRows());
        }
    }
    @Test void sameEvidenceIdChangedFullPayloadCommitsConflictAndPermanentlyQuarantines(){
        for(int field=0;field<4;field++){
            Fixture f=new Fixture();var journal=f.journal();journal.bind(f.operation,f.legacy);var original=evidence(f.operation,"same-id",ExecutionEvidence.State.PENDING);journal.record(original);
            var changed=new ExecutionEvidence(f.operation,original.evidenceId(),field==2?Optional.of("other-resource"):original.providerResourceRef(),
                    field==0?ExecutionEvidence.Source.SUBMIT:original.source(),field==1?NOW.plusNanos(1):original.observedAt(),field==3?ExecutionEvidence.State.UNKNOWN:original.state(),Optional.empty());
            conflict(()->journal.record(changed));assertEquals(1,f.evidence());assertTrue(f.conflicts()>=1);assertTrue((Boolean)f.binding().get("conflicted"));
            conflict(()->f.journal().read(f.operation));conflict(()->f.journal().claim(new SubmissionRequest(f.operation,Optional.empty())));conflict(()->f.journal().bind(f.operation,f.legacy));
            var receipt=row(f.database,"SELECT candidate_evidence_json,code FROM provider_compatibility_conflicts WHERE requested_tenant_id=? AND requested_merchant_id=? AND requested_run_id=? AND requested_caller_key=?",namespace(f.operation));
            assertNotNull(receipt.get("candidate_evidence_json"));assertEquals("EVIDENCE_CONFLICT",receipt.get("code"));assertEquals(0,f.peerRows());
        }
    }
    @Test void terminalProjectionCannotBeWeakenedAndPendingFenceContradictionsQuarantineBothOrders(){
        Fixture f=new Fixture();var journal=f.journal();journal.bind(f.operation,f.legacy);var capture=evidence(f.operation,"capture-first",ExecutionEvidence.State.CAPTURED);journal.record(capture);
        journal.record(evidence(f.operation,"late-unknown",ExecutionEvidence.State.UNKNOWN));journal.record(evidence(f.operation,"late-pending",ExecutionEvidence.State.PENDING));
        var repeated=new ExecutionEvidence(f.operation,"capture-later",capture.providerResourceRef(),ExecutionEvidence.Source.SUBMIT,NOW.plusSeconds(1),ExecutionEvidence.State.CAPTURED,Optional.empty());assertEquals(capture,journal.record(repeated));
        assertEquals(capture,f.journal().read(f.operation).orElseThrow());assertEquals(4,f.evidence());
        for(boolean pendingFirst:List.of(false,true)){
            Fixture pair=new Fixture();pair.journal().bind(pair.operation,pair.legacy);
            pair.journal().record(evidence(pair.operation,"first",pendingFirst?ExecutionEvidence.State.PENDING:ExecutionEvidence.State.NO_EFFECT_FENCED));
            conflict(()->pair.journal().record(evidence(pair.operation,"second",pendingFirst?ExecutionEvidence.State.NO_EFFECT_FENCED:ExecutionEvidence.State.PENDING)));
            assertTrue((Boolean)pair.binding().get("conflicted"));assertTrue(pair.evidence()+pair.conflicts()>=2);conflict(()->pair.journal().read(pair.operation));
        }
    }
    @Test void originalProviderResourceCannotTransferToAnotherOperation(){
        Fixture f=new Fixture();var journal=f.journal();journal.bind(f.operation,f.legacy);var first=evidence(f.operation,"resource-owner",ExecutionEvidence.State.CAPTURED);journal.record(first);
        var other=operation(f.config,"other-caller","other-operation");journal.bind(other,legacy(other,"success"));
        var stolen=new ExecutionEvidence(other,"resource-stolen",first.providerResourceRef(),ExecutionEvidence.Source.RECONCILIATION,NOW,ExecutionEvidence.State.CAPTURED,Optional.empty());
        conflict(()->journal.record(stolen));assertEquals(first,journal.read(f.operation).orElseThrow());conflict(()->journal.read(other));
        var owner=row(f.database,"SELECT caller_key FROM provider_compatibility_resource_owners WHERE provider_id=? AND account_ref=? AND region=? AND resource_ref=?",BINDING.providerId(),BINDING.accountRef(),BINDING.region(),first.providerResourceRef().orElseThrow());assertEquals(f.operation.identity().callerIdempotencyKey(),owner.get("caller_key"));
    }
    @Test void unsupportedFinancialObservationsAreRetainedAsViolationsWithoutInventingMapping(){
        for(boolean action:List.of(false,true)){
            Fixture f=new Fixture();f.journal().bind(f.operation,f.legacy);
            var descriptor=new TrustedActionDescriptor(ExecutionOffer.ActionType.SYNTHETIC_CHALLENGE,"action-canary",f.operation.fingerprint(),BINDING.digest(),EXPIRY);
            var fact=new ExecutionEvidence(f.operation,"unsupported",Optional.empty(),ExecutionEvidence.Source.RECONCILIATION,NOW,
                    action?ExecutionEvidence.State.ACTION_REQUIRED:ExecutionEvidence.State.DEFINITIVELY_REFUSED,action?Optional.of(descriptor):Optional.empty());
            rejected(()->f.journal().record(fact));assertTrue((Boolean)f.binding().get("conflicted"));assertTrue(f.conflicts()>=1);conflict(()->f.journal().claim(new SubmissionRequest(f.operation,Optional.empty())));assertEquals(0,f.peerRows());
        }
    }
    @Test void malformedPartialSubmissionColumnsAreRejectedByPostgresAndCorruptionNeverRestoresOwner()throws Exception{
        Fixture f=new Fixture();f.journal().bind(f.operation,f.legacy);var original=f.binding();
        for(String assignment:List.of("submission_fingerprint='sha256:"+"0".repeat(64)+"'",
                "submission_fingerprint='sha256:"+"0".repeat(64)+"',submission_json='{}',prepared_resource_present=NULL",
                "submission_fingerprint='sha256:"+"0".repeat(64)+"',submission_json='{}',prepared_resource_present=false,dispatch_claimed=false,dispatch_blocked=false",
                "submission_fingerprint='sha256:"+"0".repeat(64)+"',submission_json='{}',prepared_resource_present=false,prepared_resource_ref='forbidden'")){
            try(var connection=f.database.getConnection();var statement=connection.prepareStatement("UPDATE provider_compatibility_bindings SET "+assignment+" WHERE tenant_id=? AND merchant_id=? AND run_id=? AND caller_key=?")){
                Object[] values=namespace(f.operation);for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]);assertThrows(SQLException.class,statement::executeUpdate);
            }
        }
        assertEquals(original,f.binding());assertTrue(f.journal().claim(new SubmissionRequest(f.operation,Optional.empty())).firstDispatchOwner());
        String payload=f.binding().get("operation_json").toString();var json=new ObjectMapper();var corrupted=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(payload);corrupted.put("unrecognized-canary","private-canary");
        execute(f.database,"UPDATE provider_compatibility_bindings SET operation_json=? WHERE tenant_id=? AND merchant_id=? AND run_id=? AND caller_key=?",join(json.writeValueAsString(corrupted),namespace(f.operation)));
        var failure=rejected(()->f.journal().read(f.operation));assertNull(failure.getCause());assertFalse(failure.toString().contains("private-canary"));
        rejected(()->f.journal().claim(new SubmissionRequest(f.operation,Optional.empty())));assertTrue((Boolean)f.binding().get("dispatch_claimed"));assertEquals(0,f.peerRows());
    }
    @Test void actualFreshJvmCrashesBeforeAndAfterBindHaveDifferentDurableWitnesses(){
        Fixture before=new Fixture();assertEquals(73,child(before,"before-bind-commit"));assertNull(before.binding());assertEquals(0,before.peerRows());assertEquals(0,child(before,"submit-captured"));assertEquals(1,before.peerRows());assertEquals(1,before.invocations("EXECUTE"));
        Fixture after=new Fixture();assertEquals(73,child(after,"after-bind-commit"));assertNotNull(after.binding());assertFalse((Boolean)after.binding().get("dispatch_claimed"));assertEquals(after.legacy.identity().adapterKey(),after.binding().get("legacy_adapter_key"));assertEquals(after.operation.fingerprint(),after.binding().get("operation_fingerprint"));assertEquals(0,after.peerRows());
        var original=immutable(after.binding());assertEquals(0,child(after,"submit-captured"));assertEquals(original,immutable(after.binding()));assertEquals(1,after.peerRows());assertEquals(1,after.peerEvents());assertEquals(1,after.invocations("EXECUTE"));
    }
    @Test void committedClaimProcessLossRetainsUncertaintyAndNeverElectsReplacement(){
        Fixture f=new Fixture();assertEquals(73,child(f,"after-claim-commit"));assertTrue((Boolean)f.binding().get("dispatch_claimed"));assertEquals(Boolean.FALSE,f.binding().get("prepared_resource_present"));assertNotNull(f.binding().get("submission_json"));assertEquals(0,f.evidence());assertEquals(0,f.peerRows());
        var mapping=immutable(f.binding());assertEquals(0,child(f,"submit-unknown"));assertEquals(0,child(f,"submit-unknown-unavailable"));assertEquals(mapping,immutable(f.binding()));assertEquals(0,f.invocations("EXECUTE"));assertEquals(0,f.peerRows());assertTrue((Boolean)f.binding().get("dispatch_blocked"));
    }
    @Test void acceptedPeerCommitSurvivesProcessLossExpiredFiniteCacheAndUnavailableLookup(){
        Fixture f=new Fixture();assertEquals(73,child(f,"after-peer-commit"));assertEquals("SUCCEEDED",f.peerState());assertEquals(1,f.peerRows());assertEquals(1,f.peerEvents());assertEquals(0,f.evidence());assertEquals(1,f.invocations("EXECUTE"));assertEquals(1,f.cache());
        f.clearCache();assertEquals(0,f.cache());assertEquals(1,f.peerRows());assertEquals(0,child(f,"submit-unknown-absent"));assertEquals(0,child(f,"submit-unknown-unavailable"));assertEquals(1,f.invocations("EXECUTE"));assertEquals(0,f.cache());
        assertEquals(0,child(f,"recover-captured"));assertEquals(0,child(f,"terminal-replay"));assertEquals(1,f.peerEvents());assertEquals(1,f.invocations("EXECUTE"));assertEquals("CAPTURED",f.journal().read(f.operation).orElseThrow().state().name());
    }
    @Test void syntheticFiniteResponseCacheHasIndependentlyObservedColdHitAndExpiredBehavior(){
        Fixture f=new Fixture();Observer transport=new Observer(f.database,f.config,"normal");Fact cold=transport.execute(f.legacy);assertEquals(cold,transport.execute(f.legacy));
        assertEquals(2,f.invocations("EXECUTE"));assertEquals(1,f.invocations("DELEGATE_EXECUTE"));assertEquals(1,f.invocations("CACHE_HIT"));assertEquals(1,f.invocations("CACHE_MISS"));
        f.clearCache();assertEquals(0,f.cache());assertEquals(1,f.peerRows());assertEquals(cold,transport.execute(f.legacy));
        assertEquals(3,f.invocations("EXECUTE"));assertEquals(2,f.invocations("DELEGATE_EXECUTE"));assertEquals(2,f.invocations("CACHE_MISS"));assertEquals(1,f.peerRows());assertEquals(1,f.peerEvents());assertEquals(1,f.cache());
    }
    @Test void committedTerminalResponseLossAndCommittedConflictResponseLossSurviveAnotherJvm(){
        Fixture f=new Fixture();assertEquals(73,child(f,"after-record-commit"));assertEquals(1,f.evidence());assertEquals(1,f.peerRows());String originalPayload=row(f.database,"SELECT evidence_json FROM provider_compatibility_evidence WHERE tenant_id=? AND merchant_id=? AND run_id=? AND caller_key=?",namespace(f.operation)).get("evidence_json").toString();
        assertEquals(0,child(f,"terminal-replay"));assertEquals(1,f.invocations("EXECUTE"));assertEquals(originalPayload,row(f.database,"SELECT evidence_json FROM provider_compatibility_evidence WHERE tenant_id=? AND merchant_id=? AND run_id=? AND caller_key=?",namespace(f.operation)).get("evidence_json"));
        assertEquals(73,child(f,"lose-conflict-response"));assertTrue((Boolean)f.binding().get("conflicted"));assertTrue(f.conflicts()>=1);assertEquals(0,child(f,"verify-conflict"));assertEquals(1,f.invocations("EXECUTE"));assertEquals(1,f.peerRows());
    }
    @Test void providerSealAndAcceptedFirstFenceCommitGapsRecoverOriginalFacts(){
        Fixture sealed=new Fixture();assertEquals(73,child(sealed,"after-seal-commit"));assertEquals("NO_EFFECT",sealed.peerState());assertEquals(0,sealed.evidence());assertEquals(0,sealed.invocations("EXECUTE"));assertEquals(0,child(sealed,"recover-fenced"));assertEquals(0,sealed.invocations("EXECUTE"));assertEquals(1,sealed.peerEvents());
        Fixture accepted=new Fixture();assertEquals(73,child(accepted,"after-peer-commit"));assertEquals(73,child(accepted,"after-accepted-fence-commit"));assertEquals("SUCCEEDED",accepted.peerState());assertEquals(0,accepted.evidence());assertEquals(0,child(accepted,"recover-captured"));assertEquals("CAPTURED",accepted.journal().read(accepted.operation).orElseThrow().state().name());assertEquals(1,accepted.peerEvents());assertEquals(1,accepted.invocations("EXECUTE"));
    }
    private static ExecutionFailure rejected(Runnable work){var failure=assertThrows(ExecutionFailure.class,work::run);assertNull(failure.getCause());return failure;}
    private static void conflict(Runnable work){assertEquals(ExecutionFailure.Code.EVIDENCE_CONFLICT,rejected(work).code());}
    private static Object[] join(Object first,Object[] rest){Object[] values=new Object[rest.length+1];values[0]=first;System.arraycopy(rest,0,values,1,rest.length);return values;}
}
