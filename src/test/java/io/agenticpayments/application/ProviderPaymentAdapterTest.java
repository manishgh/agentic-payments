package io.agenticpayments.application;

import io.agenticpayments.infrastructure.provider.JournaledPaymentAdapter;
import io.agenticpayments.sdk.*;
import io.agenticpayments.sdk.execution.v1.*;
import io.agenticpayments.sdk.execution.v1.compat.LocalFakeExecutionAdapter;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure facade contract examples. Retained memory is not process-persistence evidence. */
class ProviderPaymentAdapterTest {
    private static final Instant NOW=Instant.parse("2026-11-08T10:00:00.123456789Z");
    private static final String DIGEST="sha256:"+"a".repeat(64);
    private enum Style { DIRECT, ADAPTED, WRAPPED }
    private record Ports(ExecutionPreparer prepare,FinancialSubmitter submit,ExecutionReconciler reconcile,NoEffectFencer fence) {}

    private static ChargeOperation core() {
        return new ChargeOperation(new OperationIdentity(new ExecutionScope("provider-facade",new UUID(0,1),"merchant-demo"),
            new UUID(0,2),new UUID(0,3),new UUID(0,4),new UUID(0,5),DIGEST),new Money("EUR",1250),"success");
    }
    private static ExecutionOperation operation(ChargeOperation core,ExecutionBinding binding,ExecutionOffer offer) {
        var id=core.identity();var tuple=offer.combinations().getFirst();var money=new ExactMoney(core.money().currency(),2,core.money().minorUnits());
        var requirement=new CapabilitySelection.Requirement(offer.contractVersion(),offer.profileVersion(),offer.providerId(),offer.implementationRevision(),
            binding.accountRef(),binding.region(),tuple.methodId(),tuple.credentialFormatId(),Set.of(),OperationPurpose.CHARGE,tuple.preparationEffect(),tuple.capabilities(),money);
        return ExecutionOperation.create(new ExecutionIdentity(id.scope().tenantId(),id.scope().merchantId(),id.scope().runId().toString(),
            id.intentId().toString(),id.attemptId().toString(),id.operationId()+":"+core.scenarioId(),id.adapterKey().toString()),binding,
            CapabilitySelection.select(offer,requirement,binding),money,OperationPurpose.CHARGE);
    }
    private static ProviderExecutionBinding registration(ChargeOperation core,ExecutionOperation operation) {
        return new ProviderExecutionBinding("local-provider-journal-admission-1","core-provider-map-1","core-scenario","1",core,
            "fixture-credential-1",operation,NOW,NOW.plusSeconds(600));
    }
    private static ExecutionEvidence evidence(ExecutionOperation op,String id,ExecutionEvidence.State state,ExecutionEvidence.Source source,Instant time) {
        return new ExecutionEvidence(op,id,state==ExecutionEvidence.State.UNKNOWN?Optional.empty():Optional.of("retained-resource"),source,time,state,Optional.empty());
    }

    /** Independently retains every complete observation and rejects altered IDs or contradictory finance. */
    private static final class ProofJournal implements ProviderEvidenceVerifier {
        final ExecutionOperation operation;
        final Map<String,ExecutionEvidence> observations=new LinkedHashMap<>();
        boolean quarantined;int verified;
        ProofJournal(ExecutionOperation operation){this.operation=operation;}
        public ExecutionEvidence verify(ExecutionOperation expected,ExecutionEvidence received) {
            verified++;check(expected);
            if(!operation.equals(received.operation()))throw new ExecutionFailure(ExecutionFailure.Code.CONTRACT_VIOLATION,ExecutionFailure.Certainty.MAY_HAVE_OCCURRED);
            var original=observations.get(received.evidenceId());
            boolean fence=received.state()==ExecutionEvidence.State.NO_EFFECT_FENCED;
            boolean accepted=received.state()==ExecutionEvidence.State.PENDING||received.state()==ExecutionEvidence.State.CAPTURED;
            boolean contrary=observations.values().stream().anyMatch(e->(fence&&(e.state()==ExecutionEvidence.State.PENDING||e.state()==ExecutionEvidence.State.CAPTURED))
                ||(accepted&&e.state()==ExecutionEvidence.State.NO_EFFECT_FENCED));
            if((original!=null&&!original.equals(received))||contrary){quarantined=true;throw conflict();}
            observations.putIfAbsent(received.evidenceId(),received);return received;
        }
        public ExecutionEvidence retained(ExecutionOperation expected,ExecutionEvidence.State state) {
            check(expected);return observations.values().stream().filter(e->e.state()==state).findFirst().orElseThrow(()->new AdapterUnavailableException());
        }
        private void check(ExecutionOperation expected){if(quarantined)throw conflict();if(!operation.equals(expected))throw new ExecutionFailure(ExecutionFailure.Code.IDENTITY_CONFLICT,ExecutionFailure.Certainty.MAY_HAVE_OCCURRED);}
        private static ExecutionFailure conflict(){return new ExecutionFailure(ExecutionFailure.Code.EVIDENCE_CONFLICT,ExecutionFailure.Certainty.MAY_HAVE_OCCURRED);}
    }

