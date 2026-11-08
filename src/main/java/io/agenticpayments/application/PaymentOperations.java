package io.agenticpayments.application;

import io.agenticpayments.application.PaymentService.*;
import java.time.Instant;
import java.util.UUID;

/** Authenticated payment use cases. Implementations and decorators preserve command ownership and replay. */
public interface PaymentOperations {
    String CONTRACT_VERSION = "payment-operations-0.1";
    Result create(Session s,String k,CreateCommand c);
    Result quote(Session s,UUID id,String k,QuoteCommand c);
    Result quoteExternal(Session s,UUID id,String k,ExternalQuoteCommand c);
    Result approve(Session s,UUID id,String k,ApproveCommand c);
    Result execute(Session s,UUID id,String k,ExecuteCommand c);
    Result status(Session s,UUID id);
    Result checkout(Session s,UUID id);
    Result action(Session s,UUID id);
    Result completeAction(Session s,UUID id,String k,CompleteActionCommand c);
    Result revoke(Session s,String id,String k,RevokeCommand c);
    void seedRun(Session s,Instant clock,long budget);
    void advanceClock(Session s,Instant clock);
    boolean runNext(Session s);
    WorkerReceipt runNextRecorded(Session s);
    void reconcile(Session s,UUID id,boolean sealIfAbsent);
}
