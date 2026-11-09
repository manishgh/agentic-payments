package io.agenticpayments.application;

import io.agenticpayments.application.PaymentService.*;
import java.time.Instant;
import java.util.*;

/** Atomic core phases. External authentication calls are forbidden in callbacks. */
public interface CustomerActionTransactions {
    String CONTRACT="customer-action-transactions-1";
    <T>T run(Session session,Work<T> work);
    @FunctionalInterface interface Work<T>{T run(Transaction transaction)throws Exception;}
    interface Transaction {
        /** Authorize and bind subject/version/decision/key before returning a committed reservation. */
        Reservation reserve(UUID actionId,String key,CompleteActionCommand command)throws Exception;
        /** Recheck exact association, current authority, expiry and version; atomically persist response and work. */
        Result finish(Request request,AuthenticationObservation observation)throws Exception;
    }
    record Request(UUID requestId,UUID actionId,String key,int expectedVersion,String subjectId,
                   ProviderActionBinding binding,String decision,Instant now) {
        public Request {
            Objects.requireNonNull(requestId);Objects.requireNonNull(actionId);Objects.requireNonNull(key);
            Objects.requireNonNull(subjectId);Objects.requireNonNull(binding);Objects.requireNonNull(now);
            if(expectedVersion<1||!Set.of("approve","deny").contains(decision))throw new IllegalArgumentException("Invalid action request.");
        }
        @Override public String toString(){return "CustomerActionRequest[redacted]";}
    }
    record Reservation(Optional<Result> replay,Optional<Request> request) {
        public Reservation {
            Objects.requireNonNull(replay);Objects.requireNonNull(request);
            if(replay.isPresent()==request.isPresent())throw new IllegalArgumentException("Invalid action reservation.");
        }
    }
}
