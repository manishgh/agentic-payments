package io.agenticpayments.sdk.execution.v1;

import java.util.Objects;

/** Raw bytes/headers remain in trusted infrastructure behind this opaque envelope reference. */
public record EventVerificationRequest(ExecutionOperation operation, String eventEnvelopeRef) {
    public EventVerificationRequest { Objects.requireNonNull(operation); eventEnvelopeRef = ContractValues.token(eventEnvelopeRef); }
    @Override public String toString() { return "EventVerificationRequest[redacted]"; }
}
