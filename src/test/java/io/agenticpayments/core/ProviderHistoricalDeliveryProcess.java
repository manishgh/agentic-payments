package io.agenticpayments.core;

import io.agenticpayments.application.*;
import io.agenticpayments.infrastructure.*;
import io.agenticpayments.infrastructure.provider.*;
import java.util.*;
import static io.agenticpayments.core.ProviderHistoricalDeliveryIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reconstructs both historical trusted routes in a separate JVM without reading the event body for routing. */
public final class ProviderHistoricalDeliveryProcess {
    public static void main(String[] args) {
        var database=CoreTestSupport.database();var session=CoreTestSupport.session(args[1],UUID.fromString(args[2]));
        if(args[0].equals("rebind")) {
            var original=OLD.registration(session);var changed=SyntheticProviderRegistration.interactions(session.tenantId(),"merchant-demo",session.runId(),original.configuration().binding(),CoreTestSupport.CLOCK.plusSeconds(1),CoreTestSupport.CLOCK.plusSeconds(600));
            var rebound=PaymentServices.providerEventDeliveryRoute(database,changed,OLD.configuration(session),new HmacProviderEventAuthenticator(OLD.keyBytes()),session);
            assertEquals(ApplicationFailure.CONFIGURATION_CHANGED,assertThrows(ApplicationException.class,rebound::registerConfiguration).failure());
            System.out.print("PROVIDER_HISTORICAL_DELIVERY_OK");return;
        }
        var source=args[0].equals("core")?new ProviderEventAdmissionIntegrationTest.EventCommitDataSource(database,"core"):
                new ProviderAutomaticDeliveryIntegrationTest.DeliveryDataSource(database,args[0]);
        OLD.services(source,session).registerConfiguration();CURRENT.services(source,session).registerConfiguration();
        drain(worker(source,session,List.of(OLD,CURRENT)));
        System.out.print("PROVIDER_HISTORICAL_DELIVERY_OK");
    }
}
