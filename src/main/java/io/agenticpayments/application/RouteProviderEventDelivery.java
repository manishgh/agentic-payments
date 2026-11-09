package io.agenticpayments.application;

import io.agenticpayments.application.PaymentService.Session;
import io.agenticpayments.sdk.AdapterUnavailableException;
import io.agenticpayments.sdk.execution.v1.ExecutionFailure;
import java.util.Objects;
import static io.agenticpayments.application.ProviderEventDeliveryQueue.*;
import static io.agenticpayments.application.ProviderEventDeliveryRoutes.*;

/** Authorizes retained scope and validates exact trusted resolution before delegating financial evidence application. */
public final class RouteProviderEventDelivery implements ProviderEventDeliveryHandler {
    private final Session runner;
    private final ProviderEventDeliveryRoutes routes;
    public RouteProviderEventDelivery(Session runner,ProviderEventDeliveryRoutes routes) {
        this.runner=Objects.requireNonNull(runner);this.routes=Objects.requireNonNull(routes);
        if(!runner.roles().contains("runner:write"))throw new PaymentException(ApplicationFailure.FORBIDDEN);
    }
    @Override public void deliver(Delivery delivery) {
        try {
            Objects.requireNonNull(delivery);var id=delivery.operation().identity();
            if(!runner.tenantId().equals(id.tenantId())||!runner.runId().toString().equals(id.runId())
                    ||!runner.allowedMerchants().contains(id.merchantId()))throw failure(Failure.INVALID_EVENT,true);
            ProviderEventDeliveryHandler handler;
            if(delivery.kind()==Kind.QUARANTINE) {
                var expected=Namespace.from(delivery.operation());
                var resolved=Objects.requireNonNull(routes.resolveQuarantine(expected)).orElseThrow(RouteProviderEventDelivery::missing);
                if(!expected.equals(resolved.namespace()))throw missing();
                handler=resolved.handler();
            } else {
                var expected=EventRoute.from(delivery.operation(),delivery.policy());
                var resolved=Objects.requireNonNull(routes.resolve(expected)).orElseThrow(RouteProviderEventDelivery::missing);
                if(!expected.equals(resolved.route()))throw missing();
                handler=resolved.handler();
            }
            handler.deliver(delivery);
        } catch(ProviderEventDeliveryFailure safe) {throw safe;}
        catch(AdapterUnavailableException unavailable) {throw failure(Failure.TEMPORARILY_UNAVAILABLE,false);}
        catch(ExecutionFailure rejected) {
            throw switch(rejected.code()) {
                case CONFIGURATION_UNAVAILABLE -> missing();
                case TEMPORARILY_UNAVAILABLE -> failure(Failure.TEMPORARILY_UNAVAILABLE,false);
                case EVIDENCE_CONFLICT,CONTRACT_VIOLATION -> failure(Failure.CONFLICT,true);
                default -> failure(Failure.INVALID_EVENT,true);
            };
        } catch(ProviderEvidenceConflict conflict) {throw failure(Failure.CONFLICT,true);}
        catch(ApplicationException rejected) {
            if(rejected.failure()==ApplicationFailure.UNSUPPORTED_CAPABILITY||rejected.failure()==ApplicationFailure.CONFIGURATION_CHANGED)throw missing();
            if(rejected.failure().category()==ApplicationFailure.Category.UNAVAILABLE)throw failure(Failure.TEMPORARILY_UNAVAILABLE,false);
            if(rejected.failure().category()==ApplicationFailure.Category.INTERNAL)throw failure(Failure.INTERNAL,false);
            throw failure(Failure.INVALID_EVENT,true);
        } catch(RuntimeException unsafe) {throw failure(Failure.INTERNAL,false);}
    }
    private static ProviderEventDeliveryFailure missing(){return failure(Failure.CONFIGURATION_UNAVAILABLE,false);}
    private static ProviderEventDeliveryFailure failure(Failure code,boolean permanent){return new ProviderEventDeliveryFailure(code,permanent);}
}
