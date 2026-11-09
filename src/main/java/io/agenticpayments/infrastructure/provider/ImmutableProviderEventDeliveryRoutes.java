package io.agenticpayments.infrastructure.provider;

import io.agenticpayments.application.ProviderEventDeliveryRoutes;
import java.util.*;

/** Immutable reference registry of explicitly retained configurations; no default, alias or revision fallback. */
public final class ImmutableProviderEventDeliveryRoutes implements ProviderEventDeliveryRoutes {
    private final Map<EventRoute,Resolved> events;
    private final Map<Namespace,QuarantineResolved> quarantines;
    public ImmutableProviderEventDeliveryRoutes(Collection<Resolved> events,Collection<QuarantineResolved> quarantines) {
        var eventCopy=new HashMap<EventRoute,Resolved>();
        for(var entry:Objects.requireNonNull(events)) {
            Objects.requireNonNull(entry);
            if(eventCopy.putIfAbsent(entry.route(),entry)!=null)throw new IllegalArgumentException("Duplicate delivery route.");
        }
        var quarantineCopy=new HashMap<Namespace,QuarantineResolved>();
        for(var entry:Objects.requireNonNull(quarantines)) {
            Objects.requireNonNull(entry);
            if(quarantineCopy.putIfAbsent(entry.namespace(),entry)!=null)throw new IllegalArgumentException("Duplicate quarantine namespace.");
        }
        this.events=Map.copyOf(eventCopy);this.quarantines=Map.copyOf(quarantineCopy);
    }
    @Override public Optional<Resolved> resolve(EventRoute expected){return Optional.ofNullable(events.get(Objects.requireNonNull(expected)));}
    @Override public Optional<QuarantineResolved> resolveQuarantine(Namespace expected){return Optional.ofNullable(quarantines.get(Objects.requireNonNull(expected)));}
}
