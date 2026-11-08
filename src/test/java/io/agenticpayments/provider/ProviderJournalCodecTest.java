package io.agenticpayments.provider;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.agenticpayments.infrastructure.provider.*;
import io.agenticpayments.sdk.*;
import io.agenticpayments.sdk.execution.v1.*;
import io.agenticpayments.sdk.execution.v1.compat.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProviderJournalCodecTest {
    private final ProviderJournalCodec codec = new ProviderJournalCodec();
    private final ObjectMapper json = new ObjectMapper();
    private static final Instant TIME = Instant.parse("2026-11-08T01:02:03.123456789Z");
    private static final ExecutionBinding BINDING = new ExecutionBinding("simulator", "account-ref", "region-test", "config-ref", "v1", "credential-ref", "v1", "platform-owner");
    private ExecutionOffer offer(boolean reverse) {
        var limits = List.of(new ExecutionOffer.CurrencyLimit("EUR", 2, 1, 999999), new ExecutionOffer.CurrencyLimit("USD", 2, 1, 999999));
        return new ExecutionOffer(ExecutionOffer.VERSION, "codec-profile-1", "simulator", "1", List.of(new ExecutionOffer.Combination(
                "synthetic-card", "synthetic-reference", Set.of(ExecutionOffer.ActionType.SYNTHETIC_CHALLENGE, ExecutionOffer.ActionType.SDK_HANDOFF),
                Set.of(OperationPurpose.CHARGE, OperationPurpose.AUTHORIZE), ExecutionOffer.PreparationEffect.EFFECT_FREE,
                Set.of(ExecutionOffer.Capability.ATOMIC_NO_EFFECT_FENCE, ExecutionOffer.Capability.VERIFIED_EVENTS), reverse ? limits.reversed() : limits)));
    }
    private ExecutionOperation operation(ExecutionOffer offer, OperationPurpose purpose) {
        var c = offer.combinations().getFirst(); var money = new ExactMoney("USD", 2, 1250);
        var selection = CapabilitySelection.select(offer, new CapabilitySelection.Requirement(offer.contractVersion(), offer.profileVersion(), offer.providerId(), offer.implementationRevision(),
                BINDING.accountRef(), BINDING.region(), c.methodId(), c.credentialFormatId(), c.actions(), purpose, c.preparationEffect(), c.capabilities(), money), BINDING);
        return ExecutionOperation.create(new ExecutionIdentity("fixture-tenant", "merchant-demo", "fixture-run", "intent", "attempt", "operation", "caller-key"), BINDING, selection, money, purpose);
    }
    private ExecutionEvidence evidence(ExecutionEvidence.State state) {
        var op = operation(offer(true), state == ExecutionEvidence.State.AUTHORIZED ? OperationPurpose.AUTHORIZE : OperationPurpose.CHARGE);
        var action = state == ExecutionEvidence.State.ACTION_REQUIRED ? Optional.of(new TrustedActionDescriptor(ExecutionOffer.ActionType.SYNTHETIC_CHALLENGE, "action-ref", op.fingerprint(), op.binding().digest(), TIME.plusNanos(7))) : Optional.<TrustedActionDescriptor>empty();
        return new ExecutionEvidence(op, "evidence-id", state == ExecutionEvidence.State.UNKNOWN ? Optional.empty() : Optional.of("provider-resource-ref"),
                state == ExecutionEvidence.State.NO_EFFECT_FENCED ? ExecutionEvidence.Source.FENCE : ExecutionEvidence.Source.SUBMIT, TIME, state, action);
    }
    @Test void fullOperationRoundTripPreservesNoncanonicalLimitListOrderAndCompleteSelection() {
        var op = operation(offer(true), OperationPurpose.CHARGE);
        assertEquals("USD", op.selection().combination().limits().getFirst().currency());
        var decoded = codec.decodeOperation(codec.encodeOperation(op));
        assertEquals(op, decoded); assertEquals(op.fingerprint(), decoded.fingerprint());
        decoded.selection().validate(offer(true), BINDING);
        assertEquals(new SubmissionRequest(op, Optional.empty()), codec.decodeSubmission(codec.encodeSubmission(new SubmissionRequest(op, Optional.empty()))));
        assertEquals(codec.encodeOperation(op), codec.encodeOperation(decoded));
    }
    @Test void everyRepresentableEvidenceStateAndExactNanosecondsRoundTrips() {
        for (var state : ExecutionEvidence.State.values()) {
            var fact = evidence(state); var encoded = codec.encodeEvidence(fact); var decoded = codec.decodeEvidence(encoded);
            assertEquals(fact, decoded); assertEquals(TIME, decoded.observedAt());
            assertEquals(codec.evidenceDigest(fact), codec.evidenceDigest(decoded));
            assertTrue(encoded.contains("123456789Z")); assertFalse(decoded.toString().contains("provider-resource-ref"));
        }
        var pending = evidence(ExecutionEvidence.State.PENDING);
        var alteredSource = new ExecutionEvidence(pending.operation(), pending.evidenceId(), pending.providerResourceRef(), ExecutionEvidence.Source.RECONCILIATION, pending.observedAt(), pending.state(), pending.action());
        assertNotEquals(codec.evidenceDigest(pending), codec.evidenceDigest(alteredSource));
    }
    @Test void legacyRoundTripRetainsAllScopeUuidScenarioAndMoneyFields() {
        var op = operation(LocalFakeExecutionAdapter.offer(), OperationPurpose.CHARGE);
        var configuration = configuration(ignored -> "response-loss-after-acceptance"); var legacy = configuration.expectedLegacy(op);
        assertEquals(legacy, codec.decodeLegacy(codec.encodeLegacy(legacy)));
        assertEquals(op.fingerprint(), legacy.identity().requestDigest());
        assertEquals("response-loss-after-acceptance", legacy.scenarioId());
    }
    @Test void duplicateFieldsAtRootAndNestedLevelsAreRejectedEvenWhenValuesAreIdentical() {
        String encoded = codec.encodeOperation(operation(offer(false), OperationPurpose.CHARGE));
        refused(() -> codec.decodeOperation(encoded.replace("\"kind\":\"operation\"", "\"kind\":\"operation\",\"kind\":\"operation\"")));
        refused(() -> codec.decodeOperation(encoded.replace("\"minorUnits\":1250", "\"minorUnits\":1250,\"minorUnits\":1250")));
    }
    @Test void unknownMissingNullAndWrongKindOrVersionFieldsFailClosed() throws Exception {
        var op = operation(offer(false), OperationPurpose.CHARGE); var root = (ObjectNode)json.readTree(codec.encodeOperation(op));
        var unknown = root.deepCopy(); ((ObjectNode)unknown.path("value").path("identity")).put("unknown", "CANARY_PRIVATE"); refused(() -> codec.decodeOperation(unknown.toString()));
        var missing = root.deepCopy(); ((ObjectNode)missing.path("value")).remove("fingerprint"); refused(() -> codec.decodeOperation(missing.toString()));
        var explicitNull = root.deepCopy(); ((ObjectNode)explicitNull.path("value")).putNull("binding"); refused(() -> codec.decodeOperation(explicitNull.toString()));
        var version = root.deepCopy(); version.put("codecVersion", "future-codec"); refused(() -> codec.decodeOperation(version.toString()));
        var kind = root.deepCopy(); kind.put("kind", "evidence"); refused(() -> codec.decodeOperation(kind.toString()));
    }
    @Test void wrongNumericTypesOverflowAndFingerprintSubstitutionAreRejected() throws Exception {
        var root = (ObjectNode)json.readTree(codec.encodeOperation(operation(offer(false), OperationPurpose.CHARGE)));
        var textual = root.deepCopy(); ((ObjectNode)textual.path("value").path("money")).put("minorUnits", "1250"); refused(() -> codec.decodeOperation(textual.toString()));
        var decimal = root.deepCopy(); ((ObjectNode)decimal.path("value").path("money")).put("minorUnits", 1250.0); refused(() -> codec.decodeOperation(decimal.toString()));
        refused(() -> codec.decodeOperation(root.toString().replace("\"minorUnits\":1250", "\"minorUnits\":1e3")));
        refused(() -> codec.decodeOperation(root.toString().replace("\"minorUnits\":1250", "\"minorUnits\":999999999999999999999999")));
        var changed = root.deepCopy(); ((ObjectNode)changed.path("value")).put("fingerprint", "sha256:" + "0".repeat(64)); refused(() -> codec.decodeOperation(changed.toString()));
    }
    @Test void duplicateCapabilitiesOverlongCollectionsAndPayloadsCannotBeDefaulted() throws Exception {
        var root = (ObjectNode)json.readTree(codec.encodeOperation(operation(offer(false), OperationPurpose.CHARGE)));
        var duplicate = root.deepCopy(); var capabilities = (ArrayNode)duplicate.path("value").path("selection").path("combination").path("capabilities"); capabilities.add(capabilities.get(0));
        refused(() -> codec.decodeOperation(duplicate.toString()));
        var many = root.deepCopy(); var limits = (ArrayNode)many.path("value").path("selection").path("combination").path("limits"); var one = limits.get(0).deepCopy();
        for (int index = 0; index < 32; index++) limits.add(one);
        refused(() -> codec.decodeOperation(many.toString()));
        refused(() -> codec.decodeOperation(" ".repeat(65537)));
        refused(() -> codec.decodeOperation(root + " {}"));
    }
    @Test void requiredOptionalFieldsAndCanonicalInstantsAreStrict() throws Exception {
        var root = (ObjectNode)json.readTree(codec.encodeEvidence(evidence(ExecutionEvidence.State.UNKNOWN)));
        assertTrue(root.path("value").path("providerResourceRef").isNull()); assertTrue(root.path("value").path("action").isNull());
        var missing = root.deepCopy(); ((ObjectNode)missing.path("value")).remove("providerResourceRef"); refused(() -> codec.decodeEvidence(missing.toString()));
        var noncanonical = root.deepCopy(); ((ObjectNode)noncanonical.path("value")).put("observedAt", "2026-11-08T01:02:03.123456789+00:00"); refused(() -> codec.decodeEvidence(noncanonical.toString()));
        var unknown = root.deepCopy(); ((ObjectNode)unknown.path("value")).put("state", "FUTURE_STATE_CANARY_PRIVATE"); refused(() -> codec.decodeEvidence(unknown.toString()));
    }
    @Test void trustedMappingMatchesUnchangedBridgeAndOriginalObservationConfiguration() {
        var op = operation(LocalFakeExecutionAdapter.offer(), OperationPurpose.CHARGE); var configuration = configuration(ignored -> "success");
        var observed = new ArrayList<ChargeOperation>();
        CompatibilityBindingJournal bindingJournal = new CompatibilityBindingJournal() {
            public ChargeOperation bind(ExecutionOperation operation, ChargeOperation candidate) { observed.add(candidate); return candidate; }
            public DispatchClaim claim(SubmissionRequest request) { throw new UnsupportedOperationException(); }
            public ExecutionEvidence record(ExecutionEvidence evidence) { throw new UnsupportedOperationException(); }
            public Optional<ExecutionEvidence> read(ExecutionOperation operation) { return Optional.empty(); }
        };
        PaymentAdapter peer = new PaymentAdapter() {
            public CapabilityOffer capabilities() { return CapabilityOffer.localFake(); }
            public Preparation prepare(ChargeOperation operation) { return new Preparation(ActionType.NONE); }
            public Fact execute(ChargeOperation operation) { throw new UnsupportedOperationException(); }
            public LookupResult lookup(OperationIdentity identity) { return LookupResult.notFound(); }
            public Fact sealNoEffect(ChargeOperation operation) { throw new UnsupportedOperationException(); }
        };
        var bridge = new LocalFakeExecutionAdapter(peer, bindingJournal, BINDING, configuration.tenantId(), configuration.merchantId(), configuration.runId(), TIME, TIME.plusSeconds(600), configuration::scenarioFor);
        assertEquals(PreparationResult.Status.READY, bridge.prepare(op).status());
        assertEquals(List.of(configuration.expectedLegacy(op)), observed);
    }
    @Test void wrongScopeIsRejectedBeforeScenarioPolicyAndErrorsNeverEchoPrivateInput() {
        var calls = new AtomicInteger(); var configuration = configuration(ignored -> { calls.incrementAndGet(); return "success"; });
        var op = operation(LocalFakeExecutionAdapter.offer(), OperationPurpose.CHARGE); var id = op.identity();
        var wrong = ExecutionOperation.create(new ExecutionIdentity("other-tenant", id.merchantId(), id.runId(), id.intentId(), id.attemptId(), id.operationId(), id.callerIdempotencyKey()), op.binding(), op.selection(), op.money(), op.purpose());
        assertThrows(ExecutionFailure.class, () -> configuration.expectedLegacy(wrong)); assertEquals(0, calls.get());
        assertFalse(configuration.toString().contains("credential-ref"));
        refused(() -> codec.decodeOperation("{\"CANARY_PRIVATE_SECRET\":true}"));
    }
    @Test void nullOrThrowingScenarioResolverIsSanitizedBeforeAnyJournalDatabaseActivity() {
        var op = operation(LocalFakeExecutionAdapter.offer(), OperationPurpose.CHARGE); var candidate = configuration(ignored -> "success").expectedLegacy(op);
        var connections = new AtomicInteger();
        var database = (DataSource)Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, arguments) -> {
            connections.incrementAndGet(); throw new IllegalStateException("CANARY_PRIVATE_DATABASE");
        });
        for (var configuration : List.of(configuration(ignored -> null), configuration(ignored -> { throw new IllegalStateException("CANARY_PRIVATE_RESOLVER"); }))) {
            var journal = new JdbcCompatibilityBindingJournal(database, configuration);
            var fact = new ExecutionEvidence(op, "unknown", Optional.empty(), ExecutionEvidence.Source.RECONCILIATION, TIME, ExecutionEvidence.State.UNKNOWN, Optional.empty());
            List<Runnable> commands = List.of(() -> journal.bind(op, candidate), () -> journal.read(op),
                    () -> journal.claim(new SubmissionRequest(op, Optional.empty())), () -> journal.record(fact));
            for (var command : commands) {
                var failure = assertThrows(ExecutionFailure.class, command::run);
                assertTrue(Set.of(ExecutionFailure.Code.UNSUPPORTED_COMBINATION, ExecutionFailure.Code.CONFIGURATION_UNAVAILABLE).contains(failure.code()));
                assertNull(failure.getCause()); assertFalse(failure.toString().contains("CANARY_PRIVATE"));
            }
        }
        assertEquals(0, connections.get());
    }
    private ProviderJournalConfiguration configuration(java.util.function.Function<ExecutionOperation, String> scenario) {
        return new ProviderJournalConfiguration("fixture-tenant", "merchant-demo", "fixture-run", BINDING, ProviderJournalConfiguration.MAPPING_POLICY_VERSION, "scenario-policy", "v1", scenario);
    }
    private void refused(Runnable action) {
        var failure = assertThrows(ExecutionFailure.class, action::run);
        assertEquals(ExecutionFailure.Code.CONTRACT_VIOLATION, failure.code()); assertNull(failure.getCause());
        assertFalse(failure.toString().contains("CANARY_PRIVATE")); assertEquals(0, failure.getStackTrace().length);
    }
}
