package io.agenticpayments.application;

import io.agenticpayments.sdk.execution.v1.ExecutionBinding;
import io.agenticpayments.sdk.execution.v1.ExecutionOperation;
import java.util.*;
import static io.agenticpayments.application.ProviderEventDeliveryQueue.Policy;

/** Trusted historical delivery routing; implementations and decorators must never select a current default. */
public interface ProviderEventDeliveryRoutes {
    String CONTRACT="provider-event-delivery-routes-1";
    /** Resolve the complete original configuration, including signing policy/key versions. Empty means unavailable. */
    Optional<Resolved> resolve(EventRoute expected);
    /** Resolve dispute processing independently of retired event signing configuration. */
    Optional<QuarantineResolved> resolveQuarantine(Namespace expected);

    record EventRoute(String tenantId,String merchantId,String runId,ExecutionBinding binding,Policy policy) {
        public EventRoute {token(tenantId);token(merchantId);token(runId);Objects.requireNonNull(binding);Objects.requireNonNull(policy);}
        public static EventRoute from(ExecutionOperation operation,Policy policy) {
            Objects.requireNonNull(operation);var id=operation.identity();
            return new EventRoute(id.tenantId(),id.merchantId(),id.runId(),operation.binding(),policy);
        }
        @Override public String toString(){return "ProviderEventDeliveryRoute[redacted]";}
    }
    /** The retained event-ID ownership namespace; revisions and signing keys cannot change dispute ownership. */
    record Namespace(String tenantId,String merchantId,String runId,String providerId,String accountRef) {
        public Namespace {for(String value:List.of(tenantId,merchantId,runId,providerId,accountRef))token(value);}
        public static Namespace from(ExecutionOperation operation) {
            Objects.requireNonNull(operation);var id=operation.identity();var binding=operation.binding();
            return new Namespace(id.tenantId(),id.merchantId(),id.runId(),binding.providerId(),binding.accountRef());
        }
        @Override public String toString(){return "ProviderEventDeliveryNamespace[redacted]";}
    }
    /** Echoed identity is checked by the caller before invoking the configured handler. */
    record Resolved(EventRoute route,ProviderEventDeliveryHandler handler) {
        public Resolved {Objects.requireNonNull(route);Objects.requireNonNull(handler);}
        @Override public String toString(){return "ResolvedProviderEventDelivery[redacted]";}
    }
    record QuarantineResolved(Namespace namespace,ProviderEventDeliveryHandler handler) {
        public QuarantineResolved {Objects.requireNonNull(namespace);Objects.requireNonNull(handler);}
        @Override public String toString(){return "ResolvedProviderEventQuarantine[redacted]";}
    }
    private static void token(String value) {
        if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))throw new IllegalArgumentException("Invalid delivery route.");
    }
}
