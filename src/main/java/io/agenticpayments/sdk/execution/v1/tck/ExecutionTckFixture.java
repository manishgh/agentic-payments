package io.agenticpayments.sdk.execution.v1.tck;

import io.agenticpayments.sdk.execution.v1.*;
import java.time.Instant;
import java.util.*;

/** Trusted synthetic controls/observations, independently inspected from the adapter under test. */
public interface ExecutionTckFixture extends AutoCloseable {
    enum Control { SUBMIT_RESPONSE_LOSS, LOOKUP_ABSENT, LOOKUP_UNAVAILABLE, EXPIRE_PROVIDER_KEYS,
        PREPARE_RESPONSE_LOSS, PENDING, REFUSED, ACTION_REQUIRED, UNSAFE_EXCEPTION, PAUSE_BEFORE_FINANCIAL_SEND }
    enum EventKind { VALID, FORGED, WRONG_ACCOUNT, ALTERED, CONFLICTING_DUPLICATE, UNBOUND, WEAK_PENDING }
    enum RestartModel { IN_PROCESS_RECONSTRUCTION, PROCESS_PERSISTENCE }
    record Ports(ExecutionPreparer preparer, FinancialSubmitter submitter, ExecutionReconciler reconciler,
                 Optional<ProviderEventVerifier> verifier, Optional<ProviderEventNormalizer> normalizer,
                 Optional<NoEffectFencer> fencer) {
        public Ports {
            Objects.requireNonNull(preparer); Objects.requireNonNull(submitter); Objects.requireNonNull(reconciler);
            Objects.requireNonNull(verifier); Objects.requireNonNull(normalizer); Objects.requireNonNull(fencer);
        }
    }
    record Inspection(long financialSends, long financialAcceptances, long nonfinancialResources,
                      long providerIdempotencyKeys, boolean bindingsValid) {
        public Inspection {
            if (financialSends < 0 || financialAcceptances < 0 || nonfinancialResources < 0 || providerIdempotencyKeys < 0)
                throw new IllegalArgumentException("Invalid observation.");
        }
    }
    String fixtureId();
    ExecutionOffer offer();
    ExecutionBinding binding();
    ExecutionOperation operation(String logicalName);
    Ports ports();
    /** Separate trusted composition over the same journal; never change scope from untrusted input. */
    Ports portsForAuthenticatedScope(String tenantId, String merchantId, String runId);
    /** Missing controls must fail safely; they cannot be treated as an implemented assertion. */
    void control(Control control, ExecutionOperation operation);
    /** Conditional fence evidence: await an admitted worker paused immediately before financial send. */
    default void awaitFinancialSendPaused(ExecutionOperation operation) { throw new UnsupportedOperationException("Delayed-send control unavailable."); }
    default void resumeFinancialSend(ExecutionOperation operation) { throw new UnsupportedOperationException("Delayed-send control unavailable."); }
    /** Independent peer observation, not the adapter's returned preparation result. */
    default Optional<String> observedPreparedResource(ExecutionOperation operation) { throw new UnsupportedOperationException("Resource association evidence unavailable."); }
    EventVerificationRequest event(ExecutionOperation operation, EventKind kind);
    /** Replace adapter objects, preserving independently observed journals/peer state. */
    void restart();
    RestartModel restartModel();
    Inspection inspect();
    Instant now();
    @Override default void close() { }
}
