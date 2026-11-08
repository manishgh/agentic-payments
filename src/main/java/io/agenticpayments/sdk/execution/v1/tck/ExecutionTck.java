package io.agenticpayments.sdk.execution.v1.tck;

import io.agenticpayments.sdk.execution.v1.*;
import java.util.*;
import java.util.concurrent.*;
import static io.agenticpayments.sdk.execution.v1.tck.ExecutionTckReport.Status.*;
import static io.agenticpayments.sdk.execution.v1.tck.ExecutionTckFixture.Control.*;

/** Pinned executable adapter-only cases: fixtures cannot choose, omit or self-certify assertions. */
public final class ExecutionTck {
    public static final String VERSION = "provider-execution-tck-1";
    public static final List<String> CASES = List.of("PE-CAPABILITY", "PE-IDENTITY", "PE-PREPARE", "PE-UNCERTAINTY",
            "PE-RECONCILE", "PE-EVENT", "PE-ACTION", "PE-SAFE-FAILURE", "PE-FENCE");
    public ExecutionTckReport run(ExecutionTckFactory factory) {
        Objects.requireNonNull(factory);
        var results = new ArrayList<ExecutionTckReport.CaseResult>();
        String profile = "unavailable", digest = "unavailable", selectedDigest = "unavailable", fixtureId = "unavailable";
        var restartModel = ExecutionTckFixture.RestartModel.IN_PROCESS_RECONSTRUCTION;
        for (String id : CASES) {
            ExecutionTckReport.CaseResult result;
            try (var fixture = factory.open(id)) {
                var offer = fixture.offer();
                var selected = fixture.operation("suite-probe").selection(); selected.validate(offer, fixture.binding());
                if (digest.equals("unavailable")) {
                    profile = offer.profileVersion(); digest = offer.digest(); selectedDigest = selected.combination().digest(); fixtureId = fixture.fixtureId(); restartModel = fixture.restartModel();
                }
                check(digest.equals(offer.digest()) && selectedDigest.equals(selected.combination().digest()) && fixtureId.equals(fixture.fixtureId()) && restartModel == fixture.restartModel());
                var before = fixture.inspect(); check(before.bindingsValid() && before.financialAcceptances() == 0 && before.financialSends() == 0);
                boolean supported = execute(id, fixture);
                check(fixture.inspect().bindingsValid());
                result = new ExecutionTckReport.CaseResult(id, supported ? PASS : UNSUPPORTED,
                        supported ? "Pinned boundary assertions passed." : "Optional capability is explicitly undeclared.");
            } catch (CheckFailed failure) {
                result = new ExecutionTckReport.CaseResult(id, FAIL, "An independently observed boundary assertion failed.");
            } catch (RuntimeException unavailable) {
                result = new ExecutionTckReport.CaseResult(id, INCOMPLETE, "Fixture did not supply complete safe evidence.");
            }
            results.add(result);
        }
        boolean passed = results.size() == CASES.size() && results.stream().noneMatch(r -> r.status() == FAIL || r.status() == INCOMPLETE)
                && results.stream().filter(r -> !r.caseId().equals("PE-EVENT") && !r.caseId().equals("PE-FENCE")).allMatch(r -> r.status() == PASS);
        return new ExecutionTckReport(VERSION, ExecutionOffer.VERSION, profile, digest, selectedDigest, fixtureId, restartModel, results, passed,
                List.of("Adapter-only synthetic evidence; no provider/AP2/ACP/PCI/network certification.",
                        "Evidence covers only the exact selected combination; other declared combinations require separate suite runs.",
                        "No financial admission, application fact monotonicity, trusted UI completion or process-crash/storage durability claim.",
                        "Restart model is explicit; retained memory reconstruction is not durable process evidence."));
    }
    private boolean execute(String id, ExecutionTckFixture f) {
        switch (id) {
            case "PE-CAPABILITY" -> capability(f);
            case "PE-IDENTITY" -> identity(f);
            case "PE-PREPARE" -> prepare(f);
            case "PE-UNCERTAINTY" -> uncertainty(f);
            case "PE-RECONCILE" -> reconcile(f);
            case "PE-EVENT" -> { return events(f); }
            case "PE-ACTION" -> action(f);
            case "PE-SAFE-FAILURE" -> safeFailure(f);
            case "PE-FENCE" -> { return fence(f); }
            default -> throw new CheckFailed();
        }
        return true;
    }
    private CapabilitySelection.Requirement requirement(ExecutionTckFixture f, String contract, String profile, String method, String credential,
                                                       Set<ExecutionOffer.ActionType> actions, OperationPurpose purpose, ExactMoney money) {
        var offer = f.offer(); var c = f.operation("suite-probe").selection().combination(); var b = f.binding();
        return new CapabilitySelection.Requirement(contract, profile, offer.providerId(), offer.implementationRevision(), b.accountRef(),
                b.region(), method, credential, actions, purpose, c.preparationEffect(), c.capabilities(), money);
    }
    private void capability(ExecutionTckFixture f) {
        var op = f.operation("primary"); var c = op.selection().combination();
        check(CapabilitySelection.select(f.offer(), requirement(f, ExecutionOffer.VERSION, f.offer().profileVersion(), c.methodId(),
                c.credentialFormatId(), c.actions(), op.purpose(), op.money()), f.binding()).equals(op.selection()));
        refused(() -> CapabilitySelection.select(f.offer(), requirement(f, "unknown-1", f.offer().profileVersion(), c.methodId(), c.credentialFormatId(), c.actions(), op.purpose(), op.money()), f.binding()));
        refused(() -> CapabilitySelection.select(f.offer(), requirement(f, ExecutionOffer.VERSION, "unknown-profile", c.methodId(), c.credentialFormatId(), c.actions(), op.purpose(), op.money()), f.binding()));
        refused(() -> CapabilitySelection.select(f.offer(), requirement(f, ExecutionOffer.VERSION, f.offer().profileVersion(), "unknown-method", c.credentialFormatId(), c.actions(), op.purpose(), op.money()), f.binding()));
        refused(() -> CapabilitySelection.select(f.offer(), requirement(f, ExecutionOffer.VERSION, f.offer().profileVersion(), c.methodId(), "unknown-credential", c.actions(), op.purpose(), op.money()), f.binding()));
        var absentAction = Arrays.stream(ExecutionOffer.ActionType.values()).filter(a -> !c.actions().contains(a)).findFirst();
        if (absentAction.isPresent()) refused(() -> CapabilitySelection.select(f.offer(), requirement(f, ExecutionOffer.VERSION, f.offer().profileVersion(), c.methodId(), c.credentialFormatId(), Set.of(absentAction.orElseThrow()), op.purpose(), op.money()), f.binding()));
        var absentPurpose = Arrays.stream(OperationPurpose.values()).filter(p -> !c.purposes().contains(p)).findFirst();
        if (absentPurpose.isPresent()) refused(() -> CapabilitySelection.select(f.offer(), requirement(f, ExecutionOffer.VERSION, f.offer().profileVersion(), c.methodId(), c.credentialFormatId(), c.actions(), absentPurpose.orElseThrow(), op.money()), f.binding()));
        refused(() -> CapabilitySelection.select(f.offer(), requirement(f, ExecutionOffer.VERSION, f.offer().profileVersion(), c.methodId(), c.credentialFormatId(), c.actions(), op.purpose(), new ExactMoney("ZZZ", 9, 1)), f.binding()));
        check(f.inspect().financialSends() == 0 && f.inspect().nonfinancialResources() == 0);
    }
    private SubmissionRequest ready(ExecutionTckFixture f, ExecutionOperation op) {
        var p = f.ports().preparer().prepare(op);
        check(p.operation().equals(op) && p.status() == PreparationResult.Status.READY);
        return new SubmissionRequest(op, p.preparedResourceRef());
    }
    private void identity(ExecutionTckFixture f) {
        var op = f.operation("primary"); var request = ready(f, op); var original = f.ports().submitter().submit(request);
        check(original.terminalForSubmission() && original.operation().equals(op));
        check(original.equals(f.ports().submitter().submit(request)));
        var id = op.identity();
        List<ExecutionIdentity> ids = List.of(
                new ExecutionIdentity(id.tenantId(), id.merchantId(), id.runId(), "different-intent", id.attemptId(), id.operationId(), id.callerIdempotencyKey()),
                new ExecutionIdentity(id.tenantId(), id.merchantId(), id.runId(), id.intentId(), "different-attempt", id.operationId(), id.callerIdempotencyKey()),
                new ExecutionIdentity(id.tenantId(), id.merchantId(), id.runId(), id.intentId(), id.attemptId(), "different-operation", id.callerIdempotencyKey()),
                new ExecutionIdentity("other-tenant", id.merchantId(), id.runId(), id.intentId(), id.attemptId(), id.operationId(), id.callerIdempotencyKey()),
                new ExecutionIdentity(id.tenantId(), "other-merchant", id.runId(), id.intentId(), id.attemptId(), id.operationId(), id.callerIdempotencyKey()),
                new ExecutionIdentity(id.tenantId(), id.merchantId(), "other-run", id.intentId(), id.attemptId(), id.operationId(), id.callerIdempotencyKey()));
        for (var changed : ids) refused(() -> f.ports().submitter().submit(new SubmissionRequest(
                ExecutionOperation.create(changed, op.binding(), op.selection(), op.money(), op.purpose()), request.preparedResourceRef())));
        var b = op.binding();
        List<ExecutionBinding> bindings = List.of(
                new ExecutionBinding("other-provider", b.accountRef(), b.region(), b.configurationRef(), b.configurationVersion(), b.credentialRef(), b.credentialVersion(), b.executionOwnerId()),
                new ExecutionBinding(b.providerId(), "other-account", b.region(), b.configurationRef(), b.configurationVersion(), b.credentialRef(), b.credentialVersion(), b.executionOwnerId()),
                new ExecutionBinding(b.providerId(), b.accountRef(), "other-region", b.configurationRef(), b.configurationVersion(), b.credentialRef(), b.credentialVersion(), b.executionOwnerId()),
                new ExecutionBinding(b.providerId(), b.accountRef(), b.region(), "other-config", b.configurationVersion(), b.credentialRef(), b.credentialVersion(), b.executionOwnerId()),
                new ExecutionBinding(b.providerId(), b.accountRef(), b.region(), b.configurationRef(), "other-version", b.credentialRef(), b.credentialVersion(), b.executionOwnerId()),
                new ExecutionBinding(b.providerId(), b.accountRef(), b.region(), b.configurationRef(), b.configurationVersion(), "other-credential", b.credentialVersion(), b.executionOwnerId()),
                new ExecutionBinding(b.providerId(), b.accountRef(), b.region(), b.configurationRef(), b.configurationVersion(), b.credentialRef(), "other-version", b.executionOwnerId()),
                new ExecutionBinding(b.providerId(), b.accountRef(), b.region(), b.configurationRef(), b.configurationVersion(), b.credentialRef(), b.credentialVersion(), "other-owner"));
        for (var changed : bindings) {
            var s = op.selection(); var selection = new CapabilitySelection(s.contractVersion(), s.profileVersion(), s.implementationRevision(), s.offerDigest(), s.combination(), changed);
            refused(() -> f.ports().submitter().submit(new SubmissionRequest(ExecutionOperation.create(id, changed, selection, op.money(), op.purpose()), request.preparedResourceRef())));
        }
        refused(() -> f.ports().submitter().submit(new SubmissionRequest(ExecutionOperation.create(id, b, op.selection(),
                new ExactMoney(op.money().currency(), op.money().exponent(), op.money().minorUnits() + 1), op.purpose()), request.preparedResourceRef())));
        refused(() -> f.ports().submitter().submit(new SubmissionRequest(ExecutionOperation.create(id, b, op.selection(),
                new ExactMoney(op.money().currency(), op.money().exponent() + 1, op.money().minorUnits()), op.purpose()), request.preparedResourceRef())));
        String otherCurrency = op.money().currency().equals("USD") ? "EUR" : "USD";
        refused(() -> f.ports().submitter().submit(new SubmissionRequest(ExecutionOperation.create(id, b, op.selection(),
                new ExactMoney(otherCurrency, op.money().exponent(), op.money().minorUnits()), op.purpose()), request.preparedResourceRef())));
        refused(() -> f.ports().submitter().submit(new SubmissionRequest(ExecutionOperation.create(id, b, op.selection(), op.money(),
                op.purpose() == OperationPurpose.CHARGE ? OperationPurpose.AUTHORIZE : OperationPurpose.CHARGE), request.preparedResourceRef())));
        var s = op.selection();
        refused(() -> f.ports().submitter().submit(new SubmissionRequest(ExecutionOperation.create(id, b,
                new CapabilitySelection(s.contractVersion(), s.profileVersion(), "other-revision", s.offerDigest(), s.combination(), b), op.money(), op.purpose()), request.preparedResourceRef())));
        refused(() -> f.ports().submitter().submit(new SubmissionRequest(ExecutionOperation.create(id, b,
                new CapabilitySelection(s.contractVersion(), "other-profile", s.implementationRevision(), s.offerDigest(), s.combination(), b), op.money(), op.purpose()), request.preparedResourceRef())));
        String changedDigest = "sha256:" + (s.offerDigest().equals("sha256:" + "0".repeat(64)) ? "1" : "0").repeat(64);
        refused(() -> f.ports().submitter().submit(new SubmissionRequest(ExecutionOperation.create(id, b,
                new CapabilitySelection(s.contractVersion(), s.profileVersion(), s.implementationRevision(), changedDigest, s.combination(), b), op.money(), op.purpose()), request.preparedResourceRef())));
        refused(() -> f.ports().submitter().submit(new SubmissionRequest(op, request.preparedResourceRef().isPresent() ? Optional.empty() : Optional.of("other-resource"))));
        if (request.preparedResourceRef().isPresent()) {
            refused(() -> f.ports().submitter().submit(new SubmissionRequest(op, Optional.of("other-resource"))));
            var another = f.operation("another"); ready(f, another);
            refused(() -> f.ports().submitter().submit(new SubmissionRequest(another, request.preparedResourceRef())));
        }
        f.restart(); check(original.equals(f.ports().submitter().submit(request)));
        check(f.inspect().financialAcceptances() == 1 && f.inspect().financialSends() == 1);
        var scopedIdentity = new ExecutionIdentity("other-tenant", id.merchantId(), id.runId(), id.intentId(), id.attemptId(), id.operationId(), id.callerIdempotencyKey());
        var scopedOperation = ExecutionOperation.create(scopedIdentity, b, op.selection(), op.money(), op.purpose());
        var scopedPorts = f.portsForAuthenticatedScope(scopedIdentity.tenantId(), scopedIdentity.merchantId(), scopedIdentity.runId());
        var scopedPreparation = scopedPorts.preparer().prepare(scopedOperation);
        check(scopedPreparation.status() == PreparationResult.Status.READY);
        var scopedFact = scopedPorts.submitter().submit(new SubmissionRequest(scopedOperation, scopedPreparation.preparedResourceRef()));
        check(scopedFact.operation().equals(scopedOperation) && scopedFact.terminalForSubmission());
        check(f.inspect().financialAcceptances() == 2 && f.inspect().financialSends() == 2);
    }
    private void prepare(ExecutionTckFixture f) {
        var op = f.operation("primary"); var p = f.ports().preparer().prepare(op);
        check(p.operation().equals(op) && p.effect() == op.selection().combination().preparationEffect());
        check(p.equals(f.ports().preparer().prepare(op)) && f.inspect().financialAcceptances() == 0 && f.inspect().financialSends() == 0);
        boolean creates = p.effect() == ExecutionOffer.PreparationEffect.RESOURCE_CREATING;
        check(f.inspect().nonfinancialResources() == (creates ? 1 : 0));
        if (creates) {
            check(p.preparedResourceRef().equals(f.observedPreparedResource(op)) && p.preparedResourceRef().isPresent());
            var lost = f.operation("lost"); f.control(PREPARE_RESPONSE_LOSS, lost);
            var unknown = f.ports().preparer().prepare(lost);
            var originalResource = f.observedPreparedResource(lost); check(originalResource.isPresent());
            check(unknown.status() == PreparationResult.Status.UNKNOWN && unknown.operation().equals(lost)
                    && unknown.effect() == ExecutionOffer.PreparationEffect.RESOURCE_CREATING
                    && (unknown.preparedResourceRef().isEmpty() || unknown.preparedResourceRef().equals(originalResource)));
            f.restart(); var recovered = f.ports().preparer().prepare(lost);
            check((recovered.status() == PreparationResult.Status.READY || recovered.status() == PreparationResult.Status.UNKNOWN)
                    && recovered.operation().equals(lost) && recovered.effect() == ExecutionOffer.PreparationEffect.RESOURCE_CREATING
                    && (recovered.preparedResourceRef().isEmpty() || recovered.preparedResourceRef().equals(originalResource))
                    && f.observedPreparedResource(lost).equals(originalResource));
            check(f.inspect().nonfinancialResources() == 2 && f.inspect().financialSends() == 0);
        }
    }
    private void uncertainty(ExecutionTckFixture f) {
        var op = f.operation("primary"); var request = ready(f, op); f.control(SUBMIT_RESPONSE_LOSS, op);
        try { check(f.ports().submitter().submit(request).state() == ExecutionEvidence.State.UNKNOWN); }
        catch (ExecutionFailure failure) { check(failure.certainty() == ExecutionFailure.Certainty.MAY_HAVE_OCCURRED); }
        check(f.inspect().financialAcceptances() == 1 && f.inspect().providerIdempotencyKeys() == 1); f.restart();
        f.control(EXPIRE_PROVIDER_KEYS, op); check(f.inspect().providerIdempotencyKeys() == 0); f.control(LOOKUP_ABSENT, op);
        check(f.ports().reconciler().reconcile(op).state() == ExecutionEvidence.State.UNKNOWN);
        check(f.ports().submitter().submit(request).state() == ExecutionEvidence.State.UNKNOWN);
        f.control(LOOKUP_UNAVAILABLE, op);
        check(f.ports().reconciler().reconcile(op).state() == ExecutionEvidence.State.UNKNOWN);
        check(f.inspect().financialAcceptances() == 1 && f.inspect().financialSends() == 1);
    }
    private void reconcile(ExecutionTckFixture f) {
        var absent = f.operation("absent"); check(f.ports().reconciler().reconcile(absent).state() == ExecutionEvidence.State.UNKNOWN);
        check(f.inspect().financialSends() == 0);
        var pending = f.operation("pending"); f.control(PENDING, pending); var request = ready(f, pending);
        check(f.ports().submitter().submit(request).state() == ExecutionEvidence.State.PENDING);
        check(f.ports().reconciler().reconcile(pending).state() == ExecutionEvidence.State.PENDING);
        var refused = f.operation("refused"); f.control(REFUSED, refused); var refusedRequest = ready(f, refused);
        var fact = f.ports().submitter().submit(refusedRequest);
        check(fact.state() == ExecutionEvidence.State.DEFINITIVELY_REFUSED || fact.state() == ExecutionEvidence.State.NO_EFFECT_FENCED);
        long sends = f.inspect().financialSends(); check(fact.equals(f.ports().submitter().submit(refusedRequest)));
        check(f.inspect().financialSends() == sends);
        var success = f.operation("success"); var capture = f.ports().submitter().submit(ready(f, success));
        check(capture.state() == financialState(success) && capture.operation().equals(success));
        var otherPurpose = success.purpose() == OperationPurpose.CHARGE ? OperationPurpose.AUTHORIZE : OperationPurpose.CHARGE;
        if (success.selection().combination().purposes().contains(otherPurpose)) {
            var original = f.operation("authorization");
            var authorization = ExecutionOperation.create(original.identity(), original.binding(), original.selection(), original.money(), otherPurpose);
            var authorized = f.ports().submitter().submit(ready(f, authorization));
            check(authorized.state() == financialState(authorization) && authorized.operation().equals(authorization));
        }
    }
    private boolean events(ExecutionTckFixture f) {
        var op = f.operation("primary");
        if (!op.selection().combination().capabilities().contains(ExecutionOffer.Capability.VERIFIED_EVENTS)) {
            check(f.ports().verifier().isEmpty() && f.ports().normalizer().isEmpty()); return false;
        }
        var verifier = f.ports().verifier().orElseThrow(); var normalizer = f.ports().normalizer().orElseThrow();
        f.ports().submitter().submit(ready(f, op));
        var request = f.event(op, ExecutionTckFixture.EventKind.VALID); var verified = verifier.verify(request);
        var fact = normalizer.normalize(op, verified); check(fact.operation().equals(op) && fact.source() == ExecutionEvidence.Source.VERIFIED_EVENT
                && fact.state() == financialState(op));
        check(verified.equals(verifier.verify(request)) && fact.equals(normalizer.normalize(op, verified)));
        for (var kind : List.of(ExecutionTckFixture.EventKind.FORGED, ExecutionTckFixture.EventKind.WRONG_ACCOUNT,
                ExecutionTckFixture.EventKind.ALTERED, ExecutionTckFixture.EventKind.CONFLICTING_DUPLICATE, ExecutionTckFixture.EventKind.UNBOUND))
            refused(() -> normalizer.normalize(op, verifier.verify(f.event(op, kind))));
        refused(() -> normalizer.normalize(op, new VerifiedEventReference("fabricated-ref", verified.eventId(), op.fingerprint(), op.binding().digest())));
        var weak = normalizer.normalize(op, verifier.verify(f.event(op, ExecutionTckFixture.EventKind.WEAK_PENDING)));
        check(weak.state() == ExecutionEvidence.State.PENDING && fact.equals(normalizer.normalize(op, verified)));
        check(f.inspect().financialSends() == 1 && f.inspect().financialAcceptances() == 1); return true;
    }
    private void action(ExecutionTckFixture f) {
        var op = f.operation("primary"); var type = op.selection().combination().actions().stream().findFirst();
        if (type.isEmpty()) {
            refused(() -> new TrustedActionDescriptor(ExecutionOffer.ActionType.REDIRECT, "https://untrusted", op.fingerprint(), op.binding().digest(), f.now()));
            return;
        }
        f.control(ACTION_REQUIRED, op); var p = f.ports().preparer().prepare(op);
        check(p.status() == PreparationResult.Status.ACTION_REQUIRED); var a = p.action().orElseThrow();
        check(a.usableFor(op, f.now()) && !a.usableFor(op, a.expiresAt()));
        check(!a.usableFor(f.operation("another"), f.now()));
        var wrong = new TrustedActionDescriptor(a.type(), a.actionRef(), op.fingerprint(), "sha256:" + "0".repeat(64), a.expiresAt());
        check(!wrong.usableFor(op, f.now())); check(f.inspect().financialAcceptances() == 0 && f.inspect().financialSends() == 0);
    }
    private void safeFailure(ExecutionTckFixture f) {
        var op = f.operation("primary"); var request = ready(f, op); f.control(UNSAFE_EXCEPTION, op);
        try {
            var result = f.ports().submitter().submit(request); check(result.state() == ExecutionEvidence.State.UNKNOWN);
            check(!result.toString().contains("CANARY_PRIVATE"));
        } catch (ExecutionFailure safe) { check(!safe.toString().contains("CANARY_PRIVATE") && safe.getCause() == null); }
        // Other RuntimeExceptions deliberately escape into INCOMPLETE, with fixed report text.
    }
    private boolean fence(ExecutionTckFixture f) {
        var op = f.operation("sealed");
        if (!op.selection().combination().capabilities().contains(ExecutionOffer.Capability.ATOMIC_NO_EFFECT_FENCE)) {
            check(f.ports().fencer().isEmpty()); return false;
        }
        var fence = f.ports().fencer().orElseThrow(); var request = ready(f, op);
        f.control(PAUSE_BEFORE_FINANCIAL_SEND, op);
        var delayedWorker = Executors.newSingleThreadExecutor(task -> { var worker = new Thread(task, "execution-tck-delayed"); worker.setDaemon(true); return worker; });
        ExecutionEvidence fact;
        try {
            var delayed = delayedWorker.submit(() -> f.ports().submitter().submit(request));
            f.awaitFinancialSendPaused(op); check(f.inspect().financialSends() == 0);
            fact = fence.fence(op); check(fact.state() == ExecutionEvidence.State.NO_EFFECT_FENCED);
            f.resumeFinancialSend(op); check(fact.equals(delayed.get(5, TimeUnit.SECONDS)));
            check(f.inspect().financialSends() == 0 && f.inspect().financialAcceptances() == 0);
            f.restart(); check(fact.equals(f.ports().submitter().submit(request)));
            check(f.inspect().financialSends() == 0 && f.inspect().financialAcceptances() == 0);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Fixture delayed-send evidence unavailable.");
        } catch (ExecutionException | TimeoutException unavailable) {
            throw new IllegalStateException("Fixture delayed-send evidence unavailable.");
        } finally { try { f.resumeFinancialSend(op); } finally { delayedWorker.shutdownNow(); } }
        var accepted = f.operation("accepted"); var success = f.ports().submitter().submit(ready(f, accepted));
        check(success.terminalForSubmission() && fence.fence(accepted).state() != ExecutionEvidence.State.NO_EFFECT_FENCED);
        check(f.inspect().financialAcceptances() == 1 && f.inspect().financialSends() == 1);
        var racing = f.operation("racing"); var racingRequest = ready(f, racing); var active = f.ports();
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2, task -> { var worker = new Thread(task, "execution-tck-race"); worker.setDaemon(true); return worker; });
        try {
            var send = workers.submit(() -> { start.await(); return active.submitter().submit(racingRequest); });
            var seal = workers.submit(() -> { start.await(); return active.fencer().orElseThrow().fence(racing); });
            start.countDown();
            var sent = send.get(5, TimeUnit.SECONDS); var sealed = seal.get(5, TimeUnit.SECONDS);
            boolean fenceWon = sealed.state() == ExecutionEvidence.State.NO_EFFECT_FENCED;
            check(fenceWon ? sent.state() == ExecutionEvidence.State.NO_EFFECT_FENCED : sent.terminalForSubmission());
            check(f.inspect().financialAcceptances() == (fenceWon ? 1 : 2));
            check(f.inspect().financialSends() == (fenceWon ? 1 : 2));
            f.restart(); check(sent.equals(f.ports().submitter().submit(racingRequest)));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Fixture race evidence unavailable.");
        } catch (ExecutionException | TimeoutException unavailable) {
            throw new IllegalStateException("Fixture race evidence unavailable.");
        } finally { workers.shutdownNow(); }
        return true;
    }
    private static void refused(Runnable action) {
        try { action.run(); }
        catch (ExecutionFailure | IllegalArgumentException expected) { return; }
        throw new CheckFailed();
    }
    private static ExecutionEvidence.State financialState(ExecutionOperation operation) {
        return operation.purpose() == OperationPurpose.AUTHORIZE ? ExecutionEvidence.State.AUTHORIZED : ExecutionEvidence.State.CAPTURED;
    }
    private static void check(boolean valid) { if (!valid) throw new CheckFailed(); }
    private static final class CheckFailed extends RuntimeException { }
}