    /** Independent peer state; adapters below only translate its foreign request and response arrays. */
    private static final class Peer {
        final ExecutionOperation operation;final List<SubmissionRequest> submissions=new ArrayList<>();
        int preparations,lookups,fences,acceptances;boolean loseResponse;
        ExecutionEvidence current;PreparationResult preparation;RuntimeException failure;
        Peer(ExecutionOperation operation){this.operation=operation;current=evidence(operation,"initial",ExecutionEvidence.State.CAPTURED,ExecutionEvidence.Source.SUBMIT,NOW);
            preparation=new PreparationResult(operation,PreparationResult.Status.READY,operation.selection().combination().preparationEffect(),Optional.empty(),Optional.empty());}
        void exact(ExecutionOperation actual){assertEquals(operation,actual);}
        PreparationResult prepare(ExecutionOperation actual){exact(actual);preparations++;return preparation;}
        ExecutionEvidence submit(SubmissionRequest request){exact(request.operation());assertTrue(request.preparedResourceRef().isEmpty());submissions.add(request);
            if(failure!=null)throw failure;if(acceptances==0&&current.state()!=ExecutionEvidence.State.UNKNOWN)acceptances++;
            if(loseResponse)throw new AdapterUnavailableException();return current;}
        ExecutionEvidence reconcile(ExecutionOperation actual){exact(actual);lookups++;if(failure!=null)throw failure;return current;}
        ExecutionEvidence fence(ExecutionOperation actual){exact(actual);fences++;return current;}
        Object[] foreign(String verb,Object[] request) {
            if(verb.equals("prepare"))return new Object[]{prepare((ExecutionOperation)request[0])};
            var result=switch(verb){case "submit"->submit(new SubmissionRequest((ExecutionOperation)request[0],Optional.empty()));
                case "lookup"->reconcile((ExecutionOperation)request[0]);case "fence"->fence((ExecutionOperation)request[0]);default->throw new AssertionError();};
            return new Object[]{result.operation(),result.evidenceId(),result.providerResourceRef(),result.source(),result.observedAt(),result.state(),result.action()};
        }
    }
    @SuppressWarnings("unchecked") private static ExecutionEvidence decode(Object[] response) {
        return new ExecutionEvidence((ExecutionOperation)response[0],(String)response[1],(Optional<String>)response[2],(ExecutionEvidence.Source)response[3],
            (Instant)response[4],(ExecutionEvidence.State)response[5],(Optional<TrustedActionDescriptor>)response[6]);
    }
    private static Object[] encode(ExecutionEvidence value){return new Object[]{value.operation(),value.evidenceId(),value.providerResourceRef(),value.source(),value.observedAt(),value.state(),value.action()};}
    private static Object[] encode(ProviderExecutionBinding value){return new Object[]{value.profile(),value.mappingPolicy(),value.scenarioPolicyReference(),value.scenarioPolicyVersion(),
        value.coreOperation(),value.credentialReference(),value.operation(),value.observedAt(),value.actionExpiresAt()};}
    private static ProviderExecutionBinding decodeBinding(Object[] row){return new ProviderExecutionBinding((String)row[0],(String)row[1],(String)row[2],(String)row[3],
        (ChargeOperation)row[4],(String)row[5],(ExecutionOperation)row[6],(Instant)row[7],(Instant)row[8]);}
    private static Ports ports(Peer peer,Style style) {
        if(style==Style.ADAPTED)return new Ports(op->(PreparationResult)peer.foreign("prepare",new Object[]{op})[0],
            request->decode(peer.foreign("submit",new Object[]{request.operation()})),op->decode(peer.foreign("lookup",new Object[]{op})),op->decode(peer.foreign("fence",new Object[]{op})));
        var direct=new Ports(peer::prepare,peer::submit,peer::reconcile,peer::fence);
        return style==Style.DIRECT?direct:new Ports(direct.prepare()::prepare,direct.submit()::submit,direct.reconcile()::reconcile,direct.fence()::fence);
    }
    private static final class Fixture {
        final ChargeOperation core=core();
        final ExecutionBinding binding=new ExecutionBinding("simulator","account-ref","eu-test","config-ref","1","fixture-credential-1","1","platform-owner");
        final ExecutionOperation operation=operation(core,binding,LocalFakeExecutionAdapter.offer());
        final ProviderExecutionBinding retained=ProviderPaymentAdapterTest.registration(core,operation);
        final ProofJournal journal=new ProofJournal(operation);final Peer peer=new Peer(operation);final Style style;
        Fixture(Style style){this.style=style;}
        ProviderExecutionRegistration registration(){
            ProviderExecutionRegistration direct=expected->{assertEquals(core,expected);return retained;};
            if(style==Style.DIRECT)return direct;
            if(style==Style.WRAPPED)return direct::binding;
            Object[] row=encode(retained);
            return expected->{assertEquals(row[4],expected);return decodeBinding(row);};
        }
        ProviderExecutionBindings bindings(){
            ProviderExecutionBindings direct=id->{assertEquals(core.identity(),id);return retained;};
            if(style==Style.DIRECT)return direct;if(style==Style.WRAPPED)return direct::load;
            Map<OperationIdentity,Object[]> foreign=Map.of(core.identity(),encode(retained));
            return id->decodeBinding(Objects.requireNonNull(foreign.get(id)));
        }
        ProviderEvidenceVerifier verifier(){
            if(style==Style.DIRECT)return journal;
            return new ProviderEvidenceVerifier(){
                public ExecutionEvidence verify(ExecutionOperation expected,ExecutionEvidence received){
                    if(style==Style.WRAPPED)return journal.verify(expected,received);
                    Object[] foreignRequest={expected,encode(received)};
                    return decode(encode(journal.verify((ExecutionOperation)foreignRequest[0],decode((Object[])foreignRequest[1]))));
                }
                public ExecutionEvidence retained(ExecutionOperation expected,ExecutionEvidence.State state){
                    if(style==Style.WRAPPED)return journal.retained(expected,state);
                    Object[] foreignRequest={expected,state.name()};
                    return decode(encode(journal.retained((ExecutionOperation)foreignRequest[0],ExecutionEvidence.State.valueOf((String)foreignRequest[1]))));
                }
            };
        }
        JournaledPaymentAdapter adapter(){return adapter(true,registration());}
        JournaledPaymentAdapter adapter(boolean fence,ProviderExecutionRegistration registration){var p=ports(peer,style);
            return new JournaledPaymentAdapter(registration,bindings(),p.prepare(),p.submit(),p.reconcile(),
                fence?Optional.of(p.fence()):Optional.empty(),verifier());}
    }

