package io.agenticpayments.sdk.descriptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import static io.agenticpayments.sdk.descriptor.NegotiationException.Code.*;

public final class AdapterRegistry {
    private record Key(String provider, String revision) { }
    private final Map<Key, AdapterDescriptor> descriptors;
    public AdapterRegistry(Collection<AdapterDescriptor> registrations) {
        Objects.requireNonNull(registrations);
        var values = new HashMap<Key, AdapterDescriptor>();
        for (AdapterDescriptor descriptor : registrations) {
            var key = new Key(descriptor.providerId(), descriptor.revision());
            var previous = values.putIfAbsent(key, descriptor);
            if (previous != null && !previous.digest().equals(descriptor.digest())) throw new IllegalArgumentException("Conflicting descriptor revision.");
        }
        descriptors = Map.copyOf(values);
    }
    public AdapterDescriptor require(String providerId, String exactRevision) {
        AdapterDescriptor descriptor = descriptors.get(new Key(providerId, exactRevision));
        if (descriptor == null) throw new NegotiationException(UNKNOWN_DESCRIPTOR);
        return descriptor;
    }
    public NegotiatedDescriptor negotiate(AdapterSelection selection, AdapterAvailability availability, Instant now) {
        Objects.requireNonNull(selection); Objects.requireNonNull(availability); Objects.requireNonNull(now);
        AdapterDescriptor descriptor = require(selection.providerId(), selection.descriptorRevision());
        if (!descriptor.extensionVersions().contains(selection.extensionVersion()) || !descriptor.profiles().contains(selection.profile())) throw new NegotiationException(UNSUPPORTED_VERSION);
        if (!descriptor.capabilities().containsAll(selection.requiredCapabilities())) throw new NegotiationException(UNSUPPORTED_CAPABILITY);
        if (!selection.providerId().equals(availability.providerId()) || !selection.descriptorRevision().equals(availability.descriptorRevision())
                || !selection.tenantId().equals(availability.tenantId()) || !selection.merchantId().equals(availability.merchantId())) throw new NegotiationException(CONFIGURATION_MISMATCH);
        if (!availability.configured() || !availability.enabled() || !availability.available()
                || now.isBefore(availability.validFrom()) || !now.isBefore(availability.expiresAt())) throw new NegotiationException(UNAVAILABLE);
        var matches = new ArrayList<NegotiatedDescriptor>();
        for (AdapterCombination combination : descriptor.combinations()) {
            if (!combination.methodId().equals(selection.methodId()) || !combination.credentialTypeId().equals(selection.credentialTypeId())
                    || !combination.actionTypeIds().contains(selection.actionTypeId()) || !combination.operations().contains(selection.operation())
                    || !combination.capabilities().containsAll(selection.requiredCapabilities())) continue;
            for (CurrencyLimit limit : combination.limits()) {
                if (limit.permits(selection.currency(), selection.minorUnits())) matches.add(new NegotiatedDescriptor(descriptor.digest(), selection, combination, limit, availability.accountAlias()));
            }
        }
        if (matches.isEmpty()) throw new NegotiationException(UNSUPPORTED_COMBINATION);
        if (matches.size() != 1) throw new NegotiationException(AMBIGUOUS_COMBINATION);
        return matches.getFirst();
    }
}
