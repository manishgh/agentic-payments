package io.agenticpayments.sdk.descriptor;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.agenticpayments.sdk.descriptor.NegotiationException.Code.*;

class AdapterRegistryTest {
    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");
    private static final CurrencyLimit EUR = new CurrencyLimit("EUR", 2, "1", "100000");
    private static final AdapterCombination CARD = new AdapterCombination("card", "vault-token", Set.of("none", "challenge-3ds"), Set.of("charge"), Set.of("idempotency", "lookup"), List.of(EUR));
    private static final AdapterCombination BANK = new AdapterCombination("bank-transfer", "bank-reference", Set.of("redirect"), Set.of("charge"), Set.of("lookup"), List.of(EUR));
    private AdapterDescriptor descriptor(String provider, List<AdapterCombination> combinations) {
        return new AdapterDescriptor(AdapterDescriptor.SCHEMA_VERSION, provider, "1.2.0", Set.of("0.1.0-draft"), Set.of("fixture-generic-0.1"), Set.of("idempotency", "lookup"), combinations);
    }
    private AdapterSelection selection(String provider, String method, String credential, String action, Set<String> required) {
        return new AdapterSelection("tenant-demo", "merchant-demo", provider, "1.2.0", "0.1.0-draft", "fixture-generic-0.1", method, credential, action, required, "charge", "EUR", "1250");
    }
    private AdapterAvailability available(String provider) {
        return new AdapterAvailability(provider, "1.2.0", "tenant-demo", "merchant-demo", "account-fixture", true, true, true, NOW.minusSeconds(1), NOW.plusSeconds(60));
    }
    @Test void independentProvidersAndExplicitAuthenticationTuplesNegotiate() {
        var registry = new AdapterRegistry(List.of(descriptor("fixture-card-provider", List.of(CARD)), descriptor("fixture-bank-provider", List.of(BANK))));
        var card = registry.negotiate(selection("fixture-card-provider", "card", "vault-token", "challenge-3ds", Set.of("idempotency")), available("fixture-card-provider"), NOW);
        var bank = registry.negotiate(selection("fixture-bank-provider", "bank-transfer", "bank-reference", "redirect", Set.of("lookup")), available("fixture-bank-provider"), NOW);
        assertEquals("EUR", card.currencyLimit().currency());
        assertNotEquals(card.descriptorDigest(), bank.descriptorDigest());
        assertEquals(EXECUTION_DISABLED, assertThrows(NegotiationException.class, () -> LocalExecutionPolicy.requireExecutable(card)).code());
    }
    @Test void providerWideCapabilitiesCannotInventAnAuthenticationOrMethodCrossProduct() {
        var registry = new AdapterRegistry(List.of(descriptor("fixture-provider", List.of(CARD, BANK))));
        for (AdapterSelection invalid : List.of(
                selection("fixture-provider", "bank-transfer", "bank-reference", "challenge-3ds", Set.of()),
                selection("fixture-provider", "card", "bank-reference", "none", Set.of()),
                selection("fixture-provider", "bank-transfer", "bank-reference", "redirect", Set.of("idempotency")))) {
            assertEquals(UNSUPPORTED_COMBINATION, assertThrows(NegotiationException.class, () -> registry.negotiate(invalid, available("fixture-provider"), NOW)).code());
        }
    }
    @Test void supportedButUnconfiguredDisabledUnavailableExpiredOrWrongScopeFailsClosed() {
        var registry = new AdapterRegistry(List.of(descriptor("fixture-provider", List.of(CARD))));
        var selection = selection("fixture-provider", "card", "vault-token", "none", Set.of());
        for (int disabled = 0; disabled < 3; disabled++) {
            var availability = new AdapterAvailability("fixture-provider", "1.2.0", "tenant-demo", "merchant-demo", "fixture-account", disabled != 0, disabled != 1, disabled != 2, NOW.minusSeconds(1), NOW.plusSeconds(1));
            assertEquals(UNAVAILABLE, assertThrows(NegotiationException.class, () -> registry.negotiate(selection, availability, NOW)).code());
        }
        assertEquals(UNAVAILABLE, assertThrows(NegotiationException.class, () -> registry.negotiate(selection, available("fixture-provider"), NOW.plusSeconds(60))).code());
        var wrongTenant = new AdapterAvailability("fixture-provider", "1.2.0", "tenant-other", "merchant-demo", "fixture-account", true, true, true, NOW, NOW.plusSeconds(1));
        assertEquals(CONFIGURATION_MISMATCH, assertThrows(NegotiationException.class, () -> registry.negotiate(selection, wrongTenant, NOW)).code());
    }
    @Test void exactRevisionAndUnknownRequiredCapabilityCannotDowngrade() {
        var registry = new AdapterRegistry(List.of(descriptor("fixture-provider", List.of(CARD))));
        assertEquals(UNKNOWN_DESCRIPTOR, assertThrows(NegotiationException.class, () -> registry.require("fixture-provider", "latest")).code());
        assertEquals(UNSUPPORTED_CAPABILITY, assertThrows(NegotiationException.class, () -> registry.negotiate(selection("fixture-provider", "card", "vault-token", "none", Set.of("atomic-no-effect-seal")), available("fixture-provider"), NOW)).code());
    }
    @Test void decimalBoundsAndExplicitNoneAuthenticationAreValidated() {
        for (String invalid : List.of("0", "-1", "1.2", "1e3", "01", "1000000000000000000000000000000")) {
            assertThrows(IllegalArgumentException.class, () -> new CurrencyLimit("EUR", 2, invalid, "100"));
        }
        assertThrows(IllegalArgumentException.class, () -> new CurrencyLimit("EUR", 2, "101", "100"));
        assertThrows(IllegalArgumentException.class, () -> new AdapterCombination("card", "token", Set.of(), Set.of("charge"), Set.of(), List.of(EUR)));
        var huge = new CurrencyLimit("JPY", 0, "1", "999999999999999999999999999999");
        assertTrue(huge.permits("JPY", "999999999999999999999999999999"));
        assertFalse(huge.permits("EUR", "1"));
    }
    @Test void conflictingRevisionCannotReplaceRegisteredDescriptor() {
        var first = descriptor("fixture-provider", List.of(CARD));
        var second = descriptor("fixture-provider", List.of(BANK));
        assertThrows(IllegalArgumentException.class, () -> new AdapterRegistry(List.of(first, second)));
        assertEquals(first, new AdapterRegistry(List.of(first, first)).require("fixture-provider", "1.2.0"));
    }
    @Test void semanticOrderDoesNotChangeDescriptorDigest() {
        var first = descriptor("fixture-provider", List.of(CARD, BANK));
        var second = descriptor("fixture-provider", List.of(BANK, CARD));
        assertEquals(first.digest(), second.digest());
        assertNotEquals(first.digest(), descriptor("fixture-provider", List.of(CARD)).digest());
    }
    @Test void overlappingTupleIsRejectedInsteadOfSelectingByListOrder() {
        var overlap = new AdapterCombination("card", "vault-token", Set.of("none"), Set.of("charge"), Set.of("idempotency", "lookup"), List.of(new CurrencyLimit("EUR", 2, "1000", "2000")));
        var registry = new AdapterRegistry(List.of(descriptor("fixture-provider", List.of(CARD, overlap))));
        assertEquals(AMBIGUOUS_COMBINATION, assertThrows(NegotiationException.class, () -> registry.negotiate(selection("fixture-provider", "card", "vault-token", "none", Set.of()), available("fixture-provider"), NOW)).code());
    }
    @Test void amountOutsideDeclaredTupleLimitsIsRefused() {
        var registry = new AdapterRegistry(List.of(descriptor("fixture-provider", List.of(CARD))));
        var tooMuch = new AdapterSelection("tenant-demo", "merchant-demo", "fixture-provider", "1.2.0", "0.1.0-draft", "fixture-generic-0.1", "card", "vault-token", "none", Set.of(), "charge", "EUR", "100001");
        assertEquals(UNSUPPORTED_COMBINATION, assertThrows(NegotiationException.class, () -> registry.negotiate(tooMuch, available("fixture-provider"), NOW)).code());
    }
}