    /** Independent published framing oracle: each UTF-8 field has a four-byte length, without a field count. */
    private static Fact expected(Fixture f,ExecutionEvidence.State state) throws IOException {
        var id=f.core.identity();var scope=id.scope();var fields=List.of("core-provider-projection-1",scope.tenantId(),scope.runId().toString(),scope.merchantId(),
            id.intentId().toString(),id.attemptId().toString(),id.operationId().toString(),id.adapterKey().toString(),id.requestDigest(),f.operation.fingerprint(),state.name());
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        for(String field:fields){byte[] value=field.getBytes(StandardCharsets.UTF_8);out.writeInt(value.length);out.write(value);}
        var outcome=switch(state){case PENDING->Outcome.PENDING;case CAPTURED->Outcome.SUCCEEDED;case NO_EFFECT_FENCED->Outcome.NO_EFFECT;default->throw new AssertionError();};
        return new Fact(UUID.nameUUIDFromBytes(bytes.toByteArray()),id,f.core.money(),state==ExecutionEvidence.State.PENDING?1:2,outcome);
    }

    @Test void directAdaptedAndWrappedResponsibilitiesPreserveExactSubmissionAndStableCanonicalFacts() throws Exception {
        for(Style style:Style.values()){
            var f=new Fixture(style);var adapter=f.adapter();
            assertEquals(CapabilityOffer.localFake(),adapter.capabilities());assertEquals(ActionType.NONE,adapter.prepare(f.core).actionType());
            assertTrue(f.peer.submissions.isEmpty());assertEquals(1,f.peer.preparations);
            var first=adapter.execute(f.core);assertEquals(expected(f,ExecutionEvidence.State.CAPTURED),first);assertEquals(first,adapter.verify(f.core,first));
            f.peer.current=evidence(f.operation,"different-provider-id",ExecutionEvidence.State.CAPTURED,ExecutionEvidence.Source.RECONCILIATION,NOW.plusSeconds(20));
            var replay=f.adapter().lookup(f.core.identity());assertEquals(first,replay.fact().orElseThrow());
            assertEquals(2,f.journal.observations.size());assertEquals(1,f.peer.submissions.size());assertEquals(1,f.peer.acceptances);
            assertEquals(new SubmissionRequest(f.operation,Optional.empty()),f.peer.submissions.getFirst());
        }
    }

