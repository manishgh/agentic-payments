package io.agenticpayments.sdk.execution.v1.compat;

import io.agenticpayments.sdk.ChargeOperation;
import io.agenticpayments.sdk.execution.v1.*;
import java.util.Optional;

/**
 * Atomic journal responsibility, not a supplied storage backend. Implementations must persist the
 * full operation/legacy mapping and resource association under identity.key(), reject conflicts,
 * and grant first dispatch ownership at most once across processes/restarts. Publication must be
 * durable before returning. Never reset an uncertain claim because a provider key expired.
 */
public interface CompatibilityBindingJournal {
    /** Atomically insert or return the identical complete mapping; a changed candidate conflicts. */
    ChargeOperation bind(ExecutionOperation operation, ChargeOperation candidate);
    /** Atomically bind the complete submission and claim its sole initial dispatch owner. */
    DispatchClaim claim(SubmissionRequest request);
    /** Atomically retain terminal evidence; conflicting terminal facts fail with EVIDENCE_CONFLICT. */
    ExecutionEvidence record(ExecutionEvidence evidence);
    Optional<ExecutionEvidence> read(ExecutionOperation operation);
    record DispatchClaim(boolean firstDispatchOwner, Optional<ExecutionEvidence> knownEvidence) {
        public DispatchClaim {
            java.util.Objects.requireNonNull(knownEvidence);
            if (firstDispatchOwner && knownEvidence.isPresent()) throw new IllegalArgumentException("Invalid dispatch claim.");
        }
    }
}
