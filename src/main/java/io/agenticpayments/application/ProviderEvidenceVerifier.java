package io.agenticpayments.application;
import io.agenticpayments.sdk.execution.v1.*;
/** Trusted durable provenance. Every observation must traverse the journal conflict rules before projection. */
public interface ProviderEvidenceVerifier {
    String CONTRACT="provider-evidence-verifier-1";
    /** Retain complete trusted-port evidence, check quarantine, and return that exact evidence. */
    ExecutionEvidence verify(ExecutionOperation expected,ExecutionEvidence received);
    /** Resolve previously retained authentic evidence of this state, checking quarantine even after terminal completion. */
    ExecutionEvidence retained(ExecutionOperation expected,ExecutionEvidence.State state);
}
