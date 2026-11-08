package io.agenticpayments.infrastructure.provider;
import io.agenticpayments.application.*;
import io.agenticpayments.sdk.*;
import io.agenticpayments.sdk.execution.v1.*;
import io.agenticpayments.sdk.execution.v1.compat.LocalFakeExecutionAdapter;
import java.time.Instant;
import java.util.*;
/** Opt-in synthetic policy. Scenario identity is pinned before provider activity. */
public final class SyntheticProviderRegistration implements ProviderExecutionRegistration {
    public static final String PROFILE="local-provider-journal-admission-1",MAPPING="core-provider-map-1";
    private final String tenant,merchant;private final UUID run;private final ExecutionBinding binding;
    private final Instant observedAt,actionExpiresAt;
    public SyntheticProviderRegistration(String tenant,String merchant,UUID run,ExecutionBinding binding,Instant observedAt,Instant actionExpiresAt) {
        this.tenant=Objects.requireNonNull(tenant);this.merchant=Objects.requireNonNull(merchant);this.run=Objects.requireNonNull(run);this.binding=Objects.requireNonNull(binding);
        this.observedAt=Objects.requireNonNull(observedAt);this.actionExpiresAt=Objects.requireNonNull(actionExpiresAt);
        if(!"fixture-credential-1".equals(binding.credentialRef())||!"simulator".equals(binding.providerId())||!observedAt.isBefore(actionExpiresAt))throw invalid();
    }
    @Override public ProviderExecutionBinding binding(ChargeOperation core) {
        var id=core.identity();if(!tenant.equals(id.scope().tenantId())||!merchant.equals(id.scope().merchantId())||!run.equals(id.scope().runId())||"challenge-then-success".equals(core.scenarioId()))throw invalid();
        var money=new ExactMoney(core.money().currency(),2,core.money().minorUnits());var offer=LocalFakeExecutionAdapter.offer();
        var selection=CapabilitySelection.select(offer,new CapabilitySelection.Requirement(ExecutionOffer.VERSION,LocalFakeExecutionAdapter.PROFILE,"simulator","1",binding.accountRef(),binding.region(),
                "synthetic-card","synthetic-reference",Set.of(),OperationPurpose.CHARGE,ExecutionOffer.PreparationEffect.EFFECT_FREE,Set.of(ExecutionOffer.Capability.ATOMIC_NO_EFFECT_FENCE),money),binding);
        var identity=new ExecutionIdentity(tenant,merchant,run.toString(),id.intentId().toString(),id.attemptId().toString(),id.operationId()+":"+core.scenarioId(),id.adapterKey().toString());
        var operation=ExecutionOperation.create(identity,binding,selection,money,OperationPurpose.CHARGE);
        return new ProviderExecutionBinding(PROFILE,MAPPING,"core-scenario","1",core,binding.credentialRef(),operation,observedAt,actionExpiresAt);
    }
    public ProviderJournalConfiguration configuration() {
        return new ProviderJournalConfiguration(tenant,merchant,run.toString(),binding,ProviderJournalConfiguration.MAPPING_POLICY_VERSION,"core-scenario","1",op->{
            String id=op.identity().operationId();if(id.length()<38||id.charAt(36)!=':')throw invalid();UUID.fromString(id.substring(0,36));return id.substring(37);
        });
    }
    public Instant observedAt(){return observedAt;} public Instant actionExpiresAt(){return actionExpiresAt;}
    private static ApplicationException invalid(){return new ApplicationException(ApplicationFailure.UNSUPPORTED_CAPABILITY);}
}
