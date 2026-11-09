package io.agenticpayments.infrastructure;

import io.agenticpayments.application.PaymentService;
import io.agenticpayments.application.ExternalCheckoutRegistration;
import io.agenticpayments.application.PaymentFactVerifier;
import io.agenticpayments.application.SyntheticPendingProgression;
import io.agenticpayments.application.ProviderExecutionRegistration;
import io.agenticpayments.application.ProviderEvidenceVerifier;
import io.agenticpayments.application.ProviderInteractionPorts;
import io.agenticpayments.application.ProviderEventAuthenticator;
import io.agenticpayments.application.DeliverProviderEvents;
import io.agenticpayments.application.ProviderEventDeliveryQueue;
import io.agenticpayments.infrastructure.provider.*;
import io.agenticpayments.sdk.execution.v1.*;
import io.agenticpayments.sdk.execution.v1.compat.LocalFakeExecutionAdapter;
import java.util.Optional;
import java.time.Duration;
import io.agenticpayments.application.CheckoutLifecycleTransactions.CoordinationDomain;
import io.agenticpayments.sdk.PaymentAdapter;
import io.agenticpayments.composition.Composition.Ports;
import io.agenticpayments.composition.NativeComposition;
import javax.sql.DataSource;

/** Composition helper kept out of application/domain packages. */
public final class PaymentServices {
    private PaymentServices() {}
    /** Historical route composition; initialize migrations and call registerConfiguration() before use. */
    public static ProviderEventRouteServices providerEventDeliveryRoute(DataSource database,SyntheticProviderRegistration registration,
            ProviderEventConfiguration eventConfiguration,ProviderEventAuthenticator authenticator,PaymentService.Session runner) {
        return providerEventDeliveryRoute(database,registration,eventConfiguration,authenticator,runner,new JdbcProviderEventConfigurations(database));
    }
    /** Configuration commitments support the same direct/adapt/decorate replacement boundary as delivery. */
    public static ProviderEventRouteServices providerEventDeliveryRoute(DataSource database,SyntheticProviderRegistration registration,
            ProviderEventConfiguration eventConfiguration,ProviderEventAuthenticator authenticator,PaymentService.Session runner,
            io.agenticpayments.application.ProviderEventConfigurationStore configurations) {
        requireDeliveryScope(runner,eventConfiguration.tenantId(),eventConfiguration.merchantId(),eventConfiguration.runId());
        java.util.Objects.requireNonNull(configurations);
        var components=interactionComponents(database,registration,eventConfiguration,authenticator);
        var policy=new ProviderEventDeliveryQueue.Policy(eventConfiguration.policyReference(),eventConfiguration.policyVersion(),eventConfiguration.keyReference(),eventConfiguration.keyVersion());
        var key=new io.agenticpayments.application.ProviderEventDeliveryRoutes.EventRoute(eventConfiguration.tenantId(),eventConfiguration.merchantId(),eventConfiguration.runId(),eventConfiguration.binding(),policy);
        var config=registration.configuration();
        String descriptor=io.agenticpayments.domain.PaymentRules.sha256(String.join("\n","synthetic-event-route-descriptor-1",registration.profile(),
                SyntheticProviderRegistration.MAPPING,config.mappingPolicyVersion(),config.scenarioPolicyReference(),config.scenarioPolicyVersion(),
                config.offer().digest(),registration.observedAt().toString(),registration.actionExpiresAt().toString()));
        var commitment=new io.agenticpayments.application.ProviderEventConfigurationStore.Configuration(key,descriptor);
        var endpoints=components.store().eventDelivery(components.inbox(),new JdbcProviderExecutionBindings(database),components.inbox(),policy,runner);
        io.agenticpayments.application.ProviderEventAcceptance acceptance=(session,intent,body,signature)->{
            requireDeliveryScope(session,key.tenantId(),key.merchantId(),key.runId());
            configurations.verify(commitment);return endpoints.acceptance().accept(session,intent,body,signature);
        };
        var route=new io.agenticpayments.application.ProviderEventDeliveryRoutes.Resolved(key,delivery->{
            if(delivery.kind()!=ProviderEventDeliveryQueue.Kind.EVENT||!key.equals(io.agenticpayments.application.ProviderEventDeliveryRoutes.EventRoute.from(delivery.operation(),delivery.policy())))
                throw new io.agenticpayments.application.ApplicationException(io.agenticpayments.application.ApplicationFailure.AUTHORITY_BINDING_INVALID);
            configurations.verify(commitment);endpoints.handler().deliver(delivery);
        });
        return new ProviderEventRouteServices(components.store(),new PaymentService(components.store()),acceptance,route,configurations,commitment);
    }
    /** Keyless protected-dispute projection; independent of all historical event configurations. */
    public static io.agenticpayments.application.ProviderEventDeliveryRoutes.QuarantineResolved providerEventQuarantine(DataSource database,
            PaymentService.Session runner,io.agenticpayments.application.ProviderEventDeliveryRoutes.Namespace namespace) {
        requireDeliveryScope(runner,namespace.tenantId(),namespace.merchantId(),namespace.runId());
        var core=new JdbcPaymentStore(database,new JdbcSimulatorAdapter(database));
        return new io.agenticpayments.application.ProviderEventDeliveryRoutes.QuarantineResolved(namespace,
                core.quarantineEventDelivery(runner,new JdbcProviderEventDisputes(database,namespace)));
    }
    /** All consumers in a queue scope must use a coherent trusted route catalog. */
    public static io.agenticpayments.application.ProviderEventDeliveryWork providerEventDeliveryWorker(DataSource database,
            PaymentService.Session runner,String merchant,io.agenticpayments.application.ProviderEventDeliveryRoutes routes) {
        java.util.Objects.requireNonNull(runner);requireDeliveryScope(runner,runner.tenantId(),merchant,runner.runId().toString());
        var queue=new JdbcProviderEventDeliveryQueue(database,runner.tenantId(),merchant,runner.runId().toString());
        var router=new io.agenticpayments.application.RouteProviderEventDelivery(runner,routes);
        var worker=new DeliverProviderEvents(queue,router,Duration.ofSeconds(30),Duration.ofSeconds(1),Duration.ofMinutes(1));
        return worker::runOnce;
    }
    private static void requireDeliveryScope(PaymentService.Session runner,String tenant,String merchant,String run) {
        if(runner==null)throw new io.agenticpayments.application.ApplicationException(io.agenticpayments.application.ApplicationFailure.UNAUTHENTICATED);
        if(!runner.roles().contains("runner:write")||!runner.tenantId().equals(tenant)||!runner.runId().toString().equals(run)||!runner.allowedMerchants().contains(merchant))
            throw new io.agenticpayments.application.ApplicationException(io.agenticpayments.application.ApplicationFailure.FORBIDDEN);
    }
    /** Explicit synthetic interactions; the trusted event key and configuration must survive restart. */
    public static ProviderInteractionServices providerInteractions(DataSource dataSource,SyntheticProviderRegistration registration,
            ProviderEventConfiguration eventConfiguration,ProviderEventAuthenticator authenticator) {
        var components=interactionComponents(dataSource,registration,eventConfiguration,authenticator);
        return new ProviderInteractionServices(new PaymentService(components.store()),components.store().providerEvents());
    }
    /** Explicit automatic synthetic delivery; one immutable configured policy serves this scoped worker. */
    public static ProviderEventDeliveryServices providerEventDelivery(DataSource dataSource,SyntheticProviderRegistration registration,
            ProviderEventConfiguration eventConfiguration,ProviderEventAuthenticator authenticator,PaymentService.Session runner) {
        if(runner==null||!runner.roles().contains("runner:write")||!runner.tenantId().equals(eventConfiguration.tenantId())
                ||!runner.runId().toString().equals(eventConfiguration.runId())||!runner.allowedMerchants().contains(eventConfiguration.merchantId()))
            throw new io.agenticpayments.application.ApplicationException(io.agenticpayments.application.ApplicationFailure.FORBIDDEN);
        var components=interactionComponents(dataSource,registration,eventConfiguration,authenticator);
        var policy=new ProviderEventDeliveryQueue.Policy(eventConfiguration.policyReference(),eventConfiguration.policyVersion(),eventConfiguration.keyReference(),eventConfiguration.keyVersion());
        var delivery=components.store().eventDelivery(components.inbox(),new JdbcProviderExecutionBindings(dataSource),components.inbox(),policy,runner);
        var queue=new JdbcProviderEventDeliveryQueue(dataSource,eventConfiguration.tenantId(),eventConfiguration.merchantId(),eventConfiguration.runId());
        var worker=new DeliverProviderEvents(queue,delivery.handler(),Duration.ofSeconds(30),Duration.ofSeconds(1),Duration.ofMinutes(1));
        return new ProviderEventDeliveryServices(components.store(),new PaymentService(components.store()),delivery.acceptance(),worker::runOnce);
    }
    private record InteractionComponents(JdbcPaymentStore store,JdbcProviderEventInbox inbox) {}
    private static InteractionComponents interactionComponents(DataSource dataSource,SyntheticProviderRegistration registration,
            ProviderEventConfiguration eventConfiguration,ProviderEventAuthenticator authenticator) {
        var configuration=registration.configuration();
        if(!SyntheticProviderRegistration.INTERACTIONS_PROFILE.equals(registration.profile())
                ||!configuration.binding().equals(eventConfiguration.binding())||!configuration.offer().equals(eventConfiguration.offer())
                ||!configuration.tenantId().equals(eventConfiguration.tenantId())||!configuration.merchantId().equals(eventConfiguration.merchantId())
                ||!configuration.runId().equals(eventConfiguration.runId()))throw new io.agenticpayments.application.ApplicationException(io.agenticpayments.application.ApplicationFailure.UNSUPPORTED_CAPABILITY);
        var journal=new JdbcCompatibilityBindingJournal(dataSource,configuration);var peer=new JdbcSimulatorAdapter(dataSource);
        var bridge=new LocalFakeExecutionAdapter(peer,journal,configuration.binding(),configuration.tenantId(),configuration.merchantId(),configuration.runId(),
                registration.observedAt(),registration.actionExpiresAt(),configuration::scenarioFor,configuration.offer());
        var authentication=new JdbcSyntheticAuthentication(dataSource,configuration);
        var inbox=new JdbcProviderEventInbox(dataSource,eventConfiguration,authenticator,
                new SyntheticProviderEventDecoder(op->"legacy-resource-"+configuration.expectedLegacy(op).identity().adapterKey()));
        var interactions=new ProviderInteractionPorts(authentication,authentication,authentication,inbox,inbox,inbox,inbox);
        var store=JdbcPaymentStore.providerInteractions(dataSource,NativeComposition.ports(new JdbcSignedArtifactStore(dataSource)),registration,
                bridge,bridge,bridge,Optional.of(bridge),journal,interactions,(core,pending)->pending);
        return new InteractionComponents(store,inbox);
    }
    /** Every provider, authentication and event responsibility accepts direct implementations, adapters and wrappers. */
    public static ProviderInteractionServices providerInteractions(DataSource dataSource,ProviderExecutionRegistration registration,
            ExecutionPreparer preparer,FinancialSubmitter submitter,ExecutionReconciler reconciler,Optional<NoEffectFencer> fencer,
            ProviderEvidenceVerifier verifier,ProviderInteractionPorts interactions,Ports nativePorts) {
        var store=JdbcPaymentStore.providerInteractions(dataSource,nativePorts,registration,preparer,submitter,reconciler,fencer,verifier,interactions,(core,pending)->pending);
        return new ProviderInteractionServices(new PaymentService(store),store.providerEvents());
    }
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
