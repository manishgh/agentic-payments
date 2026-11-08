package io.agenticpayments.sdk.execution.v1;

/** Not a bearer proof: a normalizer must resolve provenance through its trusted event store. */
public record VerifiedEventReference(String verifiedEventRef, String eventId, String operationFingerprint, String bindingDigest) {
    public VerifiedEventReference {
        verifiedEventRef = ContractValues.token(verifiedEventRef); eventId = ContractValues.token(eventId);
        operationFingerprint = ContractValues.digest(operationFingerprint); bindingDigest = ContractValues.digest(bindingDigest);
    }
    public boolean boundTo(ExecutionOperation operation) { return operation.fingerprint().equals(operationFingerprint)
            && operation.binding().digest().equals(bindingDigest); }
    @Override public String toString() { return "VerifiedEventReference[redacted]"; }
}
