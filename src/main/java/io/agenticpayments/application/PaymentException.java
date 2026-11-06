package io.agenticpayments.application;

public final class PaymentException extends RuntimeException {
    private final int status;
    private final String code;
    private final String retry;
    public PaymentException(int status, String code) { this(status, code, "NEVER"); }
    public PaymentException(int status, String code, String retry) {
        super(code.replace('_', ' ').toLowerCase(java.util.Locale.ROOT));
        this.status=status; this.code=code; this.retry=retry;
    }
    public int status() { return status; }
    public String code() { return code; }
    public String retry() { return retry; }
}
