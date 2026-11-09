package io.agenticpayments.core;

import java.util.UUID;

/** Child process reconstructs the automatic delivery composition using retained immutable policy. */
public final class ProviderAutomaticDeliveryCrashProcess {
    public static void main(String[] args){
        var database=CoreTestSupport.database();var session=CoreTestSupport.session(args[1],UUID.fromString(args[2]));
        var source=args[0].equals("core")?new ProviderEventAdmissionIntegrationTest.EventCommitDataSource(database,"core"):
                new ProviderAutomaticDeliveryIntegrationTest.DeliveryDataSource(database,args[0]);
        var services=ProviderAutomaticDeliveryIntegrationTest.automatic(source,session);
        if(!services.delivery().runOnce())throw new IllegalStateException("Expected durable event delivery.");
        System.out.print("PROVIDER_AUTOMATIC_DELIVERY_OK");
    }
}
