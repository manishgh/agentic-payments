package io.agenticpayments.sdk.execution.v1.compat;

import io.agenticpayments.sdk.*;
import io.agenticpayments.sdk.execution.v1.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import static io.agenticpayments.sdk.execution.v1.ExecutionFailure.Code.*;
import static io.agenticpayments.sdk.execution.v1.ExecutionFailure.Certainty.*;

/** Explicit synthetic compatibility only. Existing core callers continue to use PaymentAdapter. */
public final class LocalFakeExecutionAdapter implements ExecutionPreparer, FinancialSubmitter, ExecutionReconciler, NoEffectFencer {
    public static final String PROFILE = "local-fake-execution-1";
    private final PaymentAdapter delegate;
    private final CompatibilityBindingJournal journal;
    private final ExecutionBinding binding;
    private final String tenant, merchant, run;
    private final Instant observedAt, actionExpiresAt;
    private final Function<ExecutionOperation, String> scenario;
    private final ExecutionOffer offer;

    public LocalFakeExecutionAdapter(PaymentAdapter delegate, CompatibilityBindingJournal journal, ExecutionBinding binding,
                                     String tenant, String merchant, String run, Instant observedAt, Instant actionExpiresAt,
                                     Function<ExecutionOperation, String> scenario) {
        this.delegate = Objects.requireNonNull(delegate); this.journal = Objects.requireNonNull(journal);
        this.binding = Objects.requireNonNull(binding); this.tenant = Objects.requireNonNull(tenant);
        this.merchant = Objects.requireNonNull(merchant); this.run = Objects.requireNonNull(run);
        this.observedAt = Objects.requireNonNull(observedAt); this.actionExpiresAt = Objects.requireNonNull(actionExpiresAt);
        this.scenario = Objects.requireNonNull(scenario);
        if (!"simulator".equals(binding.providerId()) || !observedAt.isBefore(actionExpiresAt))
            throw new IllegalArgumentException("Invalid synthetic compatibility configuration.");
        try {
            if (!CapabilityOffer.localFake().equals(delegate.capabilities()))
                throw new ExecutionFailure(UNSUPPORTED_COMBINATION, NOT_STARTED);
        } catch (ExecutionFailure safe) { throw safe; }
        catch (RuntimeException unsafe) { throw new ExecutionFailure(CONFIGURATION_UNAVAILABLE, NOT_STARTED); }
        this.offer = offer();
    }
    public static ExecutionOffer offer() {
        return new ExecutionOffer(ExecutionOffer.VERSION, PROFILE, "simulator", "1", List.of(new ExecutionOffer.Combination(
                "synthetic-card", "synthetic-reference", Set.of(ExecutionOffer.ActionType.SYNTHETIC_CHALLENGE), Set.of(OperationPurpose.CHARGE),
                ExecutionOffer.PreparationEffect.EFFECT_FREE, Set.of(ExecutionOffer.Capability.ATOMIC_NO_EFFECT_FENCE),
                List.of(new ExecutionOffer.CurrencyLimit("EUR", 2, 1, Money.MAX_MINOR_UNITS),
                        new ExecutionOffer.CurrencyLimit("USD", 2, 1, Money.MAX_MINOR_UNITS)))));
    }
    private ChargeOperation bind(ExecutionOperation op) {
        op.selection().validate(offer, binding);
        var id = op.identity();
        if (!tenant.equals(id.tenantId()) || !merchant.equals(id.merchantId()) || !run.equals(id.runId()))
            throw new ExecutionFailure(INVALID_SELECTION, NOT_STARTED);
        String selectedScenario;
        try { selectedScenario = scenario.apply(op); }
        catch (RuntimeException unsafe) { throw new ExecutionFailure(CONFIGURATION_UNAVAILABLE, NOT_STARTED); }
        if (!ChargeOperation.SCENARIOS.contains(selectedScenario)) throw new ExecutionFailure(UNSUPPORTED_COMBINATION, NOT_STARTED);
        var legacy = new ChargeOperation(new OperationIdentity(new ExecutionScope(tenant, uuid("run", run), merchant),
                uuid("intent", id.intentId()), uuid("attempt", id.attemptId()), uuid("operation", id.operationId()),
                uuid("key", String.join("\n", tenant, merchant, run, id.callerIdempotencyKey())), op.fingerprint()),
                new Money(op.money().currency(), op.money().minorUnits()), selectedScenario);
        try { return journal.bind(op, legacy); }
        catch (ExecutionFailure safe) { throw safe; }
        catch (RuntimeException unsafe) { throw new ExecutionFailure(CONFIGURATION_UNAVAILABLE, NOT_STARTED); }
    }
    private static UUID uuid(String tag, String value) {
        return UUID.nameUUIDFromBytes(("local-fake-execution-1\n" + tag + "\n" + value).getBytes(StandardCharsets.UTF_8));
    }
    @Override public PreparationResult prepare(ExecutionOperation op) {
        var legacy = bind(op);
        try {
            var prepared = delegate.prepare(legacy);
            Optional<TrustedActionDescriptor> action = prepared.actionType() == ActionType.NONE ? Optional.empty()
                    : Optional.of(new TrustedActionDescriptor(ExecutionOffer.ActionType.SYNTHETIC_CHALLENGE,
                        "synthetic-action-" + uuid("action", op.fingerprint()), op.fingerprint(), binding.digest(), actionExpiresAt));
            return new PreparationResult(op, action.isPresent() ? PreparationResult.Status.ACTION_REQUIRED : PreparationResult.Status.READY,
                    ExecutionOffer.PreparationEffect.EFFECT_FREE, Optional.empty(), action);
        } catch (RuntimeException unsafe) { throw new ExecutionFailure(TEMPORARILY_UNAVAILABLE, MAY_HAVE_OCCURRED); }
    }
    @Override public ExecutionEvidence submit(SubmissionRequest request) {
        var op = request.operation(); var legacy = bind(op);
        CompatibilityBindingJournal.DispatchClaim claim;
        try { claim = journal.claim(request); }
        catch (ExecutionFailure safe) { throw safe; }
        catch (RuntimeException unsafe) { throw new ExecutionFailure(CONFIGURATION_UNAVAILABLE, MAY_HAVE_OCCURRED); }
        if (claim.knownEvidence().isPresent() && claim.knownEvidence().orElseThrow().terminalForSubmission())
            return claim.knownEvidence().orElseThrow();
        if (!claim.firstDispatchOwner()) return reconcile(op);
        ExecutionEvidence result;
        try { result = mapped(op, delegate.execute(legacy), ExecutionEvidence.Source.SUBMIT); }
        catch (RuntimeException unsafe) { result = unknown(op); }
        return record(result);
    }
    @Override public ExecutionEvidence reconcile(ExecutionOperation op) {
        var legacy = bind(op);
        try {
            var known = journal.read(op);
            if (known.isPresent() && known.orElseThrow().terminalForSubmission()) return known.orElseThrow();
            var found = delegate.lookup(legacy.identity());
            return record(found.fact().map(f -> mapped(op, f, ExecutionEvidence.Source.RECONCILIATION)).orElseGet(() -> unknown(op)));
        } catch (ExecutionFailure safe) { throw safe; }
        catch (RuntimeException unsafe) { return record(unknown(op)); }
    }
    @Override public ExecutionEvidence fence(ExecutionOperation op) {
        var legacy = bind(op);
        try { return record(mapped(op, delegate.sealNoEffect(legacy), ExecutionEvidence.Source.FENCE)); }
        catch (ExecutionFailure safe) { throw safe; }
        catch (RuntimeException unsafe) { return record(unknown(op)); }
    }
    private ExecutionEvidence record(ExecutionEvidence evidence) {
        try { return journal.record(evidence); }
        catch (ExecutionFailure safe) { throw safe; }
        catch (RuntimeException unsafe) { throw new ExecutionFailure(CONFIGURATION_UNAVAILABLE, MAY_HAVE_OCCURRED); }
    }
    private ExecutionEvidence unknown(ExecutionOperation op) {
        return new ExecutionEvidence(op, "unknown-" + uuid("unknown", op.fingerprint() + "\n" + observedAt), Optional.empty(),
                ExecutionEvidence.Source.RECONCILIATION, observedAt, ExecutionEvidence.State.UNKNOWN, Optional.empty());
    }
    private ExecutionEvidence mapped(ExecutionOperation op, Fact fact, ExecutionEvidence.Source source) {
        var legacy = bind(op);
        if (!fact.identity().equals(legacy.identity()) || !fact.money().equals(legacy.money()))
            throw new ExecutionFailure(CONTRACT_VIOLATION, MAY_HAVE_OCCURRED);
        var state = switch (fact.outcome()) {
            case SUCCEEDED -> ExecutionEvidence.State.CAPTURED;
            case PENDING -> ExecutionEvidence.State.PENDING;
            case NO_EFFECT -> ExecutionEvidence.State.NO_EFFECT_FENCED;
        };
        // A failed fence may observe already accepted facts; preserve their original source semantics.
        var actualSource = source == ExecutionEvidence.Source.FENCE && state != ExecutionEvidence.State.NO_EFFECT_FENCED
                ? ExecutionEvidence.Source.RECONCILIATION : source;
        return new ExecutionEvidence(op, "legacy-" + uuid("evidence", fact.eventId() + "\n" + actualSource.name() + "\n" + observedAt), Optional.of("legacy-resource-" + fact.identity().adapterKey()),
                actualSource, observedAt, state, Optional.empty());
    }
}
