package io.agenticpayments.ap2;

/** Fixed protocol errors; neither causes nor untrusted artifact text are exposed. */
public final class Ap2Exception extends IllegalArgumentException {
    public enum Code {
        INVALID_ENCODING, INVALID_JSON, INVALID_HEADER, INVALID_KEY, INVALID_SIGNATURE,
        INVALID_CLAIMS, INVALID_CONTEXT, EXPIRED_AUTHORITY, UNSUPPORTED_PROFILE,
        BINDING_MISMATCH, REPLAY_REJECTED, INVALID_RECEIPT, UNTRUSTED_KEY
    }
    private final Code code;
    public Ap2Exception(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
}