    @Test void unknownAndUnavailableNeverProduceFinancialFactsOrImplicitFences() {
        for(Style style:Style.values()){
            var f=new Fixture(style);f.peer.current=evidence(f.operation,"unknown",ExecutionEvidence.State.UNKNOWN,ExecutionEvidence.Source.RECONCILIATION,NOW);
            var adapter=f.adapter();
            assertThrows(AdapterUnavailableException.class,()->adapter.execute(f.core));assertTrue(adapter.lookup(f.core.identity()).fact().isEmpty());
            assertEquals(0,f.peer.fences);
            f.peer.failure=new ExecutionFailure(ExecutionFailure.Code.TEMPORARILY_UNAVAILABLE,ExecutionFailure.Certainty.MAY_HAVE_OCCURRED);
            assertThrows(AdapterUnavailableException.class,()->adapter.lookup(f.core.identity()));assertEquals(0,f.peer.acceptances);
        }
    }

    @Test void missingFencerCannotImplementTheSyntheticProfilesDeclaredGuarantee() {
        for(Style style:Style.values()){
            var f=new Fixture(style);
            assertEquals("UNSUPPORTED_CAPABILITY",assertThrows(ApplicationException.class,()->f.adapter(false,f.registration())).code());
            assertEquals(0,f.peer.preparations);assertTrue(f.peer.submissions.isEmpty());assertEquals(0,f.peer.lookups);assertEquals(0,f.peer.fences);assertEquals(0,f.journal.verified);
        }
    }

    @Test void lostSubmitResponseRecoversTheOriginalOperationThroughReadOnlyReconciliation() throws Exception {
        for(Style style:Style.values()){
            var f=new Fixture(style);f.peer.loseResponse=true;assertThrows(AdapterUnavailableException.class,()->f.adapter().execute(f.core));
            assertEquals(1,f.peer.acceptances);f.peer.loseResponse=false;
            var recovered=f.adapter().lookup(f.core.identity()).fact().orElseThrow();assertEquals(expected(f,ExecutionEvidence.State.CAPTURED),recovered);
            assertEquals(1,f.peer.submissions.size());assertEquals(1,f.peer.lookups);assertEquals(1,f.peer.acceptances);
        }
    }

