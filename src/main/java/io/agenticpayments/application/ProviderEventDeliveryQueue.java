package io.agenticpayments.application;

import io.agenticpayments.sdk.execution.v1.ExecutionOperation;
import java.time.Duration;
import java.util.*;

/** Durable scoped delivery control; direct implementations, adapters and wrappers preserve lease fencing. */
public interface ProviderEventDeliveryQueue {
    String CONTRACT="provider-event-delivery-queue-1";
    /** Reclaims expired leases; exhausted crashed claims become durable DEAD work. Uses storage time. */
    Optional<Claim> claim(Duration lease);
    /** False means stale/expired ownership. Authentic financial evidence is independently idempotent. */
    boolean settle(Claim claim,Resolution resolution);
    enum Kind { EVENT, QUARANTINE }
    enum Disposition { DONE, RETRY, DEAD }
    enum Failure { NONE, INVALID_EVENT, CONFIGURATION_UNAVAILABLE, TEMPORARILY_UNAVAILABLE, CONFLICT, INTERNAL, ATTEMPTS_EXHAUSTED }
    record Policy(String policyReference,String policyVersion,String keyReference,String keyVersion) {
        public Policy { for(String value:List.of(policyReference,policyVersion,keyReference,keyVersion))token(value); }
        @Override public String toString(){return "ProviderEventDeliveryPolicy[redacted]";}
    }
    record Delivery(String deliveryReference,Kind kind,ExecutionOperation operation,Optional<String> envelopeReference,Policy policy) {
        public Delivery {
            token(deliveryReference);Objects.requireNonNull(kind);Objects.requireNonNull(operation);Objects.requireNonNull(envelopeReference);Objects.requireNonNull(policy);
            if((kind==Kind.EVENT)!=envelopeReference.isPresent())throw new IllegalArgumentException("Invalid delivery kind.");
            envelopeReference.ifPresent(ProviderEventDeliveryQueue::token);
        }
        @Override public String toString(){return "ProviderEventDelivery[redacted]";}
    }
    record Claim(Delivery delivery,UUID leaseToken,int attempt,int maxAttempts) {
        public Claim {Objects.requireNonNull(delivery);Objects.requireNonNull(leaseToken);if(maxAttempts<1||maxAttempts>100||attempt<1||attempt>maxAttempts)throw new IllegalArgumentException("Invalid delivery attempt.");}
        @Override public String toString(){return "ProviderEventDeliveryClaim[redacted]";}
    }
    record Resolution(Disposition disposition,Failure failure,Duration retryAfter) {
        public Resolution {
            Objects.requireNonNull(disposition);Objects.requireNonNull(failure);Objects.requireNonNull(retryAfter);
            if(retryAfter.isNegative()||retryAfter.compareTo(Duration.ofDays(1))>0||(disposition!=Disposition.RETRY&&!retryAfter.isZero())
                    ||(disposition==Disposition.DONE)!=(failure==Failure.NONE))throw new IllegalArgumentException("Invalid delivery resolution.");
        }
        public static Resolution done(){return new Resolution(Disposition.DONE,Failure.NONE,Duration.ZERO);}
    }
    private static void token(String value){if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))throw new IllegalArgumentException("Invalid delivery token.");}
}
