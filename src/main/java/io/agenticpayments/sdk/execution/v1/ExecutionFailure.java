package io.agenticpayments.sdk.execution.v1;

import java.util.Objects;

/** Sanitized boundary failure. NOT_STARTED applies only to this invocation, never all senders. */
public final class ExecutionFailure extends RuntimeException {
    public enum Code { UNSUPPORTED_CONTRACT, UNSUPPORTED_PROFILE, UNSUPPORTED_COMBINATION,
        INVALID_SELECTION, IDENTITY_CONFLICT, CONFIGURATION_UNAVAILABLE, PREPARATION_UNRESOLVED,
        TEMPORARILY_UNAVAILABLE, INVALID_EVENT, EVIDENCE_CONFLICT, CONTRACT_VIOLATION }
    public enum Certainty { NOT_STARTED, MAY_HAVE_OCCURRED }
    private final Code code;
    private final Certainty certainty;
    public ExecutionFailure(Code code, Certainty certainty) {
        super(Objects.requireNonNull(code).name(), null, false, false);
        this.code = code; this.certainty = Objects.requireNonNull(certainty);
    }
    public Code code() { return code; }
    public Certainty certainty() { return certainty; }
    @Override public String toString() { return "ExecutionFailure[" + code + "," + certainty + "]"; }
}