    @Test void authenticLatePendingRemainsVerifiableAfterCaptureWithoutCallingProviderAgain() throws Exception {
        for(Style style:Style.values()){
            var f=new Fixture(style);var adapter=f.adapter();
            f.peer.current=evidence(f.operation,"pending",ExecutionEvidence.State.PENDING,ExecutionEvidence.Source.SUBMIT,NOW);var pending=adapter.execute(f.core);
            assertEquals(expected(f,ExecutionEvidence.State.PENDING),pending);
            f.peer.current=evidence(f.operation,"captured",ExecutionEvidence.State.CAPTURED,ExecutionEvidence.Source.RECONCILIATION,NOW.plusSeconds(1));
            var captured=adapter.lookup(f.core.identity()).fact().orElseThrow();assertEquals(expected(f,ExecutionEvidence.State.CAPTURED),captured);
            int lookups=f.peer.lookups;assertEquals(pending,adapter.verify(f.core,pending));assertEquals(captured,adapter.verify(f.core,captured));assertEquals(lookups,f.peer.lookups);
            var forged=new Fact(UUID.randomUUID(),pending.identity(),pending.money(),pending.sequence(),pending.outcome());
            assertEquals("AUTHORITY_BINDING_INVALID",assertThrows(ApplicationException.class,()->adapter.verify(f.core,forged)).code());
        }
    }

    @Test void genuineFenceIsDistinctFromAbsenceAndContradictionIsTypedConflict() throws Exception {
        for(Style style:Style.values()){
            var f=new Fixture(style);f.peer.current=evidence(f.operation,"fence",ExecutionEvidence.State.NO_EFFECT_FENCED,ExecutionEvidence.Source.FENCE,NOW);
            var adapter=f.adapter();var fenced=adapter.sealNoEffect(f.core);assertEquals(expected(f,ExecutionEvidence.State.NO_EFFECT_FENCED),fenced);assertEquals(fenced,adapter.verify(f.core,fenced));
            f.peer.current=evidence(f.operation,"contrary-pending",ExecutionEvidence.State.PENDING,ExecutionEvidence.Source.RECONCILIATION,NOW.plusSeconds(1));
            var failure=assertThrows(ProviderEvidenceConflict.class,()->adapter.lookup(f.core.identity()));assertEquals("TRUSTED_RESOLUTION_REQUIRED",failure.code());
            assertNull(failure.getCause());assertTrue(f.journal.quarantined);
            assertThrows(ProviderEvidenceConflict.class,()->adapter.verify(f.core,fenced));assertEquals(1,f.peer.fences);
        }
    }

    @Test void sameEvidenceIdChangedFullContentQuarantinesEvenWithoutTerminalTransition() {
        for(Style style:Style.values()){
            var f=new Fixture(style);var adapter=f.adapter();
            f.peer.current=evidence(f.operation,"same-id",ExecutionEvidence.State.PENDING,ExecutionEvidence.Source.SUBMIT,NOW);adapter.execute(f.core);
            f.peer.current=evidence(f.operation,"same-id",ExecutionEvidence.State.PENDING,ExecutionEvidence.Source.RECONCILIATION,NOW);
            assertThrows(ProviderEvidenceConflict.class,()->adapter.lookup(f.core.identity()));assertTrue(f.journal.quarantined);
            assertEquals(ExecutionEvidence.Source.SUBMIT,f.journal.observations.get("same-id").source());
        }
    }

    @Test void changedCompleteOperationOrMoneyCannotReuseRetainedRegistration() {
        for(Style style:Style.values()){
            var f=new Fixture(style);var adapter=f.adapter();
            var changed=new ChargeOperation(f.core.identity(),new Money("EUR",1251),f.core.scenarioId());
            assertEquals("AUTHORITY_BINDING_INVALID",assertThrows(ApplicationException.class,()->adapter.execute(changed)).code());assertTrue(f.peer.submissions.isEmpty());
            var wrong=ExecutionOperation.create(f.operation.identity(),f.operation.binding(),f.operation.selection(),new ExactMoney("EUR",2,1251),OperationPurpose.CHARGE);
            f.peer.current=evidence(wrong,"wrong-money",ExecutionEvidence.State.CAPTURED,ExecutionEvidence.Source.SUBMIT,NOW);
            assertEquals("AUTHORITY_BINDING_INVALID",assertThrows(ApplicationException.class,()->adapter.execute(f.core)).code());assertTrue(f.journal.observations.isEmpty());
        }
    }

