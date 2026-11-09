package io.agenticpayments.infrastructure.provider;

import io.agenticpayments.application.*;
import io.agenticpayments.sdk.execution.v1.*;
import java.util.Objects;

/** Every route responsibility observes the same durable dispute gate, including before submit. */
public final class ProviderEventGuards {
    private final ProviderEventHealth health;
    public ProviderEventGuards(ProviderEventHealth health){this.health=Objects.requireNonNull(health);}
    public ExecutionPreparer preparer(ExecutionPreparer next){Objects.requireNonNull(next);return op->{health.requireHealthy(op);return next.prepare(op);};}
    public FinancialSubmitter submitter(FinancialSubmitter next){Objects.requireNonNull(next);return request->{health.requireHealthy(request.operation());return next.submit(request);};}
    public ExecutionReconciler reconciler(ExecutionReconciler next){Objects.requireNonNull(next);return op->{health.requireHealthy(op);return next.reconcile(op);};}
    public NoEffectFencer fencer(NoEffectFencer next){Objects.requireNonNull(next);return op->{health.requireHealthy(op);return next.fence(op);};}
    public ProviderEvidenceVerifier evidenceVerifier(ProviderEvidenceVerifier next){Objects.requireNonNull(next);return new ProviderEvidenceVerifier(){
        public ExecutionEvidence verify(ExecutionOperation expected,ExecutionEvidence received){health.requireHealthy(expected);var result=next.verify(expected,received);health.requireHealthy(expected);return result;}
        public ExecutionEvidence retained(ExecutionOperation expected,ExecutionEvidence.State state){health.requireHealthy(expected);var result=next.retained(expected,state);health.requireHealthy(expected);return result;}
    };}
}
