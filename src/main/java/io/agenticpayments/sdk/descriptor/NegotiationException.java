package io.agenticpayments.sdk.descriptor;

public final class NegotiationException extends RuntimeException {
    public enum Code { UNKNOWN_DESCRIPTOR, UNSUPPORTED_VERSION, UNSUPPORTED_CAPABILITY, UNSUPPORTED_COMBINATION, AMBIGUOUS_COMBINATION, CONFIGURATION_MISMATCH, UNAVAILABLE, EXECUTION_DISABLED }
    private final Code code;
    public NegotiationException(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
}
