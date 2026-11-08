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

    /** Execute-only implementations do not implicitly support approval. */
    default String approvalRequestDigest(ApproveCommand command) { throw new PaymentException(ApplicationFailure.UNSUPPORTED_CAPABILITY); }
    /** Approval retries may repeat existing signing/immutable staging; payment effects are forbidden. */
    default Result runApproval(Session session,ApprovalWork work) { throw new PaymentException(ApplicationFailure.UNSUPPORTED_CAPABILITY); }
    @FunctionalInterface interface ApprovalWork { Result run(ApprovalTransaction transaction) throws Exception; }

    /** One approval view of the same atomic owner, with the same lifetime and nesting restrictions. */
    interface ApprovalTransaction {
        Instant now();
        ApprovalIntent intent(UUID id) throws Exception;
        ApprovalCheckout checkout(ApprovalIntent intent) throws Exception;
        ApprovalGrant grant(String id) throws Exception;
        ApprovalBudget budget(String id) throws Exception;
        Optional<AcceptedCommand> accepted(CommandIdentity identity) throws Exception;
        /** Decode only after comparing the historic request digest. */
        Result replay(AcceptedCommand accepted) throws Exception;
        void stageAttempt(ApprovalWrite write) throws Exception;
        /** Preserve provisional authority/challenges, exact reload and fresh trust-snapshot ordering. */
        void issueAndVerifyNative(ApprovalIntent intent,ApprovalCheckout checkout,ApprovalGrant grant,ApprovalWrite write) throws Exception;
        void reserveAndApprove(ApprovalWrite write) throws Exception;
        void accept(CommandIdentity identity,String requestDigest,Result result) throws Exception;
    }

    record ApprovalIntent(UUID id,String merchantId,String profile,String state,long version,int checkoutRevision) {
        @Override public String toString(){return "ApprovalIntent[redacted]";}
    }
    record ApprovalCheckout(int revision,String digest,String currency,long minorUnits,Instant expiresAt) {
        @Override public String toString(){return "ApprovalCheckout[redacted]";}
    }
    record ApprovalGrant(String id,String subjectId,String merchantId,String state,long version,Instant validFrom,Instant expiresAt,String budgetId) {
        @Override public String toString(){return "ApprovalGrant[redacted]";}
    }
    record ApprovalBudget(String id,String currency,boolean frozen,long held,long consumed,long limitMinor) {
        @Override public String toString(){return "ApprovalBudget[redacted]";}
    }
    /** Binds one provisional attempt to its final reservation; cannot admit, release, or charge. */
    record ApprovalWrite(UUID intentId,long expectedVersion,UUID attemptId,int checkoutRevision,String grantId,long grantVersion,String subjectId,String budgetId,long minorUnits) {
        @Override public String toString(){return "ApprovalWrite[redacted]";}
    }

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
