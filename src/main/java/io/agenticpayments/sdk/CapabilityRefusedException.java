package io.agenticpayments.sdk;

/** Safe structured refusal; caller-supplied capability data is never copied to its message. */
public final class CapabilityRefusedException extends RuntimeException {
    public enum Code { UNSUPPORTED_PROFILE, UNSUPPORTED_CAPABILITY, UNSUPPORTED_TENDER }

    private final Code code;

    public CapabilityRefusedException(Code code) {
        super("The requested capability selection is unsupported.");
        this.code = java.util.Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
