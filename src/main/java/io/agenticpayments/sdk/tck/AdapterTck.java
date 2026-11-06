package io.agenticpayments.sdk.tck;

import io.agenticpayments.sdk.*;
import java.util.*;
import static io.agenticpayments.sdk.tck.TckStatus.*;

/** Reusable adapter contract checks. A passing plan is not a whole-platform certification. */
public final class AdapterTck {
    private static final String SEAL = "atomic-no-effect-seal";
    private static final String LOCAL = CapabilityOffer.PROFILE;
    public TckReport run(AdapterTckFactory factory, TckPlan plan) {
        Objects.requireNonNull(factory); Objects.requireNonNull(plan);
        List<TckCaseResult> results = new ArrayList<>();
        Set<String> declaredCapabilities = new TreeSet<>();
        Set<String> evaluatedCapabilities = new HashSet<>();
        String descriptorDigest = null;
        for (String caseId : plan.caseIds()) {
            int priorResultCount = results.size();
            TckCaseContext context = new TckCaseContext(caseId, UUID.randomUUID().toString(), plan.seed(), plan.initialClock());
            try (AdapterTckFixture fixture = factory.open(context)) {
                var descriptor = fixture.descriptor();
                declaredCapabilities.addAll(descriptor.capabilities());
                String currentDigest = descriptor.digest();
                if (descriptorDigest == null) descriptorDigest = currentDigest;
                require(descriptorDigest.equals(currentDigest));
                if (!descriptor.profiles().contains(plan.selectedProfile()) || !descriptor.capabilities().containsAll(plan.requiredCapabilities())) {
                    results.add(result(caseId, UNSUPPORTED, "Selected profile or required capability is not declared.")); continue;
                }
                if (plan.selectedProfile().equals(LOCAL) && (!plan.requiredCaseIds().containsAll(TckPlan.CASES)
                        || !descriptor.capabilities().contains(SEAL) || !descriptor.capabilities().containsAll(CapabilityOffer.CATALOG))) {
                    results.add(result(caseId, INCOMPLETE, "The local profile requires every baseline case and atomic sealing.")); continue;
                }
                if (!TckPlan.CASES.contains(caseId)) {
                    results.add(result(caseId, INCOMPLETE, "This case has no executable implementation in the pinned suite.")); continue;
                }
                if (caseId.equals("AT-SEAL")) {
                    if (!descriptor.capabilities().contains(SEAL)) {
                        results.add(result(caseId, UNSUPPORTED, "Atomic absence sealing is not advertised; lookup absence remains unresolved.")); continue;
                    }
                    if (fixture.noEffectSealer().isEmpty()) {
                        results.add(result(caseId, INCOMPLETE, "Atomic sealing is advertised but no test extension is supplied.")); continue;
                    }
                }
                TckObservation before = fixture.inspect();
                require(before.immutableBindingsValid() && before.acceptedOperations() == 0 && before.successfulCharges() == 0 && before.facts().isEmpty());
                switch (caseId) {
                    case "AT-PREPARE" -> prepare(fixture);
                    case "AT-IDEMPOTENCY" -> idempotency(fixture);
                    case "AT-LOOKUP" -> lookup(fixture);
                    case "AT-SEAL" -> seal(fixture);
                    default -> throw new CheckFailed();
                }
                TckObservation observed = fixture.inspect();
                require(observed.immutableBindingsValid());
                results.add(new TckCaseResult(caseId, PASS, "Pinned adapter-only assertions passed.", Map.of(
                        "acceptedOperations", Long.toString(observed.acceptedOperations()), "successfulCharges", Long.toString(observed.successfulCharges()),
                        "immutableBindingsValid", "true", "fixtureNamespace", context.fixtureNamespace())));
            } catch (CheckFailed failed) {
                results.add(result(caseId, FAIL, "An adapter invariant failed; inspect isolated fixture evidence."));
            } catch (RuntimeException unavailable) {
                // Raw adapter exceptions may contain credentials or sensitive payloads.
                if (results.size() > priorResultCount) results.removeLast();
                results.add(result(caseId, INCOMPLETE, "The fixture could not produce complete safe evidence."));
            } finally {
                if (results.size() > priorResultCount) {
                    int index = results.size() - 1;
                    TckCaseResult result = results.get(index);
                    var assertions = new HashMap<>(result.safeAssertions());
                    assertions.put("fixtureNamespace", context.fixtureNamespace());
                    results.set(index, new TckCaseResult(result.caseId(), result.status(), result.reason(), assertions));
                }
            }
        }
        boolean passed = results.stream().noneMatch(r -> r.status() == FAIL || r.status() == INCOMPLETE)
                && plan.requiredCaseIds().stream().allMatch(id -> results.stream().anyMatch(r -> r.caseId().equals(id) && r.status() == PASS));
        List<String> exclusions = new ArrayList<>(List.of("Adapter-only synthetic test bridge; no AP2/ACP, provider, PCI, regulatory or network certification.",
                "Core authority, reservations, customer authentication and process recovery require separate composed evidence."));
        for (TckCaseResult result : results) {
            if (result.status() != PASS) continue;
            switch (result.caseId()) {
                case "AT-IDEMPOTENCY" -> evaluatedCapabilities.addAll(Set.of("idempotency", "idempotent-execution"));
                case "AT-LOOKUP" -> evaluatedCapabilities.add("lookup");
                case "AT-SEAL" -> evaluatedCapabilities.add(SEAL);
                case "AT-PREPARE" -> evaluatedCapabilities.add("effect-free-prepare");
                default -> { }
            }
        }
        results.stream().filter(r -> r.status() == UNSUPPORTED).forEach(r -> exclusions.add(r.caseId() + ": " + r.reason()));
        declaredCapabilities.stream().filter(capability -> !evaluatedCapabilities.contains(capability))
                .forEach(capability -> exclusions.add("Declared capability not fully evaluated by this adapter-only plan: " + capability));
        return new TckReport(TckPlan.VERSION, plan.selectedProfile(), descriptorDigest == null ? "unavailable" : descriptorDigest, results, passed, exclusions, plan.evidence());
    }
    private void prepare(AdapterTckFixture fixture) {
        var operation = fixture.operation("primary", "success");
        require(fixture.execution().prepare(operation).actionType() == ActionType.NONE);
        require(fixture.execution().prepare(operation).actionType() == ActionType.NONE);
        var observed = fixture.inspect();
        require(observed.acceptedOperations() == 0 && observed.successfulCharges() == 0 && observed.facts().isEmpty());
    }
    private void idempotency(AdapterTckFixture fixture) {
        var operation = fixture.operation("primary", "success");
        Fact original = fixture.execution().execute(operation);
        require(original.outcome() == Outcome.SUCCEEDED && original.identity().equals(operation.identity()) && original.money().equals(operation.money()));
        require(original.equals(fixture.execution().execute(operation)));
        var identity = operation.identity();
        String alternateDigest = identity.requestDigest().equals("sha256:" + "0".repeat(64)) ? "sha256:" + "1".repeat(64) : "sha256:" + "0".repeat(64);
        var changedIdentity = new OperationIdentity(identity.scope(), identity.intentId(), identity.attemptId(), identity.operationId(), identity.adapterKey(), alternateDigest);
        refused(() -> fixture.execution().execute(new ChargeOperation(changedIdentity, operation.money(), operation.scenarioId())));
        long differentAmount = operation.money().minorUnits() == 1 ? 2 : operation.money().minorUnits() - 1;
        refused(() -> fixture.execution().execute(new ChargeOperation(identity, new Money(operation.money().currency(), differentAmount), operation.scenarioId())));
        refused(() -> fixture.execution().execute(new ChargeOperation(identity, operation.money(), "definitive-no-effect")));
        require(original.equals(fixture.execution().execute(operation)));
        var observed = fixture.inspect();
        require(observed.acceptedOperations() == 1 && observed.successfulCharges() == 1 && observed.facts().equals(List.of(original)));
    }
    private void lookup(AdapterTckFixture fixture) {
        var unused = fixture.operation("unused", "success");
        var absent = fixture.execution().lookup(unused.identity());
        require(absent.status() != LookupResult.Status.FOUND && absent.fact().isEmpty());
        require(fixture.inspect().acceptedOperations() == 0 && fixture.inspect().facts().isEmpty());
        var accepted = fixture.operation("accepted", "success");
        var fact = fixture.execution().execute(accepted);
        var known = fixture.execution().lookup(accepted.identity());
        require(known.status() == LookupResult.Status.FOUND && known.fact().orElseThrow().equals(fact));
        require(fixture.inspect().acceptedOperations() == 1 && fixture.inspect().successfulCharges() == 1 && fixture.inspect().facts().contains(fact));
    }
    private void seal(AdapterTckFixture fixture) {
        var sealer = fixture.noEffectSealer().orElseThrow();
        var sealed = fixture.operation("sealed", "success");
        Fact absent = sealer.sealNoEffect(sealed);
        require(absent.outcome() == Outcome.NO_EFFECT && absent.identity().equals(sealed.identity()) && absent.money().equals(sealed.money()));
        require(absent.equals(fixture.execution().execute(sealed)));
        require(fixture.inspect().acceptedOperations() == 0 && fixture.inspect().successfulCharges() == 0);
        var accepted = fixture.operation("accepted", "success");
        Fact success = fixture.execution().execute(accepted);
        require(success.outcome() == Outcome.SUCCEEDED && success.equals(sealer.sealNoEffect(accepted)));
        require(fixture.inspect().acceptedOperations() == 1 && fixture.inspect().successfulCharges() == 1
                && fixture.inspect().facts().contains(absent) && fixture.inspect().facts().contains(success));
    }
    private static void refused(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expectedRefusal) { return; }
        throw new CheckFailed();
    }
    private static void require(boolean condition) { if (!condition) throw new CheckFailed(); }
    private static TckCaseResult result(String id, TckStatus status, String reason) { return new TckCaseResult(id, status, reason, Map.of()); }
    private static final class CheckFailed extends RuntimeException { }
}
