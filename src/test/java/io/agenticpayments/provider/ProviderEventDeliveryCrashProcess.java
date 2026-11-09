package io.agenticpayments.provider;

import io.agenticpayments.application.DeliverProviderEvents;
import io.agenticpayments.infrastructure.provider.JdbcProviderEventDeliveryQueue;
import io.agenticpayments.sdk.execution.v1.*;
import java.time.Duration;
import static io.agenticpayments.provider.ProviderEventTestSupport.*;

/** Synthetic subprocess fault boundaries. Never emits raw payloads, keys or identifiers. */
public final class ProviderEventDeliveryCrashProcess {
    public static void main(String[] args){
        var database=ProviderJournalTestSupport.database();var configuration=configuration(args[1]);var operation=operation(configuration,"first");var inbox=inbox(database,configuration);
        var queue=new JdbcProviderEventDeliveryQueue(database,configuration.tenantId(),configuration.merchantId(),configuration.runId());
        if(args[0].equals("accepted")){
            byte[] raw=body(operation,"event",ExecutionEvidence.State.PENDING);inbox.accept(operation,raw,sign(raw));Runtime.getRuntime().halt(73);
        }
        if(args[0].equals("claimed")){queue.claim(Duration.ofMinutes(1)).orElseThrow();Runtime.getRuntime().halt(73);}
        var worker=new DeliverProviderEvents(queue,delivery->{
            var reference=inbox.verify(new EventVerificationRequest(delivery.operation(),delivery.envelopeReference().orElseThrow()));
            if(args[0].equals("verified"))Runtime.getRuntime().halt(73);
            inbox.normalize(delivery.operation(),reference);
            if(args[0].equals("before-ack"))Runtime.getRuntime().halt(73);
        },Duration.ofMinutes(1),Duration.ofSeconds(1),Duration.ofMinutes(1));
        while(worker.runOnce()){}
        System.out.print("PROVIDER_EVENT_DELIVERY_OK");
    }
}
