package io.agenticpayments.application;

import io.agenticpayments.application.PaymentService.*;
import java.time.Instant;
import java.util.*;

/** One run-locked atomic owner; these operations cannot be independently committed or registered. */
public interface PaymentCommandTransactions {
    /** Pure historic request encoding, performed before locking; no storage or business effects. */
    String requestDigest(ExecuteCommand command);
    /** May retry the work on serialization failure. No external effects are permitted in work. */
    Result run(Session session,Work work);
    @FunctionalInterface interface Work { Result run(Transaction transaction) throws Exception; }

    /** Synchronous transaction scope. Every operation must reject use after work returns. */
    interface Transaction {
        Instant now();
        ExecutionIntent intent(UUID id) throws Exception;
        ExecutionAttempt attempt(UUID intentId) throws Exception;
        ExecutionCheckout checkout(ExecutionIntent intent) throws Exception;
        ExecutionGrant grant(String id) throws Exception;
        Optional<AcceptedCommand> accepted(CommandIdentity identity) throws Exception;
        /** Decode only after the application has compared the historic request digest. */
        Result replay(AcceptedCommand accepted) throws Exception;
        void verifyNative(ExecutionIntent intent,ExecutionAttempt attempt,ExecutionGrant grant) throws Exception;
        void prepare(PreparationWrite write) throws Exception;
        void accept(CommandIdentity identity,String requestDigest,Result result) throws Exception;
    }

    record ExecutionIntent(UUID id,String merchantId,String profile,String state,long version,int checkoutRevision,String scenarioId) {
        @Override public String toString(){return "ExecutionIntent[redacted]";}
    }
    record ExecutionAttempt(UUID id,UUID intentId,int checkoutRevision,String grantId,long grantVersion,String subjectId) {
        @Override public String toString(){return "ExecutionAttempt[redacted]";}
    }
    record ExecutionCheckout(String digest,String currency,long minorUnits,String credentialReference) {
        @Override public String toString(){return "ExecutionCheckout[redacted]";}
    }
    record ExecutionGrant(String id,String subjectId,String merchantId,String state,long version,Instant validFrom,Instant expiresAt) {
        @Override public String toString(){return "ExecutionGrant[redacted]";}
    }
    /** Tenant/run/principal are fixed by the transaction, never by these journal values. */
    record CommandIdentity(String route,String resource,String key) {
        @Override public String toString(){return "CommandIdentity[redacted]";}
    }
    record AcceptedCommand(String requestDigest) {
        @Override public String toString(){return "AcceptedCommand[redacted]";}
    }
    /** Schedules one preparation; cannot admit, change arbitrary state, release, or charge. */
    record PreparationWrite(UUID intentId,long expectedVersion,UUID attemptId,UUID operationId,String requestDigest) {
        @Override public String toString(){return "PreparationWrite[redacted]";}
    }
}
