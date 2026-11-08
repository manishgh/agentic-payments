package io.agenticpayments.sdk.execution.v1;

import java.time.Instant;
import java.util.*;

/** Safe facts from configured trusted ports. Constructing this value does not verify provenance. */
public record ExecutionEvidence(ExecutionOperation operation, String evidenceId, Optional<String> providerResourceRef,
                                Source source, Instant observedAt, State state, Optional<TrustedActionDescriptor> action) {
    public enum Source { SUBMIT, RECONCILIATION, VERIFIED_EVENT, FENCE }
    public enum State { UNKNOWN, PENDING, ACTION_REQUIRED, AUTHORIZED, CAPTURED, DEFINITIVELY_REFUSED, NO_EFFECT_FENCED }
    public ExecutionEvidence {
        Objects.requireNonNull(operation); evidenceId = ContractValues.token(evidenceId);
        Objects.requireNonNull(providerResourceRef); providerResourceRef = providerResourceRef.map(ContractValues::token);
        Objects.requireNonNull(source); Objects.requireNonNull(observedAt); Objects.requireNonNull(state); Objects.requireNonNull(action);
        if ((state == State.ACTION_REQUIRED) != action.isPresent()
                || (state == State.AUTHORIZED && operation.purpose() != OperationPurpose.AUTHORIZE)
                || (state == State.CAPTURED && operation.purpose() != OperationPurpose.CHARGE)
                || (state == State.NO_EFFECT_FENCED && !operation.selection().combination().capabilities().contains(ExecutionOffer.Capability.ATOMIC_NO_EFFECT_FENCE))
                || (source == Source.FENCE && state != State.NO_EFFECT_FENCED && state != State.UNKNOWN
                    && state != State.PENDING && state != State.CAPTURED && state != State.AUTHORIZED && state != State.DEFINITIVELY_REFUSED)
                || action.stream().anyMatch(a -> !a.operationFingerprint().equals(operation.fingerprint())
                    || !a.bindingDigest().equals(operation.binding().digest()) || !operation.selection().combination().actions().contains(a.type())))
            throw new IllegalArgumentException("Invalid execution evidence binding.");
    }
    public boolean terminalForSubmission() { return state == State.CAPTURED || state == State.AUTHORIZED
            || state == State.DEFINITIVELY_REFUSED || state == State.NO_EFFECT_FENCED; }
    @Override public String toString() { return "ExecutionEvidence[" + state + "]"; }
}