    @Test void everyChangedProviderBindingIsRejectedAsAnUnboundObservation() {
        for(Style style:Style.values()){
            var f=new Fixture(style);var b=f.binding;
            var mutations=List.of(
                new ExecutionBinding(b.providerId(),"other-account",b.region(),b.configurationRef(),b.configurationVersion(),b.credentialRef(),b.credentialVersion(),b.executionOwnerId()),
                new ExecutionBinding(b.providerId(),b.accountRef(),"other-region",b.configurationRef(),b.configurationVersion(),b.credentialRef(),b.credentialVersion(),b.executionOwnerId()),
                new ExecutionBinding(b.providerId(),b.accountRef(),b.region(),"other-config",b.configurationVersion(),b.credentialRef(),b.credentialVersion(),b.executionOwnerId()),
                new ExecutionBinding(b.providerId(),b.accountRef(),b.region(),b.configurationRef(),"2",b.credentialRef(),b.credentialVersion(),b.executionOwnerId()),
                new ExecutionBinding(b.providerId(),b.accountRef(),b.region(),b.configurationRef(),b.configurationVersion(),"other-credential",b.credentialVersion(),b.executionOwnerId()),
                new ExecutionBinding(b.providerId(),b.accountRef(),b.region(),b.configurationRef(),b.configurationVersion(),b.credentialRef(),"2",b.executionOwnerId()),
                new ExecutionBinding(b.providerId(),b.accountRef(),b.region(),b.configurationRef(),b.configurationVersion(),b.credentialRef(),b.credentialVersion(),"other-owner"));
            for(var changed:mutations){
                var unbound=operation(f.core,changed,LocalFakeExecutionAdapter.offer());
                f.peer.current=evidence(unbound,"changed-binding",ExecutionEvidence.State.CAPTURED,ExecutionEvidence.Source.SUBMIT,NOW);
                assertEquals("AUTHORITY_BINDING_INVALID",assertThrows(ApplicationException.class,()->f.adapter().execute(f.core)).code());assertTrue(f.journal.observations.isEmpty());
            }
        }
    }

    @Test void unsupportedPreparationActionsAndFinancialStatesFailExplicitly() {
        for(Style style:Style.values()){
            var f=new Fixture(style);var adapter=f.adapter();
            var action=new TrustedActionDescriptor(ExecutionOffer.ActionType.SYNTHETIC_CHALLENGE,"opaque-action",f.operation.fingerprint(),f.binding.digest(),NOW.plusSeconds(300));
            f.peer.preparation=new PreparationResult(f.operation,PreparationResult.Status.ACTION_REQUIRED,ExecutionOffer.PreparationEffect.EFFECT_FREE,Optional.empty(),Optional.of(action));
            assertEquals("UNSUPPORTED_CAPABILITY",assertThrows(ApplicationException.class,()->adapter.prepare(f.core)).code());assertTrue(f.peer.submissions.isEmpty());
            f.peer.current=evidence(f.operation,"refusal",ExecutionEvidence.State.DEFINITIVELY_REFUSED,ExecutionEvidence.Source.RECONCILIATION,NOW);
            assertEquals("UNSUPPORTED_CAPABILITY",assertThrows(ApplicationException.class,()->adapter.lookup(f.core.identity())).code());
        }
    }

