package io.agenticpayments.application;

import io.agenticpayments.application.PaymentService.Session;
import io.agenticpayments.infrastructure.provider.ImmutableProviderEventDeliveryRoutes;
import io.agenticpayments.sdk.AdapterUnavailableException;
import io.agenticpayments.sdk.execution.v1.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static io.agenticpayments.application.ProviderEventDeliveryQueue.*;
import static io.agenticpayments.application.ProviderEventDeliveryRoutes.*;
import static org.junit.jupiter.api.Assertions.*;

class RouteProviderEventDeliveryTest {
    private final UUID run=UUID.randomUUID();
    private final Session runner=new Session("tenant",run,"runner",Set.of("runner:write"),Set.of("merchant"),Set.of());
    private final ExecutionBinding binding=new ExecutionBinding("provider-a","account","region","config","1","credential","1","owner");
    private final Policy policy=new Policy("policy","1","key","1");
    private final List<String> calls=new ArrayList<>();

    private ExecutionOperation operation(String tenant,String merchant,String runId,ExecutionBinding selected) {
        var money=new ExactMoney("EUR",2,1250);
        var offer=new ExecutionOffer(ExecutionOffer.VERSION,"test-events-1",selected.providerId(),"1",List.of(
                new ExecutionOffer.Combination("card","reference",Set.of(),Set.of(OperationPurpose.CHARGE),
                        ExecutionOffer.PreparationEffect.EFFECT_FREE,Set.of(ExecutionOffer.Capability.VERIFIED_EVENTS),
                        List.of(new ExecutionOffer.CurrencyLimit("EUR",2,1,999999)))));
        var selection=CapabilitySelection.select(offer,new CapabilitySelection.Requirement(ExecutionOffer.VERSION,offer.profileVersion(),
                selected.providerId(),"1",selected.accountRef(),selected.region(),"card","reference",Set.of(),OperationPurpose.CHARGE,
                ExecutionOffer.PreparationEffect.EFFECT_FREE,Set.of(ExecutionOffer.Capability.VERIFIED_EVENTS),money),selected);
        return ExecutionOperation.create(new ExecutionIdentity(tenant,merchant,runId,"intent","attempt","operation","idempotency"),selected,selection,money,OperationPurpose.CHARGE);
    }
    private ExecutionOperation operation(ExecutionBinding selected){return operation("tenant","merchant",run.toString(),selected);}
    private Delivery event(ExecutionOperation operation,Policy selected){return new Delivery("delivery",Kind.EVENT,operation,Optional.of("envelope"),selected);}
    private EventRoute route(ExecutionBinding selected,Policy selectedPolicy){return EventRoute.from(operation(selected),selectedPolicy);}
    private ImmutableProviderEventDeliveryRoutes registry(Resolved... entries){return new ImmutableProviderEventDeliveryRoutes(List.of(entries),List.of());}
    private ProviderEventDeliveryFailure rejected(ProviderEventDeliveryHandler handler,Delivery delivery,Failure code,boolean permanent) {
        var failure=assertThrows(ProviderEventDeliveryFailure.class,()->handler.deliver(delivery));
        assertEquals(code,failure.failure());assertEquals(permanent,failure.permanent());return failure;
    }

