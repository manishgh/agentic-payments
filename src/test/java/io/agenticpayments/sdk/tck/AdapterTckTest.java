package io.agenticpayments.sdk.tck;

import io.agenticpayments.sdk.*;
import io.agenticpayments.sdk.descriptor.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdapterTckTest {
    private static final String PROFILE = "fixture-generic-0.1";
    private TckPlan plan(boolean sealRequired) {
        return new TckPlan(TckPlan.VERSION, PROFILE, sealRequired ? Set.of("atomic-no-effect-seal") : Set.of(),
                sealRequired ? Set.copyOf(TckPlan.CASES) : Set.of("AT-PREPARE", "AT-IDEMPOTENCY", "AT-LOOKUP"), TckPlan.CASES, "42", Instant.parse("2026-11-06T10:00:00Z"));
    }
    @Test void independentNoSealAdapterPassesBaselineWithTruthfulExclusion() {
        var report = new AdapterTck().run(context -> new MemoryFixture(false, false, false), plan(false));
        assertTrue(report.requiredCasesPassed());
        assertEquals(TckStatus.UNSUPPORTED, report.results().getLast().status());
        assertFalse(report.exclusions().isEmpty());
    }
    @Test void requiredUnsupportedSealCannotPass() {
        var report = new AdapterTck().run(context -> new MemoryFixture(false, false, false), plan(true));
        assertFalse(report.requiredCasesPassed());
        assertTrue(report.results().stream().noneMatch(result -> result.status() == TckStatus.PASS));
    }
    @Test void advertisedButMissingExtensionIsIncomplete() {
        var report = new AdapterTck().run(context -> new MemoryFixture(true, false, false), plan(true));
        assertFalse(report.requiredCasesPassed());
        assertEquals(TckStatus.INCOMPLETE, report.results().getLast().status());
    }
    @Test void duplicateEconomicEffectFailsInsteadOfTrustingSuccessResponse() {
        var report = new AdapterTck().run(context -> new MemoryFixture(false, true, false), plan(false));
        assertFalse(report.requiredCasesPassed());
        assertEquals(TckStatus.FAIL, report.results().stream().filter(result -> result.caseId().equals("AT-IDEMPOTENCY")).findFirst().orElseThrow().status());
    }
    @Test void exceptionsCannotLeakSyntheticSensitiveCanaryIntoReport() {
        var report = new AdapterTck().run(context -> new MemoryFixture(false, false, true), plan(false));
        assertFalse(report.requiredCasesPassed());
        assertFalse(report.toString().contains("CANARY_PRIVATE_PROVIDER_PAYLOAD"));
    }
    @Test void unknownCaseCannotBecomeAnImplicitPass() {
        var selected = new TckPlan(TckPlan.VERSION, PROFILE, Set.of(), Set.of("AT-FUTURE"), List.of("AT-FUTURE"), "42", Instant.EPOCH);
        var report = new AdapterTck().run(context -> new MemoryFixture(false, false, false), selected);
        assertFalse(report.requiredCasesPassed());
        assertEquals(TckStatus.INCOMPLETE, report.results().getFirst().status());
    }
    @Test void declaringLocalProfileCannotOmitItsMandatoryCapabilities() {
        var selected = new TckPlan(TckPlan.VERSION, CapabilityOffer.PROFILE, Set.of("atomic-no-effect-seal"), Set.copyOf(TckPlan.CASES), TckPlan.CASES, "42", Instant.EPOCH);
        var report = new AdapterTck().run(context -> new MemoryFixture(true, false, false) {
            @Override public AdapterDescriptor descriptor() {
                var descriptor = super.descriptor();
                return new AdapterDescriptor(descriptor.schemaVersion(), descriptor.providerId(), descriptor.revision(), descriptor.extensionVersions(), Set.of(CapabilityOffer.PROFILE), descriptor.capabilities(), descriptor.combinations());
            }
        }, selected);
        assertFalse(report.requiredCasesPassed());
        assertTrue(report.results().stream().allMatch(result -> result.status() == TckStatus.INCOMPLETE));
    }
    @Test void advertisedFeaturesOmittedFromAPlanRemainExplicitlyUnevaluated() {
        var selected = new TckPlan(TckPlan.VERSION, PROFILE, Set.of(), Set.of("AT-PREPARE"), List.of("AT-PREPARE"), "42", Instant.EPOCH);
        var report = new AdapterTck().run(context -> new MemoryFixture(true, false, false), selected);
        assertTrue(report.requiredCasesPassed());
        assertTrue(report.exclusions().stream().anyMatch(text -> text.contains("not fully evaluated") && text.contains("atomic-no-effect-seal")));
    }
    @Test void failedFixtureCloseCannotRetainPassingCapabilityEvidence() {
        var selected = new TckPlan(TckPlan.VERSION, PROFILE, Set.of(), Set.of("AT-IDEMPOTENCY"), List.of("AT-IDEMPOTENCY"), "42", Instant.EPOCH);
        var report = new AdapterTck().run(context -> new MemoryFixture(false, false, false) {
            @Override public AdapterDescriptor descriptor() {
                return new AdapterDescriptor(AdapterDescriptor.SCHEMA_VERSION, "fixture-independent", "1", Set.of("0.1.0-draft"), Set.of(PROFILE), Set.of("idempotent-execution"),
                        List.of(new AdapterCombination("synthetic-card", "synthetic-reference", Set.of("none"), Set.of("synthetic-charge"), Set.of("idempotent-execution"), List.of(new CurrencyLimit("EUR", 2, "1", "999999")))));
            }
            @Override public void close() { throw new IllegalStateException("CANARY_PRIVATE_PROVIDER_PAYLOAD"); }
        }, selected);
        assertFalse(report.requiredCasesPassed());
        assertEquals(1, report.results().size());
        assertEquals(TckStatus.INCOMPLETE, report.results().getFirst().status());
        assertTrue(report.exclusions().stream().anyMatch(text -> text.contains("not fully evaluated") && text.contains("idempotent-execution")));
        assertFalse(report.toString().contains("CANARY_PRIVATE_PROVIDER_PAYLOAD"));
    }
    @Test void reportRetainsRequiredOptionalCasesAndReproduciblePlanIdentity() {
        var selected = plan(false);
        var report = new AdapterTck().run(context -> new MemoryFixture(false, false, false), selected);
        assertEquals(selected.requiredCaseIds(), report.plan().requiredCaseIds());
        assertEquals(selected.requiredCapabilities(), report.plan().requiredCapabilities());
        assertEquals(selected.caseIds(), report.plan().caseIds());
        assertEquals("42", report.plan().seed());
        assertEquals(selected.initialClock().toString(), report.plan().initialClock());
        assertEquals(selected.evidence().digest(), report.plan().digest());
        assertNotEquals(plan(true).evidence().digest(), report.plan().digest());
        assertTrue(report.results().stream().allMatch(result -> result.safeAssertions().containsKey("fixtureNamespace")));
    }
    /** Independent adapter fixture: no JDBC, no core and no reference adapter reuse. */
    private static class MemoryFixture implements AdapterTckFixture, TckExecutionDriver {
        private final Map<UUID, ChargeOperation> requests = new HashMap<>();
        private final Map<UUID, Fact> facts = new HashMap<>();
        private final boolean advertisesSeal, duplicates, fails;
        private long charges;
        MemoryFixture(boolean advertisesSeal, boolean duplicates, boolean fails) { this.advertisesSeal = advertisesSeal; this.duplicates = duplicates; this.fails = fails; }
        public AdapterDescriptor descriptor() {
            Set<String> capabilities = advertisesSeal ? Set.of("atomic-no-effect-seal") : Set.of();
            return new AdapterDescriptor(AdapterDescriptor.SCHEMA_VERSION, "fixture-independent", "1", Set.of("0.1.0-draft"), Set.of(PROFILE), capabilities,
                    List.of(new AdapterCombination("synthetic-card", "synthetic-reference", Set.of("none"), Set.of("synthetic-charge"), capabilities, List.of(new CurrencyLimit("EUR", 2, "1", "999999")))));
        }
        public ChargeOperation operation(String name, String scenario) {
            UUID operation = UUID.randomUUID();
            return new ChargeOperation(new OperationIdentity(new ExecutionScope("fixture-tenant", UUID.randomUUID(), "merchant-demo"), UUID.randomUUID(), UUID.randomUUID(), operation, operation, "sha256:" + "a".repeat(64)), new Money("EUR", 1250), scenario);
        }
        public TckExecutionDriver execution() { return this; }
        public Optional<AtomicNoEffectSealer> noEffectSealer() { return Optional.empty(); }
        public Preparation prepare(ChargeOperation operation) { return new Preparation(ActionType.NONE); }
        public Fact execute(ChargeOperation operation) {
            if (fails) throw new IllegalStateException("CANARY_PRIVATE_PROVIDER_PAYLOAD");
            UUID key = operation.identity().adapterKey();
            var prior = requests.get(key);
            if (prior != null && !prior.equals(operation)) throw new IllegalArgumentException("Binding mismatch");
            if (prior == null) {
                requests.put(key, operation); charges++;
                facts.put(key, new Fact(UUID.randomUUID(), operation.identity(), operation.money(), 1, Outcome.SUCCEEDED));
            } else if (duplicates) charges++;
            return facts.get(key);
        }
        public LookupResult lookup(OperationIdentity identity) { return facts.containsKey(identity.adapterKey()) ? new LookupResult(LookupResult.Status.FOUND, Optional.of(facts.get(identity.adapterKey()))) : new LookupResult(LookupResult.Status.NOT_FOUND, Optional.empty()); }
        public TckObservation inspect() { return new TckObservation(Math.max(requests.size(), charges), charges, true, List.copyOf(facts.values())); }
    }
}
