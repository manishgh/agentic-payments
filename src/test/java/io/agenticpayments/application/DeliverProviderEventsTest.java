package io.agenticpayments.application;

import io.agenticpayments.sdk.execution.v1.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static io.agenticpayments.application.ProviderEventDeliveryQueue.*;
import static org.junit.jupiter.api.Assertions.*;

class DeliverProviderEventsTest {
    @Test void safeFailuresBackOffCapAndExhaustWithoutPersistingExceptionDetails(){
        var queue=new Queue();var worker=new DeliverProviderEvents(queue,ignored->{throw new ProviderEventDeliveryFailure(Failure.CONFIGURATION_UNAVAILABLE,false);},Duration.ofSeconds(30),Duration.ofSeconds(2),Duration.ofSeconds(5));
        worker.runOnce();assertEquals(new Resolution(Disposition.RETRY,Failure.CONFIGURATION_UNAVAILABLE,Duration.ofSeconds(2)),queue.last);
        queue.attempt=3;worker.runOnce();assertEquals(Duration.ofSeconds(5),queue.last.retryAfter());
        queue.attempt=8;worker.runOnce();assertEquals(Disposition.DEAD,queue.last.disposition());assertEquals(Failure.CONFIGURATION_UNAVAILABLE,queue.last.failure());
        queue.attempt=1;new DeliverProviderEvents(queue,ignored->{throw new IllegalStateException("sensitive-canary");},Duration.ofSeconds(30),Duration.ofSeconds(1),Duration.ofSeconds(5)).runOnce();
        assertEquals(Failure.INTERNAL,queue.last.failure());assertFalse(queue.last.toString().contains("sensitive-canary"));
    }
    @Test void permanentInvalidAndConflictAreDeadWhileSuccessAcknowledgesAndIdleDoesNothing(){
        var queue=new Queue();new DeliverProviderEvents(queue,ignored->{throw new ProviderEventDeliveryFailure(Failure.INVALID_EVENT,true);},Duration.ofSeconds(30),Duration.ofSeconds(1),Duration.ofSeconds(5)).runOnce();
        assertEquals(new Resolution(Disposition.DEAD,Failure.INVALID_EVENT,Duration.ZERO),queue.last);
        new DeliverProviderEvents(queue,ignored->{},Duration.ofSeconds(30),Duration.ofSeconds(1),Duration.ofSeconds(5)).runOnce();assertEquals(Resolution.done(),queue.last);
        queue.idle=true;assertFalse(new DeliverProviderEvents(queue,ignored->{fail("idle worker must not invoke handler");},Duration.ofSeconds(30),Duration.ofSeconds(1),Duration.ofSeconds(5)).runOnce());
    }
    private static final class Queue implements ProviderEventDeliveryQueue {
        int attempt=1;boolean idle;Resolution last;
        public Optional<Claim> claim(Duration lease){
            if(idle)return Optional.empty();var binding=new ExecutionBinding("simulator","account","region","configuration","1","credential","1","owner");
            var offer=io.agenticpayments.sdk.execution.v1.compat.LocalFakeExecutionAdapter.offer();var combination=offer.combinations().getFirst();var money=new ExactMoney("EUR",2,100);
            var selection=CapabilitySelection.select(offer,new CapabilitySelection.Requirement(ExecutionOffer.VERSION,offer.profileVersion(),offer.providerId(),offer.implementationRevision(),binding.accountRef(),binding.region(),combination.methodId(),combination.credentialFormatId(),combination.actions(),OperationPurpose.CHARGE,combination.preparationEffect(),combination.capabilities(),money),binding);
            var operation=ExecutionOperation.create(new ExecutionIdentity("tenant","merchant","run","intent","attempt","operation","key"),binding,selection,money,OperationPurpose.CHARGE);
            return Optional.of(new Claim(new Delivery("delivery",Kind.EVENT,operation,Optional.of("envelope"),new Policy("policy","1","key","1")),UUID.randomUUID(),attempt,8));
        }
        public boolean settle(Claim claim,Resolution resolution){last=resolution;return true;}
    }
}
