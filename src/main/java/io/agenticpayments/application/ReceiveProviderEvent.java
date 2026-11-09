package io.agenticpayments.application;

import io.agenticpayments.application.PaymentService.Session;
import io.agenticpayments.sdk.ChargeOperation;
import io.agenticpayments.sdk.AdapterUnavailableException;
import io.agenticpayments.sdk.execution.v1.ExecutionFailure;
import java.util.*;
import java.util.function.Supplier;

/** Explicit retained-reference replay closes independent inbox/journal/core commits without resending. */
public final class ReceiveProviderEvent implements ProviderEventOperations {
    private final PaymentWorkerTransactions transactions;
    private final ProviderExecutionBindings bindings;
    private final ProviderEventIngress ingress;
    private final ProviderEventProcessing processing;
    public ReceiveProviderEvent(PaymentWorkerTransactions transactions,ProviderExecutionBindings bindings,
                                ProviderEventIngress ingress,ProviderEventProcessing processing) {
        this.transactions=Objects.requireNonNull(transactions);this.bindings=Objects.requireNonNull(bindings);
        this.ingress=Objects.requireNonNull(ingress);this.processing=Objects.requireNonNull(processing);
    }
    public String retain(Session session,UUID intentId,byte[] body,String signature) {
        var binding=safe(()->bound(session,intentId));
        try{return safe(()->Objects.requireNonNull(ingress.retain(binding.operation(),body,signature)));}
        catch(ProviderEvidenceConflict conflict){quarantine(session,binding);throw conflict;}
    }
    public void replay(Session session,UUID intentId,String envelopeReference) {
        var binding=safe(()->bound(session,intentId));
        try {
            var fact=safe(()->Objects.requireNonNull(processing.process(binding,envelopeReference)));
            if(fact.isPresent())safe(()->transactions.run(session,tx->{tx.applyVerifiedFact(binding.coreOperation(),fact.orElseThrow());return null;}));
        } catch(ProviderEvidenceConflict conflict) {
            quarantine(session,binding);throw conflict;
        }
    }
    private ProviderExecutionBinding bound(Session session,UUID intentId) {
        if(session==null)throw new PaymentException(ApplicationFailure.UNAUTHENTICATED);
        if(!session.roles().contains("runner:write"))throw new PaymentException(ApplicationFailure.FORBIDDEN);
        ChargeOperation core=transactions.run(session,tx->{
            var snapshot=tx.findAdmitted(Objects.requireNonNull(intentId));var operation=snapshot.operation();
            if(!snapshot.admitted())throw new PaymentException(ApplicationFailure.INVALID_STATE);
            if(!operation.identity().scope().tenantId().equals(session.tenantId())
                    ||!operation.identity().scope().runId().equals(session.runId())
                    ||!session.allowedMerchants().contains(operation.identity().scope().merchantId()))
                throw new PaymentException(ApplicationFailure.FORBIDDEN);
            return operation;
        });
        var binding=bindings.load(core.identity());
        if(!binding.coreOperation().equals(core))throw new PaymentException(ApplicationFailure.AUTHORITY_BINDING_INVALID);
        return binding;
    }
    private void quarantine(Session session,ProviderExecutionBinding binding){safe(()->transactions.run(session,tx->{tx.quarantineProvider(binding.coreOperation());return null;}));}
    private static <T>T safe(Supplier<T> work){
        try{return work.get();}
        catch(ApplicationException|AdapterUnavailableException safe){throw safe;}
        catch(ExecutionFailure failure){
            if(failure.code()==ExecutionFailure.Code.EVIDENCE_CONFLICT||failure.code()==ExecutionFailure.Code.CONTRACT_VIOLATION)throw new ProviderEvidenceConflict();
            if(failure.code()==ExecutionFailure.Code.CONFIGURATION_UNAVAILABLE||failure.code()==ExecutionFailure.Code.TEMPORARILY_UNAVAILABLE)throw new AdapterUnavailableException();
            throw new ApplicationException(ApplicationFailure.AUTHORITY_BINDING_INVALID);
        }catch(RuntimeException unsafe){throw new AdapterUnavailableException();}
    }
}
