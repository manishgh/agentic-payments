package io.agenticpayments.application;

import java.time.Instant;
import java.util.UUID;
import io.agenticpayments.application.PaymentService.*;

/** Transactional use-case port. The adapter supplies durable, atomic implementations. */
public interface PaymentStore {
    Result create(Session session,String key,CreateCommand command);
    Result quote(Session session,UUID id,String key,QuoteCommand command);
    Result approve(Session session,UUID id,String key,ApproveCommand command);
    Result execute(Session session,UUID id,String key,ExecuteCommand command);
    Result status(Session session,UUID id);
    Result checkout(Session session,UUID id);
    Result action(Session session,UUID id);
    Result completeAction(Session session,UUID id,String key,CompleteActionCommand command);
    Result revoke(Session session,String id,String key,RevokeCommand command);
    void seedRun(Session session,Instant clock,long budget);
    void advanceClock(Session session,Instant clock);
    boolean runNext(Session session);
    WorkerReceipt runNextRecorded(Session session);
    void reconcile(Session session,UUID id,boolean sealIfAbsent);
}