    @Test void changedImmutableRegistrationCannotFallBackOrReachProvider() {
        for(Style style:Style.values()){
            var f=new Fixture(style);var original=f.retained;
            var mutations=List.of(
                new ProviderExecutionBinding("other-profile",original.mappingPolicy(),original.scenarioPolicyReference(),original.scenarioPolicyVersion(),f.core,original.credentialReference(),f.operation,NOW,original.actionExpiresAt()),
                new ProviderExecutionBinding(original.profile(),"other-mapping",original.scenarioPolicyReference(),original.scenarioPolicyVersion(),f.core,original.credentialReference(),f.operation,NOW,original.actionExpiresAt()),
                new ProviderExecutionBinding(original.profile(),original.mappingPolicy(),"other-policy",original.scenarioPolicyVersion(),f.core,original.credentialReference(),f.operation,NOW,original.actionExpiresAt()),
                new ProviderExecutionBinding(original.profile(),original.mappingPolicy(),original.scenarioPolicyReference(),"2",f.core,original.credentialReference(),f.operation,NOW,original.actionExpiresAt()),
                new ProviderExecutionBinding(original.profile(),original.mappingPolicy(),original.scenarioPolicyReference(),original.scenarioPolicyVersion(),f.core,original.credentialReference(),f.operation,NOW.plusNanos(1),original.actionExpiresAt()),
                new ProviderExecutionBinding(original.profile(),original.mappingPolicy(),original.scenarioPolicyReference(),original.scenarioPolicyVersion(),f.core,original.credentialReference(),f.operation,NOW,original.actionExpiresAt().plusNanos(1)));
            for(var changed:mutations){
                var adapter=f.adapter(true,ignored->changed);
                assertEquals("UNSUPPORTED_CAPABILITY",assertThrows(ApplicationException.class,()->adapter.prepare(f.core)).code());
                assertEquals("UNSUPPORTED_CAPABILITY",assertThrows(ApplicationException.class,()->adapter.execute(f.core)).code());
                assertEquals("UNSUPPORTED_CAPABILITY",assertThrows(ApplicationException.class,()->adapter.lookup(f.core.identity())).code());
            }
            assertEquals(0,f.peer.preparations);assertTrue(f.peer.submissions.isEmpty());assertEquals(0,f.peer.lookups);assertEquals(0,f.journal.verified);
        }
    }

    @Test void resourceCreatingCombinationIsRejectedBeforePreparationOrSubmission() {
        var f=new Fixture(Style.DIRECT);var original=LocalFakeExecutionAdapter.offer();var tuple=original.combinations().getFirst();
        var resourceTuple=new ExecutionOffer.Combination(tuple.methodId(),tuple.credentialFormatId(),tuple.actions(),tuple.purposes(),ExecutionOffer.PreparationEffect.RESOURCE_CREATING,tuple.capabilities(),tuple.limits());
        var offer=new ExecutionOffer(original.contractVersion(),original.profileVersion(),original.providerId(),original.implementationRevision(),List.of(resourceTuple));
        var op=operation(f.core,f.binding,offer);var retained=registration(f.core,op);var calls=new int[1];
        var adapter=new JournaledPaymentAdapter(ignored->retained,ignored->retained,
            operation->{calls[0]++;return new PreparationResult(operation,PreparationResult.Status.READY,ExecutionOffer.PreparationEffect.RESOURCE_CREATING,Optional.of("resource"),Optional.empty());},
            request->{calls[0]++;throw new AssertionError("Resource creation must not become financial admission");},
            operation->{calls[0]++;throw new AssertionError("Unsupported combination must fail before lookup");},
            Optional.of(operation->{calls[0]++;throw new AssertionError("Unsupported combination must fail before fence");}),f.journal);
        assertEquals("UNSUPPORTED_CAPABILITY",assertThrows(ApplicationException.class,()->adapter.prepare(f.core)).code());
        assertEquals("UNSUPPORTED_CAPABILITY",assertThrows(ApplicationException.class,()->adapter.execute(f.core)).code());assertEquals(0,calls[0]);
    }

    @Test void backendCanaryExceptionsAreSanitizedAndNeverBecomeNoEffectFacts() {
        for(Style style:Style.values()){
            var f=new Fixture(style);f.peer.failure=new IllegalStateException("CANARY_PRIVATE_PROVIDER_PAYLOAD");var adapter=f.adapter();
            var submit=assertThrows(AdapterUnavailableException.class,()->adapter.execute(f.core));
            var lookup=assertThrows(AdapterUnavailableException.class,()->adapter.lookup(f.core.identity()));
            for(var failure:List.of(submit,lookup)){assertNull(failure.getCause());assertFalse(failure.toString().contains("CANARY_PRIVATE"));}
            assertTrue(f.journal.observations.isEmpty());assertEquals(0,f.peer.acceptances);assertEquals(0,f.peer.fences);
        }
    }
}