    @Test void distinctProvidersAndHistoricalPoliciesUseTheirOwnHandlers() {
        var other=new ExecutionBinding("provider-b","account","region","config","2","credential","2","owner");
        var oldPolicy=new Policy("policy","0","retired-key","0");
        var events=registry(new Resolved(route(binding,policy),d->calls.add("current")),
                new Resolved(route(binding,oldPolicy),d->calls.add("historical")),new Resolved(route(other,policy),d->calls.add("other-provider")));
        var router=new RouteProviderEventDelivery(runner,events);
        router.deliver(event(operation(other),policy));router.deliver(event(operation(binding),oldPolicy));router.deliver(event(operation(binding),policy));
        assertEquals(List.of("other-provider","historical","current"),calls);
    }
    @Test void everyBindingFieldAndPolicyFieldParticipatesInExactResolution() {
        var variants=List.of(new ExecutionBinding("provider-b","account","region","config","1","credential","1","owner"),
                new ExecutionBinding("provider-a","other-account","region","config","1","credential","1","owner"),
                new ExecutionBinding("provider-a","account","other-region","config","1","credential","1","owner"),
                new ExecutionBinding("provider-a","account","region","other-config","1","credential","1","owner"),
                new ExecutionBinding("provider-a","account","region","config","2","credential","1","owner"),
                new ExecutionBinding("provider-a","account","region","config","1","other-credential","1","owner"),
                new ExecutionBinding("provider-a","account","region","config","1","credential","2","owner"),
                new ExecutionBinding("provider-a","account","region","config","1","credential","1","other-owner"));
        var router=new RouteProviderEventDelivery(runner,registry(new Resolved(route(binding,policy),d->calls.add("unexpected"))));
        for(var selected:variants)rejected(router,event(operation(selected),policy),Failure.CONFIGURATION_UNAVAILABLE,false);
        for(var selected:List.of(new Policy("other-policy","1","key","1"),new Policy("policy","2","key","1"),
                new Policy("policy","1","other-key","1"),new Policy("policy","1","key","2")))
            rejected(router,event(operation(binding),selected),Failure.CONFIGURATION_UNAVAILABLE,false);
        assertTrue(calls.isEmpty());
    }
    @Test void unauthorizedScopeNeverReachesResolver() {
        var routes=new ProviderEventDeliveryRoutes() {
            public Optional<Resolved> resolve(EventRoute expected){throw new AssertionError("Resolver must not see unauthorized scope");}
            public Optional<QuarantineResolved> resolveQuarantine(Namespace expected){throw new AssertionError("Resolver must not see unauthorized scope");}
        };
        var router=new RouteProviderEventDelivery(runner,routes);
        for(var operation:List.of(operation("other-tenant","merchant",run.toString(),binding),operation("tenant","other-merchant",run.toString(),binding),
                operation("tenant","merchant",UUID.randomUUID().toString(),binding))) {
            rejected(router,event(operation,policy),Failure.INVALID_EVENT,true);
            rejected(router,new Delivery("quarantine",Kind.QUARANTINE,operation,Optional.empty(),policy),Failure.INVALID_EVENT,true);
        }
    }
    @Test void replacementMustEchoExactRouteAndNamespaceBeforeAnyInvocation() {
        var wrongRoute=new EventRoute("other-tenant","merchant",run.toString(),binding,policy);
        var wrongNamespace=new Namespace("tenant","merchant",run.toString(),"other-provider","account");
        var routes=new ProviderEventDeliveryRoutes() {
            public Optional<Resolved> resolve(EventRoute expected){return Optional.of(new Resolved(wrongRoute,d->calls.add("unexpected")));}
            public Optional<QuarantineResolved> resolveQuarantine(Namespace expected){return Optional.of(new QuarantineResolved(wrongNamespace,d->calls.add("unexpected")));}
        };
        var router=new RouteProviderEventDelivery(runner,routes);
        rejected(router,event(operation(binding),policy),Failure.CONFIGURATION_UNAVAILABLE,false);
        rejected(router,new Delivery("quarantine",Kind.QUARANTINE,operation(binding),Optional.empty(),policy),Failure.CONFIGURATION_UNAVAILABLE,false);
        assertTrue(calls.isEmpty());
    }
    @Test void registryCopiesCollectionsAndRejectsEvenIdenticalDuplicateOwners() {
        var entry=new Resolved(route(binding,policy),d->{});var namespace=Namespace.from(operation(binding));
        var quarantine=new QuarantineResolved(namespace,d->{});var events=new ArrayList<>(List.of(entry));var quarantines=new ArrayList<>(List.of(quarantine));
        var registry=new ImmutableProviderEventDeliveryRoutes(events,quarantines);events.clear();quarantines.clear();
        assertEquals(Optional.of(entry),registry.resolve(entry.route()));assertEquals(Optional.of(quarantine),registry.resolveQuarantine(namespace));
        assertThrows(IllegalArgumentException.class,()->new ImmutableProviderEventDeliveryRoutes(List.of(entry,entry),List.of()));
        assertThrows(IllegalArgumentException.class,()->new ImmutableProviderEventDeliveryRoutes(List.of(),List.of(quarantine,quarantine)));
    }
    @Test void forwardingResolverAndHandlerPreserveExactDelivery() {
        var delivery=event(operation(binding),policy);var delegate=registry(new Resolved(route(binding,policy),d->{assertSame(delivery,d);calls.add("apply");}));
        var wrapper=new ProviderEventDeliveryRoutes() {
            public Optional<Resolved> resolve(EventRoute expected){calls.add("resolve");return delegate.resolve(expected).map(r->new Resolved(r.route(),d->{calls.add("handler");r.handler().deliver(d);}));}
            public Optional<QuarantineResolved> resolveQuarantine(Namespace expected){return delegate.resolveQuarantine(expected);}
        };
        new RouteProviderEventDelivery(runner,wrapper).deliver(delivery);assertEquals(List.of("resolve","handler","apply"),calls);
    }
    @Test void quarantineRequiresProtectedProvenanceWithoutResolvingAnyHistoricalEventKeys() {
        var operation=operation(binding);var oldPolicy=new Policy("retired-policy","0","retired-key","0");
        PaymentWorkerTransactions transactions=new PaymentWorkerTransactions() {public<T>T run(Session session,Work<T> work) {
            var tx=(Transaction)Proxy.newProxyInstance(Transaction.class.getClassLoader(),new Class<?>[]{Transaction.class},(proxy,method,args)->{
                assertEquals("quarantineProviderOperation",method.getName());assertEquals(operation,args[0]);calls.add("freeze");return null;
            });
            try{return work.run(tx);}catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new AssertionError(failure);}
        }};
        var apply=new ApplyProviderEventDelivery(runner,transactions,op->{throw new AssertionError();},(b,r)->{throw new AssertionError();},
                op->{assertEquals(operation,op);calls.add("protected-dispute");},policy);
        var routes=new ProviderEventDeliveryRoutes() {
            public Optional<Resolved> resolve(EventRoute expected){throw new AssertionError("Historical signing configuration is unavailable");}
            public Optional<QuarantineResolved> resolveQuarantine(Namespace expected){return Optional.of(new QuarantineResolved(expected,apply));}
        };
        new RouteProviderEventDelivery(runner,routes).deliver(new Delivery("quarantine",Kind.QUARANTINE,operation,Optional.empty(),oldPolicy));
        assertEquals(List.of("protected-dispute","freeze"),calls);
    }
    @Test void missingQuarantineNamespaceNeverFallsBackToEventHandler() {
        var router=new RouteProviderEventDelivery(runner,registry(new Resolved(route(binding,policy),d->calls.add("unexpected"))));
        rejected(router,new Delivery("quarantine",Kind.QUARANTINE,operation(binding),Optional.empty(),policy),Failure.CONFIGURATION_UNAVAILABLE,false);
        assertTrue(calls.isEmpty());
    }
    @Test void quarantineNamespaceCannotCrossAnyOwnershipField() {
        var namespace=Namespace.from(operation(binding));
        var registry=new ImmutableProviderEventDeliveryRoutes(List.of(),List.of(new QuarantineResolved(namespace,d->{})));
        for(var other:List.of(new Namespace("other-tenant","merchant",run.toString(),binding.providerId(),binding.accountRef()),
                new Namespace("tenant","other-merchant",run.toString(),binding.providerId(),binding.accountRef()),
                new Namespace("tenant","merchant","other-run",binding.providerId(),binding.accountRef()),
                new Namespace("tenant","merchant",run.toString(),"other-provider",binding.accountRef()),
                new Namespace("tenant","merchant",run.toString(),binding.providerId(),"other-account")))
            assertTrue(registry.resolveQuarantine(other).isEmpty());
    }
    @Test void explicitResolutionFailuresPreserveRetryOrPermanentClassification() {
        for(var code:List.of(ExecutionFailure.Code.CONFIGURATION_UNAVAILABLE,ExecutionFailure.Code.TEMPORARILY_UNAVAILABLE,ExecutionFailure.Code.INVALID_EVENT)) {
            var routes=new ProviderEventDeliveryRoutes() {
                public Optional<Resolved> resolve(EventRoute expected){throw new ExecutionFailure(code,ExecutionFailure.Certainty.NOT_STARTED);}
                public Optional<QuarantineResolved> resolveQuarantine(Namespace expected){throw new AssertionError();}
            };
            rejected(new RouteProviderEventDelivery(runner,routes),event(operation(binding),policy),switch(code) {
                case CONFIGURATION_UNAVAILABLE -> Failure.CONFIGURATION_UNAVAILABLE;
                case TEMPORARILY_UNAVAILABLE -> Failure.TEMPORARILY_UNAVAILABLE;
                default -> Failure.INVALID_EVENT;
            },code==ExecutionFailure.Code.INVALID_EVENT);
        }
    }
    @Test void resolverErrorsRemainClosedAndPreserveTransientSemantics() {
        for(var thrown:List.of(new AdapterUnavailableException(),new IllegalStateException("SECRET-CANARY"))) {
            var routes=new ProviderEventDeliveryRoutes() {
                public Optional<Resolved> resolve(EventRoute expected){throw thrown;}
                public Optional<QuarantineResolved> resolveQuarantine(Namespace expected){throw thrown;}
            };
            var failure=rejected(new RouteProviderEventDelivery(runner,routes),event(operation(binding),policy),
                    thrown instanceof AdapterUnavailableException?Failure.TEMPORARILY_UNAVAILABLE:Failure.INTERNAL,false);
            assertNull(failure.getCause());assertFalse(failure.toString().contains("SECRET-CANARY"));
        }
    }
    @Test void reboundConfigurationRemainsRetryableWithoutAnotherRoute() {
        var operation=operation(binding);var key=EventRoute.from(operation,policy);
        var registry=new ImmutableProviderEventDeliveryRoutes(List.of(new Resolved(key,delivery->{
            throw new ApplicationException(ApplicationFailure.CONFIGURATION_CHANGED);
        })),List.of());
        rejected(new RouteProviderEventDelivery(runner,registry),event(operation,policy),Failure.CONFIGURATION_UNAVAILABLE,false);
    }
}
