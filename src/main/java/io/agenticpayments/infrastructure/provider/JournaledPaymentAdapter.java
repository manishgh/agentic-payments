package io.agenticpayments.infrastructure.provider;
import io.agenticpayments.application.*;
import io.agenticpayments.sdk.*;
import io.agenticpayments.sdk.execution.v1.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
/** Bounded synthetic translation; durable binding and trusted provenance remain independently replaceable. */
public final class JournaledPaymentAdapter implements PaymentAdapter,PaymentFactVerifier {
    private final ProviderExecutionRegistration registration;private final ProviderExecutionBindings bindings;
    private final ExecutionPreparer preparer;private final FinancialSubmitter submitter;private final ExecutionReconciler reconciler;
    private final Optional<NoEffectFencer> fencer;private final ProviderEvidenceVerifier verifier;
    public JournaledPaymentAdapter(ProviderExecutionRegistration registration,ProviderExecutionBindings bindings,ExecutionPreparer preparer,
            FinancialSubmitter submitter,ExecutionReconciler reconciler,Optional<NoEffectFencer> fencer,ProviderEvidenceVerifier verifier) {
        this.registration=Objects.requireNonNull(registration);this.bindings=Objects.requireNonNull(bindings);this.preparer=Objects.requireNonNull(preparer);
        this.submitter=Objects.requireNonNull(submitter);this.reconciler=Objects.requireNonNull(reconciler);this.fencer=Objects.requireNonNull(fencer);this.verifier=Objects.requireNonNull(verifier);
        if(fencer.isEmpty())throw new ApplicationException(ApplicationFailure.UNSUPPORTED_CAPABILITY);
    }
    @Override public CapabilityOffer capabilities(){return CapabilityOffer.localFake();}
    private ProviderExecutionBinding bound(OperationIdentity identity) {
        var value=bindings.load(identity);
        if(!value.coreOperation().identity().equals(identity)||!registration.binding(value.coreOperation()).equals(value)
                ||!SyntheticProviderRegistration.PROFILE.equals(value.profile())||!SyntheticProviderRegistration.MAPPING.equals(value.mappingPolicy())
                ||value.operation().selection().combination().preparationEffect()!=ExecutionOffer.PreparationEffect.EFFECT_FREE)
            throw new ApplicationException(ApplicationFailure.UNSUPPORTED_CAPABILITY);
        return value;
    }
    private ProviderExecutionBinding bound(ChargeOperation core) {
        var value=bound(core.identity());if(!value.coreOperation().equals(core))throw new ApplicationException(ApplicationFailure.AUTHORITY_BINDING_INVALID);return value;
    }
    @Override public Preparation prepare(ChargeOperation core) {return safe(()->{
        var binding=bound(core);var prepared=preparer.prepare(binding.operation());
        if(!prepared.operation().equals(binding.operation())||prepared.status()!=PreparationResult.Status.READY
                ||prepared.effect()!=ExecutionOffer.PreparationEffect.EFFECT_FREE||prepared.preparedResourceRef().isPresent()||prepared.action().isPresent())
            throw new ApplicationException(ApplicationFailure.UNSUPPORTED_CAPABILITY);
        return new Preparation(ActionType.NONE);
    });}
    @Override public Fact execute(ChargeOperation core) {return safe(()->{
        var binding=bound(core);return financial(binding,submitter.submit(new SubmissionRequest(binding.operation(),Optional.empty())));
    });}
    @Override public LookupResult lookup(OperationIdentity identity) {return safe(()->{
        var binding=bound(identity);var evidence=verified(binding,reconciler.reconcile(binding.operation()));
        // NOT_FOUND is only the legacy contract's inconclusive no-fact observation; it asserts no absence/no-effect.
        return evidence.state()==ExecutionEvidence.State.UNKNOWN?LookupResult.notFound():LookupResult.found(project(binding,evidence));
    });}
    @Override public Fact sealNoEffect(ChargeOperation core) {return safe(()->{
        var binding=bound(core);var selected=fencer.orElseThrow(AdapterUnavailableException::new);
        if(!binding.operation().selection().combination().capabilities().contains(ExecutionOffer.Capability.ATOMIC_NO_EFFECT_FENCE))throw new AdapterUnavailableException();
        return financial(binding,selected.fence(binding.operation()));
    });}
    @Override public Fact verify(ChargeOperation core,Fact delivered) {return safe(()->{
        var binding=bound(core);var state=switch(delivered.outcome()){case PENDING->ExecutionEvidence.State.PENDING;case SUCCEEDED->ExecutionEvidence.State.CAPTURED;case NO_EFFECT->ExecutionEvidence.State.NO_EFFECT_FENCED;};
        var retained=verifier.retained(binding.operation(),state);
        if(!retained.operation().equals(binding.operation())||retained.state()!=state||!project(binding,retained).equals(delivered))
            throw new ApplicationException(ApplicationFailure.AUTHORITY_BINDING_INVALID);
        return delivered;
    });}
    private Fact financial(ProviderExecutionBinding binding,ExecutionEvidence received) {
        var evidence=verified(binding,received);if(evidence.state()==ExecutionEvidence.State.UNKNOWN)throw new AdapterUnavailableException();return project(binding,evidence);
    }
    private ExecutionEvidence verified(ProviderExecutionBinding binding,ExecutionEvidence received) {
        if(!received.operation().equals(binding.operation()))throw new ApplicationException(ApplicationFailure.AUTHORITY_BINDING_INVALID);
        var checked=verifier.verify(binding.operation(),received);if(!received.equals(checked))throw new ApplicationException(ApplicationFailure.AUTHORITY_BINDING_INVALID);return checked;
    }
    private static Fact project(ProviderExecutionBinding binding,ExecutionEvidence evidence) {
        Outcome outcome=switch(evidence.state()){case PENDING->Outcome.PENDING;case CAPTURED->Outcome.SUCCEEDED;case NO_EFFECT_FENCED->Outcome.NO_EFFECT;default->throw new ApplicationException(ApplicationFailure.UNSUPPORTED_CAPABILITY);};
        var id=binding.coreOperation().identity();
        try {var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            for(String field:List.of("core-provider-projection-1",id.scope().tenantId(),id.scope().runId().toString(),id.scope().merchantId(),id.intentId().toString(),id.attemptId().toString(),id.operationId().toString(),id.adapterKey().toString(),id.requestDigest(),binding.operation().fingerprint(),evidence.state().name())) {
                byte[] value=field.getBytes(StandardCharsets.UTF_8);out.writeInt(value.length);out.write(value);
            }
            return new Fact(UUID.nameUUIDFromBytes(bytes.toByteArray()),id,binding.coreOperation().money(),outcome==Outcome.PENDING?1:2,outcome);
        }catch(IOException impossible){throw new IllegalStateException("Projection encoding failed.");}
    }
    private static <T>T safe(Supplier<T> work) {
        try{return work.get();}catch(ExecutionFailure failure){
            if(failure.code()==ExecutionFailure.Code.EVIDENCE_CONFLICT||failure.code()==ExecutionFailure.Code.CONTRACT_VIOLATION)throw new ProviderEvidenceConflict();
            if(failure.code()==ExecutionFailure.Code.TEMPORARILY_UNAVAILABLE||failure.code()==ExecutionFailure.Code.CONFIGURATION_UNAVAILABLE)throw new AdapterUnavailableException();
            throw new ApplicationException(ApplicationFailure.UNSUPPORTED_CAPABILITY);
        }catch(ApplicationException|AdapterUnavailableException safe){throw safe;}
        catch(RuntimeException unsafe){throw new AdapterUnavailableException();}
    }
}
