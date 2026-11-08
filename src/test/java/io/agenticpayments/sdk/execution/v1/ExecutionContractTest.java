package io.agenticpayments.sdk.execution.v1;

import io.agenticpayments.sdk.execution.v1.tck.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionContractTest {
    @Test void exactMoneyRejectsImplicitOrNoncanonicalRepresentations() {
        assertEquals(new ExactMoney("JPY", 0, 1250), ExactMoney.parse("JPY", 0, "1250"));
        for (String invalid : List.of("0", "01", "+1", "1.00", "1e3", " 1", "-1", "1000000000000000000"))
            assertThrows(IllegalArgumentException.class, () -> ExactMoney.parse("EUR", 2, invalid));
        assertThrows(IllegalArgumentException.class, () -> new ExactMoney("eur", 2, 1));
        assertThrows(IllegalArgumentException.class, () -> new ExactMoney("EUR", 10, 1));
    }
    @Test void offerCanonicalizationIgnoresCollectionOrderingAndCoversEveryDeclaredLimit() {
        var offer = FixtureOperations.offer("synthetic-1", ExecutionOffer.PreparationEffect.EFFECT_FREE, Set.of());
        var c = offer.combinations().getFirst();
        var reversed = new ExecutionOffer.Combination(c.methodId(), c.credentialFormatId(), c.actions(), c.purposes(), c.preparationEffect(), c.capabilities(), c.limits().reversed());
        assertEquals(offer.digest(), new ExecutionOffer(offer.contractVersion(), offer.profileVersion(), offer.providerId(), offer.implementationRevision(), List.of(reversed)).digest());
        var changed = new ExecutionOffer.Combination(c.methodId(), c.credentialFormatId(), c.actions(), c.purposes(), c.preparationEffect(), c.capabilities(), List.of(new ExecutionOffer.CurrencyLimit("EUR", 2, 1, 999998)));
        assertNotEquals(c.digest(), changed.digest());
        assertThrows(IllegalArgumentException.class, () -> new ExecutionOffer.Combination(c.methodId(), c.credentialFormatId(), c.actions(), c.purposes(), c.preparationEffect(), c.capabilities(), List.of(c.limits().getFirst(), c.limits().getFirst())));
        assertThrows(ExecutionFailure.class, () -> new ExecutionOffer("future", "profile", "provider", "1", List.of(c)));
    }
    @Test void operationFingerprintCannotBeSuppliedOrConfusedByDiagnosticText() {
        var fixture = new ListPeerFixture(); var op = fixture.operation("primary");
        assertThrows(IllegalArgumentException.class, () -> new ExecutionOperation(op.identity(), op.binding(), op.selection(), op.money(), op.purpose(), "sha256:" + "0".repeat(64)));
        var changed = ExecutionOperation.create(op.identity(), op.binding(), op.selection(), new ExactMoney("EUR", 2, 1251), op.purpose());
        assertNotEquals(op.fingerprint(), changed.fingerprint());
        assertEquals(op.identity().key(), changed.identity().key());
        var scoped = new ExecutionIdentity("other-tenant", op.identity().merchantId(), op.identity().runId(), op.identity().intentId(), op.identity().attemptId(), op.identity().operationId(), op.identity().callerIdempotencyKey());
        assertNotEquals(op.identity().key(), scoped.key());
        assertFalse(op.toString().contains("credential-ref")); assertFalse(op.binding().toString().contains("account-ref"));
    }
    @Test void actionAndPreparationCrossFieldInvariantsAreExecutable() {
        var f = new MapPeerFixture(); var op = f.operation("primary"); var action = FixtureOperations.action(op);
        assertTrue(action.usableFor(op, f.now())); assertFalse(action.usableFor(op, action.expiresAt()));
        assertFalse(action.usableFor(f.operation("another"), f.now()));
        assertThrows(IllegalArgumentException.class, () -> new PreparationResult(op, PreparationResult.Status.READY, ExecutionOffer.PreparationEffect.RESOURCE_CREATING, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new PreparationResult(op, PreparationResult.Status.UNKNOWN, ExecutionOffer.PreparationEffect.RESOURCE_CREATING, Optional.empty(), Optional.of(action)));
        assertThrows(IllegalArgumentException.class, () -> new TrustedActionDescriptor(action.type(), "https://unsafe.example", op.fingerprint(), op.binding().digest(), Instant.MAX));
        assertFalse(action.toString().contains(action.actionRef()));
    }
    @Test void authorizationCaptureAndActionAreDistinctFacts() {
        var f = new ListPeerFixture(); var op = f.operation("primary");
        assertThrows(IllegalArgumentException.class, () -> FixtureOperations.evidence(op, "id", ExecutionEvidence.Source.SUBMIT, ExecutionEvidence.State.AUTHORIZED));
        var authorization = ExecutionOperation.create(op.identity(), op.binding(), op.selection(), op.money(), OperationPurpose.AUTHORIZE);
        assertThrows(IllegalArgumentException.class, () -> FixtureOperations.evidence(authorization, "id", ExecutionEvidence.Source.SUBMIT, ExecutionEvidence.State.CAPTURED));
        var authorized = f.ports().submitter().submit(new SubmissionRequest(authorization, Optional.empty()));
        assertEquals(ExecutionEvidence.State.AUTHORIZED, authorized.state());
        assertThrows(IllegalArgumentException.class, () -> new ExecutionEvidence(op, "id", Optional.empty(), ExecutionEvidence.Source.SUBMIT, f.now(), ExecutionEvidence.State.ACTION_REQUIRED, Optional.empty()));
    }
    @Test void fullIndependentMapFixturePassesPinnedCases() {
        var report = new ExecutionTck().run(id -> new MapPeerFixture());
        assertTrue(report.requiredCasesPassed(), report.results().toString());
        assertEquals(ExecutionTckReport.Status.PASS, status(report, "PE-EVENT"));
        assertEquals(ExecutionTckReport.Status.UNSUPPORTED, status(report, "PE-FENCE"));
        assertEquals(ExecutionTckFixture.RestartModel.IN_PROCESS_RECONSTRUCTION, report.restartModel());
    }
    @Test void independentlyAuthoredListFixturePassesWithHonestOptionalExclusions() {
        var report = new ExecutionTck().run(id -> new ListPeerFixture());
        assertTrue(report.requiredCasesPassed(), report.results().toString());
        assertEquals(ExecutionTckReport.Status.UNSUPPORTED, status(report, "PE-EVENT"));
        assertEquals(ExecutionTck.CASES, report.results().stream().map(ExecutionTckReport.CaseResult::caseId).toList());
    }
    @Test void authorizeOnlyNonFirstSelectionUsesItsOwnTupleAndFinancialMeaning() {
        var original = FixtureOperations.offer("synthetic-authorize-1", ExecutionOffer.PreparationEffect.EFFECT_FREE, Set.of());
        var c = original.combinations().getFirst();
        var first = new ExecutionOffer.Combination("first-method", c.credentialFormatId(), Set.of(), Set.of(OperationPurpose.CHARGE), ExecutionOffer.PreparationEffect.RESOURCE_CREATING, Set.of(), c.limits());
        var authorize = new ExecutionOffer.Combination(c.methodId(), c.credentialFormatId(), c.actions(), Set.of(OperationPurpose.AUTHORIZE), c.preparationEffect(), c.capabilities(), c.limits());
        var offer = new ExecutionOffer(original.contractVersion(), original.profileVersion(), original.providerId(), original.implementationRevision(), List.of(first, authorize));
        var report = new ExecutionTck().run(id -> new ListPeerFixture(offer, 1, OperationPurpose.AUTHORIZE));
        assertTrue(report.requiredCasesPassed(), report.results().toString());
        assertEquals(authorize.digest(), report.selectedCombinationDigest());
        var only = new ExecutionOffer(original.contractVersion(), original.profileVersion(), original.providerId(), original.implementationRevision(), List.of(authorize));
        var single = new ExecutionTck().run(id -> new ListPeerFixture(only, 0, OperationPurpose.AUTHORIZE));
        assertTrue(single.requiredCasesPassed(), single.results().toString());
    }
    @Test void localFakeCompatibilityPassesWithoutWideningOriginalSdk() {
        var report = new ExecutionTck().run(id -> new CompatibilityFixture());
        assertTrue(report.requiredCasesPassed(), report.results().toString());
        assertEquals(ExecutionTckReport.Status.PASS, status(report, "PE-FENCE"));
        assertEquals(ExecutionTckReport.Status.UNSUPPORTED, status(report, "PE-EVENT"));
        assertEquals(io.agenticpayments.sdk.CapabilityOffer.PROFILE, io.agenticpayments.sdk.CapabilityOffer.localFake().profile());
    }
    @Test void compatibilityObservationsPreserveDistinctEvidenceSourceIdentities() {
        var f = new CompatibilityFixture(); var op = f.operation("pending");
        f.control(ExecutionTckFixture.Control.PENDING, op); f.ports().preparer().prepare(op);
        var submitted = f.ports().submitter().submit(new SubmissionRequest(op, Optional.empty()));
        var reconciled = f.ports().reconciler().reconcile(op);
        assertEquals(ExecutionEvidence.State.PENDING, submitted.state());
        assertEquals(ExecutionEvidence.Source.SUBMIT, submitted.source());
        assertEquals(ExecutionEvidence.Source.RECONCILIATION, reconciled.source());
        assertNotEquals(submitted.evidenceId(), reconciled.evidenceId());
        assertEquals(reconciled, f.ports().reconciler().reconcile(op));
    }
    @Test void delayedSenderCannotPassFencingMerelyBecausePeerRefusesItsForbiddenSend() {
        var report = new ExecutionTck().run(id -> new CompatibilityFixture(true));
        assertFalse(report.requiredCasesPassed());
        assertEquals(ExecutionTckReport.Status.FAIL, status(report, "PE-FENCE"));
    }
    @Test void lostPreparationCannotRecoverUsingAnotherOperationsExistingResource() {
        var report = new ExecutionTck().run(id -> {
            var independent = new MapPeerFixture();
            return new ForwardingFixture(independent) {
                @Override public Ports ports() {
                    var p = super.ports();
                    return new Ports(op -> {
                        var result = p.preparer().prepare(op);
                        if (op.identity().operationId().equals("operation-lost") && result.status() == PreparationResult.Status.READY)
                            return new PreparationResult(op, result.status(), result.effect(), independent.observedPreparedResource(operation("primary")), Optional.empty());
                        return result;
                    }, p.submitter(), p.reconciler(), p.verifier(), p.normalizer(), p.fencer());
                }
            };
        });
        assertFalse(report.requiredCasesPassed());
        assertEquals(ExecutionTckReport.Status.FAIL, status(report, "PE-PREPARE"));
    }
    @Test void forwardingDecoratorCanReplaceDirectImplementationWithoutWideningIt() {
        var report = new ExecutionTck().run(id -> new ForwardingFixture(new MapPeerFixture()));
        assertTrue(report.requiredCasesPassed(), report.results().toString());
    }
    @Test void duplicateEffectDecoratorFailsIndependentObservation() {
        var report = new ExecutionTck().run(id -> new ForwardingFixture(new ListPeerFixture()) {
            @Override public Ports ports() {
                var p = super.ports();
                return new Ports(p.preparer(), request -> {
                    var result = p.submitter().submit(request);
                    var extra = operation("hidden-replacement"); p.preparer().prepare(extra);
                    p.submitter().submit(new SubmissionRequest(extra, Optional.empty())); return result;
                }, p.reconciler(), p.verifier(), p.normalizer(), p.fencer());
            }
        });
        assertFalse(report.requiredCasesPassed()); assertEquals(ExecutionTckReport.Status.FAIL, status(report, "PE-IDENTITY"));
    }
    @Test void unavailableControlsAndRawExceptionLeaksCannotBecomePassingEvidence() {
        var report = new ExecutionTck().run(id -> new ForwardingFixture(new ListPeerFixture()) {
            @Override public void control(Control c, ExecutionOperation op) { throw new IllegalStateException("CANARY_PRIVATE_FIXTURE"); }
        });
        assertFalse(report.requiredCasesPassed()); assertEquals(ExecutionTckReport.Status.INCOMPLETE, status(report, "PE-UNCERTAINTY"));
        assertFalse(report.toString().contains("CANARY_PRIVATE"));
        var unsafe = new ExecutionTck().run(id -> new ForwardingFixture(new ListPeerFixture()) {
            @Override public Ports ports() {
                var p = super.ports(); return new Ports(p.preparer(), request -> { throw new IllegalStateException("CANARY_PRIVATE_HEADER"); }, p.reconciler(), p.verifier(), p.normalizer(), p.fencer());
            }
        });
        assertFalse(unsafe.requiredCasesPassed()); assertFalse(unsafe.toString().contains("CANARY_PRIVATE"));
    }
    @Test void fixtureCloseFailureRevokesPassingCaseAndReportsOnlySafeText() {
        var report = new ExecutionTck().run(id -> new ForwardingFixture(new ListPeerFixture()) {
            @Override public void close() { throw new IllegalStateException("CANARY_PRIVATE_CLOSE"); }
        });
        assertFalse(report.requiredCasesPassed()); assertTrue(report.results().stream().allMatch(r -> r.status() == ExecutionTckReport.Status.INCOMPLETE));
        assertFalse(report.toString().contains("CANARY_PRIVATE"));
    }
    @Test void notStartedFailureCannotBeTreatedAsOperationWideNoEffect() {
        var safe = new ExecutionFailure(ExecutionFailure.Code.IDENTITY_CONFLICT, ExecutionFailure.Certainty.NOT_STARTED);
        assertNull(safe.getCause()); assertEquals("IDENTITY_CONFLICT", safe.getMessage());
        assertEquals(0, safe.getStackTrace().length);
        assertFalse(ExecutionEvidence.State.UNKNOWN == ExecutionEvidence.State.NO_EFFECT_FENCED);
    }
    private static ExecutionTckReport.Status status(ExecutionTckReport report, String id) {
        return report.results().stream().filter(r -> r.caseId().equals(id)).findFirst().orElseThrow().status();
    }
    private static class ForwardingFixture implements ExecutionTckFixture {
        private final ExecutionTckFixture delegate;
        ForwardingFixture(ExecutionTckFixture delegate) { this.delegate = delegate; }
        public String fixtureId() { return delegate.fixtureId(); }
        public ExecutionOffer offer() { return delegate.offer(); }
        public ExecutionBinding binding() { return delegate.binding(); }
        public ExecutionOperation operation(String name) { return delegate.operation(name); }
        private Ports forward(Ports p) { return new Ports(p.preparer()::prepare, p.submitter()::submit, p.reconciler()::reconcile,
                p.verifier().map(v -> v::verify), p.normalizer().map(n -> n::normalize), p.fencer().map(f -> f::fence)); }
        public Ports ports() { return forward(delegate.ports()); }
        public Ports portsForAuthenticatedScope(String tenant, String merchant, String run) { return forward(delegate.portsForAuthenticatedScope(tenant, merchant, run)); }
        public void control(Control control, ExecutionOperation operation) { delegate.control(control, operation); }
        public void awaitFinancialSendPaused(ExecutionOperation operation) { delegate.awaitFinancialSendPaused(operation); }
        public void resumeFinancialSend(ExecutionOperation operation) { delegate.resumeFinancialSend(operation); }
        public Optional<String> observedPreparedResource(ExecutionOperation operation) { return delegate.observedPreparedResource(operation); }
        public EventVerificationRequest event(ExecutionOperation operation, EventKind kind) { return delegate.event(operation, kind); }
        public void restart() { delegate.restart(); }
        public RestartModel restartModel() { return delegate.restartModel(); }
        public Inspection inspect() { return delegate.inspect(); }
        public Instant now() { return delegate.now(); }
        public void close() { delegate.close(); }
    }
}
