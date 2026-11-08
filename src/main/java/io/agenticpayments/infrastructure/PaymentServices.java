package io.agenticpayments.infrastructure;

import io.agenticpayments.application.PaymentService;
import io.agenticpayments.application.ExternalCheckoutRegistration;
import io.agenticpayments.application.PaymentFactVerifier;
import io.agenticpayments.application.SyntheticPendingProgression;
import io.agenticpayments.application.ProviderExecutionRegistration;
import io.agenticpayments.application.ProviderEvidenceVerifier;
import io.agenticpayments.infrastructure.provider.*;
import io.agenticpayments.sdk.execution.v1.*;
import io.agenticpayments.sdk.execution.v1.compat.LocalFakeExecutionAdapter;
import java.util.Optional;
import io.agenticpayments.application.CheckoutLifecycleTransactions.CoordinationDomain;
import io.agenticpayments.sdk.PaymentAdapter;
import io.agenticpayments.composition.Composition.Ports;
import io.agenticpayments.composition.NativeComposition;
import javax.sql.DataSource;

/** Composition helper kept out of application/domain packages. */
public final class PaymentServices {
    private PaymentServices() {}
    public static PaymentService local(DataSource dataSource) { return local(dataSource,new JdbcSimulatorAdapter(dataSource)); }
    public static PaymentService local(DataSource dataSource,PaymentAdapter adapter) {
        return local(dataSource,adapter,NativeComposition.ports(new JdbcSignedArtifactStore(dataSource)));
    }
    /** All native responsibilities can be replaced, decorated or delegated through these same ports. */
    public static PaymentService local(DataSource dataSource,PaymentAdapter adapter,Ports ports) {
        return new PaymentService(new JdbcPaymentStore(dataSource,adapter,null,ports));
    }
    /** Provider wrappers can explicitly retain trusted verification and simulator progression. */
    public static PaymentService local(DataSource dataSource,PaymentAdapter adapter,Ports ports,
                                       PaymentFactVerifier verifier,SyntheticPendingProgression progression) {
        return new PaymentService(new JdbcPaymentStore(dataSource,adapter,null,ports,null,null,null,verifier,progression));
    }
    /** Explicit trusted external profile registration; callers must preserve its shared atomic domain. */
    public static PaymentService local(DataSource dataSource, PaymentAdapter adapter, Ports nativePorts,
                                       Ports externalPorts, ExternalCheckoutRegistration registration,
                                       CoordinationDomain domain) {
        return new PaymentService(new JdbcPaymentStore(dataSource,adapter,null,nativePorts,externalPorts,registration,domain));
    }
    /** Explicit synthetic opt-in; existing local() overloads never adopt this route. */
    public static PaymentService providerJournal(DataSource dataSource,SyntheticProviderRegistration registration) {
        var configuration=registration.configuration();
        var journal=new JdbcCompatibilityBindingJournal(dataSource,configuration);var peer=new JdbcSimulatorAdapter(dataSource);
        var bridge=new LocalFakeExecutionAdapter(peer,journal,configuration.binding(),configuration.tenantId(),configuration.merchantId(),configuration.runId(),
                registration.observedAt(),registration.actionExpiresAt(),configuration::scenarioFor);
        var bindings=new JdbcProviderExecutionBindings(dataSource);
        var adapter=new JournaledPaymentAdapter(registration,bindings,bridge,bridge,bridge,Optional.of(bridge),journal);
        SyntheticPendingProgression progression=(core,pending)->{
            var retained=bindings.load(core.identity());
            if(!registration.binding(core).equals(retained))throw new io.agenticpayments.application.ApplicationException(io.agenticpayments.application.ApplicationFailure.UNSUPPORTED_CAPABILITY);
            peer.finishPending(configuration.expectedLegacy(retained.operation()).identity());
            return adapter.lookup(core.identity()).fact().orElseThrow(io.agenticpayments.sdk.AdapterUnavailableException::new);
        };
        return new PaymentService(JdbcPaymentStore.providerJournal(dataSource,null,NativeComposition.ports(new JdbcSignedArtifactStore(dataSource)),null,null,null,registration,bridge,bridge,bridge,Optional.of(bridge),journal,progression));
    }
    /** Direct, adapted and decorated provider responsibilities preserve the same immutable route contract. */
    public static PaymentService providerJournal(DataSource dataSource,ProviderExecutionRegistration registration,
            ExecutionPreparer preparer,FinancialSubmitter submitter,ExecutionReconciler reconciler,
            Optional<NoEffectFencer> fencer,ProviderEvidenceVerifier verifier,Ports nativePorts) {
        return new PaymentService(JdbcPaymentStore.providerJournal(dataSource,null,nativePorts,null,null,null,registration,preparer,submitter,reconciler,fencer,verifier,(core,pending)->pending));
    }
}
