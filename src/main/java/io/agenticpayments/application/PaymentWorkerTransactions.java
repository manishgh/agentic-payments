package io.agenticpayments.application;

import io.agenticpayments.application.PaymentService.Session;
import io.agenticpayments.sdk.*;
import java.time.Instant;
import java.util.*;

/** Atomic worker phases. Direct implementations, adapters and wrappers preserve one owner. */
public interface PaymentWorkerTransactions {
    String CONTRACT="payment-worker-transactions-1";
    /** May retry the callback; provider effects are forbidden. Return only after commit. */
    <T> T run(Session session,Work<T> work);
    @FunctionalInterface interface Work<T> { T run(Transaction transaction) throws Exception; }

    /** Synchronous, thread-confined scope, invalid after return; no nested owner entry. */
    interface Transaction {
        Instant now();
        Activity activity() throws Exception;
        void expireEligible() throws Exception;
        Optional<WorkItem> next() throws Exception;
        Snapshot load(UUID operationId) throws Exception;
        Snapshot findAdmitted(UUID intentId) throws Exception;
        Claim claim(WorkItem work) throws Exception;
        /** Manual observation supersedes earlier unfinished reconciliation, never revives it. */
        Claim reconciliationClaim(Snapshot snapshot) throws Exception;
        boolean isCurrent(Claim claim) throws Exception;
        boolean nativeGate(Snapshot snapshot) throws Exception;
        void terminateBeforeAdmission(Snapshot snapshot,Termination termination) throws Exception;
        void completeObsolete(WorkItem work) throws Exception;
        void admit(Claim claim,Snapshot snapshot) throws Exception;
        void completePreparation(Claim claim,Snapshot snapshot,ActionType action) throws Exception;
        default void completePreparationObservation(Claim claim,Snapshot snapshot,PreparationObservation observation) throws Exception {
            if(observation.providerAction().isPresent())throw new PaymentException(ApplicationFailure.UNSUPPORTED_CAPABILITY);
            completePreparation(claim,snapshot,observation.actionType());
        }
        /** Only current uncertainty may project ADMITTED to UNKNOWN; never regress known facts. */
        void markUnknown(Claim claim,Snapshot snapshot) throws Exception;
        /** Caller must use the configured verifier; stale counters do not invalidate evidence. */
        void applyVerifiedFact(ChargeOperation expected,Fact fact) throws Exception;
        /** Durable dispute, including after settlement; unavailable storage must not claim success. */
        default void quarantineProvider(ChargeOperation expected) throws Exception {
            throw new PaymentException(ApplicationFailure.UNSUPPORTED_CAPABILITY);
        }
        /** Caller must resolve durable dispute provenance first; check original admitted route, not current keys. */
        default void quarantineProviderOperation(io.agenticpayments.sdk.execution.v1.ExecutionOperation expected) throws Exception {
            throw new PaymentException(ApplicationFailure.UNSUPPORTED_CAPABILITY);
        }
        WorkerReceipt receipt(Activity before,Selection selection) throws Exception;
    }

    enum WorkKind { PREPARE, DISPATCH, RECONCILE }
    enum Termination { CANCELLED, EXPIRED, FAILED }
    record WorkItem(UUID operationId,WorkKind kind,long fence) {
        public WorkItem { Objects.requireNonNull(operationId);Objects.requireNonNull(kind); }
        @Override public String toString(){return "WorkItem[redacted]";}
    }
    record Claim(UUID operationId,WorkKind kind,long fence) {
        public Claim { Objects.requireNonNull(operationId);Objects.requireNonNull(kind); }
        @Override public String toString(){return "Claim[redacted]";}
    }
    record Selection(UUID operationId,WorkKind kind,long fence) {
        public static Selection idle(){return new Selection(null,null,0);}
        @Override public String toString(){return "Selection[redacted]";}
    }
    record Activity(long auditSequence,long completedWork,long evidence,long conflicts) {}
    record Grant(String state,long version,String subjectId,String merchantId,Instant validFrom,Instant expiresAt) {
        @Override public String toString(){return "Grant[redacted]";}
    }
    record Action(String state,String subjectId,String checkoutDigest,Instant expiresAt) {
        @Override public String toString(){return "Action[redacted]";}
    }
    record Snapshot(ChargeOperation operation,String profile,String state,long grantVersion,String subjectId,
                    String checkoutDigest,Grant grant,String reservationState,boolean budgetFrozen,boolean admitted,Action action) {
        @Override public String toString(){return "Snapshot[redacted]";}
    }
}
