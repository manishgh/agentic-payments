package io.agenticpayments.application;

import io.agenticpayments.application.PaymentService.Session;
import io.agenticpayments.sdk.AdapterUnavailableException;
import io.agenticpayments.sdk.execution.v1.ExecutionFailure;
import java.util.Objects;
import static io.agenticpayments.application.ProviderEventDeliveryQueue.*;

/** Verifies retained events and applies evidence to the original admitted operation; never dispatches money. */
public final class ApplyProviderEventDelivery implements ProviderEventDeliveryHandler {
    private final Session runner;
    private final PaymentWorkerTransactions transactions;
    private final ProviderEventDeliveryTargets targets;
    private final ProviderEventProcessing processing;
    private final ProviderEventDisputeVerifier disputes;
    private final Policy policy;
    public ApplyProviderEventDelivery(Session runner,PaymentWorkerTransactions transactions,ProviderEventDeliveryTargets targets,
            ProviderEventProcessing processing,ProviderEventDisputeVerifier disputes,Policy policy) {
        this.runner=Objects.requireNonNull(runner);this.transactions=Objects.requireNonNull(transactions);
        this.targets=Objects.requireNonNull(targets);this.processing=Objects.requireNonNull(processing);
        this.disputes=Objects.requireNonNull(disputes);this.policy=Objects.requireNonNull(policy);
        if(!runner.roles().contains("runner:write"))throw new PaymentException(ApplicationFailure.FORBIDDEN);
    }
    @Override public void deliver(Delivery delivery) {
        try {
            Objects.requireNonNull(delivery);var expected=delivery.operation();var identity=expected.identity();
            if(!runner.tenantId().equals(identity.tenantId())||!runner.runId().toString().equals(identity.runId())
                    ||!runner.allowedMerchants().contains(identity.merchantId()))throw failure(Failure.INVALID_EVENT,true);
            if(delivery.kind()==Kind.QUARANTINE) {
                // A queue DTO is not authority to freeze: resolve protected authenticated provenance first.
                disputes.requireQuarantined(expected);
                transactions.run(runner,tx->{tx.quarantineProviderOperation(expected);return null;});
                return;
            }
            if(!policy.equals(delivery.policy()))throw failure(Failure.CONFIGURATION_UNAVAILABLE,false);
            var binding=Objects.requireNonNull(targets.load(expected));
            if(!binding.operation().equals(expected))throw failure(Failure.INVALID_EVENT,true);
            var core=binding.coreOperation();
            if(!core.identity().scope().tenantId().equals(runner.tenantId())||!core.identity().scope().runId().equals(runner.runId())
                    ||!core.identity().scope().merchantId().equals(identity.merchantId()))throw failure(Failure.INVALID_EVENT,true);
            transactions.run(runner,tx->{
                var snapshot=tx.load(core.identity().operationId());
                if(!snapshot.admitted()||!snapshot.operation().equals(core))throw failure(Failure.INVALID_EVENT,true);
                return null;
            });
            java.util.Optional<io.agenticpayments.sdk.Fact> fact;
            try {fact=Objects.requireNonNull(processing.process(binding,delivery.envelopeReference().orElseThrow()));}
            catch(ProviderEvidenceConflict conflict) {
                transactions.run(runner,tx->{tx.quarantineProvider(core);return null;});throw conflict;
            } catch(ExecutionFailure conflict) {
                if(conflict.code()==ExecutionFailure.Code.EVIDENCE_CONFLICT||conflict.code()==ExecutionFailure.Code.CONTRACT_VIOLATION)
                    transactions.run(runner,tx->{tx.quarantineProvider(core);return null;});
                throw conflict;
            }
            if(fact.isPresent())transactions.run(runner,tx->{tx.applyVerifiedFact(core,fact.orElseThrow());return null;});
        } catch(ProviderEventDeliveryFailure safe) {throw safe;}
        catch(ProviderEvidenceConflict conflict) {throw failure(Failure.CONFLICT,true);}
        catch(AdapterUnavailableException unavailable) {throw failure(Failure.TEMPORARILY_UNAVAILABLE,false);}
        catch(ExecutionFailure rejected) {
            throw switch(rejected.code()) {
                case CONFIGURATION_UNAVAILABLE -> failure(Failure.CONFIGURATION_UNAVAILABLE,false);
                case TEMPORARILY_UNAVAILABLE -> failure(Failure.TEMPORARILY_UNAVAILABLE,false);
                case EVIDENCE_CONFLICT,CONTRACT_VIOLATION -> failure(Failure.CONFLICT,true);
                default -> failure(Failure.INVALID_EVENT,true);
            };
        } catch(ApplicationException rejected) {
            if(rejected.failure()==ApplicationFailure.UNSUPPORTED_CAPABILITY)throw failure(Failure.CONFIGURATION_UNAVAILABLE,false);
            if(rejected.failure().category()==ApplicationFailure.Category.UNAVAILABLE)throw failure(Failure.TEMPORARILY_UNAVAILABLE,false);
            if(rejected.failure().category()==ApplicationFailure.Category.INTERNAL)throw failure(Failure.INTERNAL,false);
            throw failure(Failure.INVALID_EVENT,true);
        } catch(RuntimeException unsafe) {throw failure(Failure.INTERNAL,false);}
    }
    private static ProviderEventDeliveryFailure failure(Failure failure,boolean permanent){return new ProviderEventDeliveryFailure(failure,permanent);}
}
