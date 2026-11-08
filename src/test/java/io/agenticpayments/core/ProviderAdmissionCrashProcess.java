package io.agenticpayments.core;

import io.agenticpayments.application.*;
import io.agenticpayments.composition.NativeComposition;
import io.agenticpayments.infrastructure.*;
import io.agenticpayments.infrastructure.provider.*;
import io.agenticpayments.sdk.*;
import io.agenticpayments.sdk.execution.v1.*;
import io.agenticpayments.sdk.execution.v1.compat.*;
import java.util.*;
import static io.agenticpayments.core.CoreTestSupport.*;

/** Abrupt JVM loss after a named real commit, without finally/cleanup running. */
public final class ProviderAdmissionCrashProcess {
    public static void main(String[] args)throws Exception{
        String phase=args[0];var ds=database();var s=session(args[1],UUID.fromString(args[2]));UUID intent=UUID.fromString(args[3]);
        var registration=ProviderAdmissionIntegrationTest.registration(s);var config=registration.configuration();
        var durable=new JdbcCompatibilityBindingJournal(ds,config);
        CompatibilityBindingJournal journal=new CompatibilityBindingJournal(){
            public ChargeOperation bind(ExecutionOperation operation,ChargeOperation candidate){return durable.bind(operation,candidate);}
            public DispatchClaim claim(SubmissionRequest request){var claim=durable.claim(request);if(phase.equals("claim"))Runtime.getRuntime().halt(73);return claim;}
            public ExecutionEvidence record(ExecutionEvidence evidence){var result=durable.record(evidence);if(phase.equals("journal"))Runtime.getRuntime().halt(73);return result;}
            public Optional<ExecutionEvidence> read(ExecutionOperation operation){return durable.read(operation);}
        };
        var peer=new JdbcSimulatorAdapter(ds);
        PaymentAdapter observed=new PaymentAdapter(){
            public CapabilityOffer capabilities(){return peer.capabilities();}
            public Preparation prepare(ChargeOperation operation){return peer.prepare(operation);}
            public Fact execute(ChargeOperation operation){var fact=peer.execute(operation);if(phase.equals("acceptance"))Runtime.getRuntime().halt(73);return fact;}
            public LookupResult lookup(OperationIdentity identity){return peer.lookup(identity);}
            public Fact sealNoEffect(ChargeOperation operation){return peer.sealNoEffect(operation);}
        };
        var bridge=new LocalFakeExecutionAdapter(observed,journal,config.binding(),s.tenantId(),"merchant-demo",s.runId().toString(),registration.observedAt(),registration.actionExpiresAt(),config::scenarioFor);
        FinancialSubmitter submit=request->{if(phase.equals("admission"))Runtime.getRuntime().halt(73);return bridge.submit(request);};
        var service=PaymentServices.providerJournal(ds,registration,bridge,submit,bridge,Optional.of(bridge),durable,NativeComposition.ports(new JdbcSignedArtifactStore(ds)));
        if(phase.equals("fence"))service.reconcile(s,intent,true);else service.runNext(s);
        if(Set.of("recover","fence").contains(phase))System.out.println("PROVIDER_RECOVERY_OK");
        else throw new AssertionError("Expected crash boundary was not reached");
    }
}
