package io.agenticpayments.application;

import io.agenticpayments.sdk.AdapterUnavailableException;
import java.time.Duration;
import java.util.Objects;
import static io.agenticpayments.application.ProviderEventDeliveryQueue.*;

/** One bounded delivery step. Host owns scheduling; crashes recover through persisted leases. */
public final class DeliverProviderEvents {
    private final ProviderEventDeliveryQueue queue;
    private final ProviderEventDeliveryHandler handler;
    private final Duration lease,baseBackoff,maxBackoff;
    public DeliverProviderEvents(ProviderEventDeliveryQueue queue,ProviderEventDeliveryHandler handler,Duration lease,Duration baseBackoff,Duration maxBackoff){
        this.queue=Objects.requireNonNull(queue);this.handler=Objects.requireNonNull(handler);
        this.lease=Objects.requireNonNull(lease);this.baseBackoff=Objects.requireNonNull(baseBackoff);this.maxBackoff=Objects.requireNonNull(maxBackoff);
        if(lease.compareTo(Duration.ofMillis(1))<0||lease.compareTo(Duration.ofHours(1))>0||baseBackoff.compareTo(Duration.ofMillis(1))<0
                ||maxBackoff.compareTo(baseBackoff)<0||maxBackoff.compareTo(Duration.ofDays(1))>0)throw new IllegalArgumentException("Invalid delivery timing.");
    }
    public boolean runOnce(){
        var selected=queue.claim(lease);if(selected.isEmpty())return false;var claim=selected.orElseThrow();Resolution resolution;
        try{handler.deliver(claim.delivery());resolution=Resolution.done();}
        catch(ProviderEventDeliveryFailure failure){resolution=failed(claim,failure.failure(),failure.permanent());}
        catch(ProviderEvidenceConflict conflict){resolution=failed(claim,Failure.CONFLICT,true);}
        catch(AdapterUnavailableException unavailable){resolution=failed(claim,Failure.TEMPORARILY_UNAVAILABLE,false);}
        catch(RuntimeException unsafe){resolution=failed(claim,Failure.INTERNAL,false);}
        queue.settle(claim,resolution);return true;
    }
    private Resolution failed(Claim claim,Failure failure,boolean permanent){
        if(permanent||claim.attempt()>=claim.maxAttempts())return new Resolution(Disposition.DEAD,failure,Duration.ZERO);
        long delay=baseBackoff.toMillis(),limit=maxBackoff.toMillis();
        for(int n=1;n<claim.attempt()&&delay<limit;n++)delay=Math.min(limit,delay*2);
        return new Resolution(Disposition.RETRY,failure,Duration.ofMillis(delay));
    }
}
