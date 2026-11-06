package io.agenticpayments.sdk.descriptor;

import io.agenticpayments.sdk.CapabilityOffer;
import java.util.Set;

/** Defense in depth: descriptor registration alone can never activate a real provider. */
public final class LocalExecutionPolicy {
    private LocalExecutionPolicy() { }
    public static void requireExecutable(NegotiatedDescriptor negotiated) {
        AdapterSelection selection = negotiated.selection();
        if (!selection.providerId().equals("simulator") || !selection.profile().equals(CapabilityOffer.PROFILE)
                || !selection.extensionVersion().equals(CapabilityOffer.EXTENSION_VERSION)
                || !selection.methodId().equals("synthetic-card") || !selection.credentialTypeId().equals("synthetic-reference")
                || !Set.of("none", "synthetic-challenge").contains(selection.actionTypeId())
                || !selection.operation().equals("synthetic-charge") || !Set.of("EUR", "USD").contains(selection.currency())
                || negotiated.currencyLimit().exponent() != 2
                || !negotiated.combination().capabilities().containsAll(CapabilityOffer.CATALOG)) {
            throw new NegotiationException(NegotiationException.Code.EXECUTION_DISABLED);
        }
        // Check local execution amount bounds separately from generic metadata limits.
        try { new io.agenticpayments.sdk.Money(selection.currency(), Long.parseLong(selection.minorUnits())); }
        catch (IllegalArgumentException invalid) { throw new NegotiationException(NegotiationException.Code.EXECUTION_DISABLED); }
    }
}
