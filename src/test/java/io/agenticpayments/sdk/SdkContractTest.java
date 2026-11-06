package io.agenticpayments.sdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SdkContractTest {
    private static final String DIGEST = "sha256:" + "a".repeat(64);

    private static OperationIdentity identity() {
        return new OperationIdentity(new ExecutionScope("tenant-demo", UUID.randomUUID(), "merchant-demo"),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), DIGEST);
    }

    @Test
    void moneyRoundTripsWireDecimalWithoutFloatingPoint() {
        assertEquals("1250", Money.parse("EUR", "1250").decimalString());
        assertEquals(999_999_999_999L, Money.parse("USD", "999999999999").minorUnits());
        assertThrows(IllegalArgumentException.class, () -> new Money("EUR", 1_000_000_000_000L));
        assertThrows(IllegalArgumentException.class, () -> new Money("GBP", 1250));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "+1", "1.0", "1e3", "01", " 1", "1 ", "1000000000000", "", "１２５０"})
    void refusesNonCanonicalMoney(String value) {
        assertThrows(IllegalArgumentException.class, () -> Money.parse("EUR", value));
    }

    @Test
    void unknownRequiredCapabilityAndDowngradeFailClosed() {
        var offer = CapabilityOffer.localFake();
        var selection = new CapabilityCombination("simulator", "synthetic-card", "synthetic-reference",
                Set.of(ActionType.SYNTHETIC_CHALLENGE));
        assertSame(offer, offer.negotiate(CapabilityOffer.EXTENSION_VERSION, CapabilityOffer.PROFILE,
                Set.of("exact-money", "customer-action"), selection));
        var unknown = assertThrows(CapabilityRefusedException.class, () -> offer.negotiate(
                CapabilityOffer.EXTENSION_VERSION, CapabilityOffer.PROFILE, Set.of("real-ap2"), selection));
        assertEquals(CapabilityRefusedException.Code.UNSUPPORTED_CAPABILITY, unknown.code());
        var downgrade = assertThrows(CapabilityRefusedException.class, () -> offer.negotiate(
                "0.0", CapabilityOffer.PROFILE, Set.of(), selection));
        assertEquals(CapabilityRefusedException.Code.UNSUPPORTED_PROFILE, downgrade.code());
    }

    @Test
    void providerMethodCredentialAxesAreNotACrossProduct() {
        var invalid = new CapabilityCombination("simulator", "voucher", "synthetic-reference", Set.of(ActionType.NONE));
        var refusal = assertThrows(CapabilityRefusedException.class, () -> CapabilityOffer.localFake().negotiate(
                CapabilityOffer.EXTENSION_VERSION, CapabilityOffer.PROFILE, Set.of(), invalid));
        assertEquals(CapabilityRefusedException.Code.UNSUPPORTED_TENDER, refusal.code());
        assertThrows(IllegalArgumentException.class, () -> new CapabilityOffer(CapabilityOffer.EXTENSION_VERSION,
                CapabilityOffer.PROFILE, CapabilityOffer.CATALOG_VERSION, CapabilityOffer.CATALOG, java.util.List.of(invalid)));
    }

    @Test
    void advertisedButUnavailableActionIsRefused() {
        var noChallenge = new CapabilityCombination("simulator", "synthetic-card", "synthetic-reference", Set.of(ActionType.NONE));
        var offer = new CapabilityOffer(CapabilityOffer.EXTENSION_VERSION, CapabilityOffer.PROFILE,
                CapabilityOffer.CATALOG_VERSION, CapabilityOffer.CATALOG, java.util.List.of(noChallenge));
        var challenge = new CapabilityCombination("simulator", "synthetic-card", "synthetic-reference", Set.of(ActionType.SYNTHETIC_CHALLENGE));
        assertThrows(CapabilityRefusedException.class, () -> offer.negotiate(CapabilityOffer.EXTENSION_VERSION,
                CapabilityOffer.PROFILE, Set.of("customer-action"), challenge));
    }

    @Test
    void capabilityCollectionsCannotChangeAfterSelection() {
        var actions = new HashSet<>(Set.of(ActionType.NONE));
        var combination = new CapabilityCombination("simulator", "synthetic-card", "synthetic-reference", actions);
        var capabilities = new HashSet<>(CapabilityOffer.CATALOG);
        var combinations = new ArrayList<>(java.util.List.of(combination));
        var offer = new CapabilityOffer(CapabilityOffer.EXTENSION_VERSION, CapabilityOffer.PROFILE,
                CapabilityOffer.CATALOG_VERSION, capabilities, combinations);
        actions.add(ActionType.SYNTHETIC_CHALLENGE);
        capabilities.clear();
        combinations.clear();
        assertEquals(Set.of(ActionType.NONE), offer.combinations().getFirst().actionTypes());
        assertEquals(CapabilityOffer.CATALOG, offer.capabilities());
        assertThrows(UnsupportedOperationException.class, () -> offer.combinations().clear());
    }

    @Test
    void operationIdentityBindsTenantRunMerchantAndDigest() {
        var first = identity();
        var otherScope = new ExecutionScope(first.scope().tenantId(), UUID.randomUUID(), first.scope().merchantId());
        var otherRun = new OperationIdentity(otherScope, first.intentId(), first.attemptId(), first.operationId(), first.adapterKey(), first.requestDigest());
        assertNotEquals(first, otherRun);
        assertThrows(IllegalArgumentException.class, () -> new OperationIdentity(first.scope(), first.intentId(),
                first.attemptId(), first.operationId(), first.adapterKey(), "sha256:placeholder"));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionScope("tenant\nsecret", UUID.randomUUID(), "merchant-demo"));
    }

    @Test
    void absenceAndTransportFailureCannotBeFinalNoEffectEvidence() {
        assertTrue(LookupResult.notFound().fact().isEmpty());
        assertTrue(LookupResult.unavailable().fact().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> new LookupResult(LookupResult.Status.FOUND, Optional.empty()));
        var fact = new Fact(UUID.randomUUID(), identity(), new Money("EUR", 1250), 1, Outcome.PENDING);
        assertThrows(IllegalArgumentException.class,
                () -> new LookupResult(LookupResult.Status.NOT_FOUND, Optional.of(fact)));
        assertEquals(Outcome.PENDING, LookupResult.found(fact).fact().orElseThrow().outcome());
        assertThrows(IllegalArgumentException.class,
                () -> new Fact(UUID.randomUUID(), identity(), new Money("EUR", 1250), 0, Outcome.NO_EFFECT));
    }

    @Test
    void scenarioInputsAndExceptionsContainNoArbitraryProviderPayload() {
        var operation = new ChargeOperation(identity(), new Money("EUR", 1250), "success");
        assertEquals("success", operation.scenarioId());
        String canary = "SYNTHETIC_PRIVATE_CANARY";
        var failure = assertThrows(IllegalArgumentException.class,
                () -> new ChargeOperation(identity(), new Money("EUR", 1250), canary));
        assertFalse(failure.toString().contains(canary));
        assertEquals("The existing adapter operation requires reconciliation.", new AdapterUnavailableException().getMessage());
        assertNull(new AdapterUnavailableException().getCause());
    }
}
