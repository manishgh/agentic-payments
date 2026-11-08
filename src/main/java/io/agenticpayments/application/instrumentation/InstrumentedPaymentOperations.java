package io.agenticpayments.application.instrumentation;

import io.agenticpayments.application.*;
import io.agenticpayments.application.PaymentService.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Observes completed invocations; never retries or changes a financial result. */
public final class InstrumentedPaymentOperations implements PaymentOperations {
    private final PaymentOperations inner;
    private final ServiceTelemetry telemetry;
    public InstrumentedPaymentOperations(PaymentOperations inner, ServiceTelemetry telemetry) {
        this.inner = Objects.requireNonNull(inner);
        this.telemetry = Objects.requireNonNull(telemetry);
    }
    @Override public Result create(Session s,String k,CreateCommand c) { return telemetry.call(ServiceTelemetry.Operation.CREATE, () -> inner.create(s, k, c)); }
    @Override public Result quote(Session s,UUID id,String k,QuoteCommand c) { return telemetry.call(ServiceTelemetry.Operation.QUOTE, () -> inner.quote(s, id, k, c)); }
    @Override public Result quoteExternal(Session s,UUID id,String k,ExternalQuoteCommand c) { return telemetry.call(ServiceTelemetry.Operation.QUOTE_EXTERNAL, () -> inner.quoteExternal(s, id, k, c)); }
    @Override public Result approve(Session s,UUID id,String k,ApproveCommand c) { return telemetry.call(ServiceTelemetry.Operation.APPROVE, () -> inner.approve(s, id, k, c)); }
    @Override public Result execute(Session s,UUID id,String k,ExecuteCommand c) { return telemetry.call(ServiceTelemetry.Operation.EXECUTE, () -> inner.execute(s, id, k, c)); }
    @Override public Result status(Session s,UUID id) { return telemetry.call(ServiceTelemetry.Operation.STATUS, () -> inner.status(s, id)); }
    @Override public Result checkout(Session s,UUID id) { return telemetry.call(ServiceTelemetry.Operation.CHECKOUT, () -> inner.checkout(s, id)); }
    @Override public Result action(Session s,UUID id) { return telemetry.call(ServiceTelemetry.Operation.ACTION, () -> inner.action(s, id)); }
    @Override public Result completeAction(Session s,UUID id,String k,CompleteActionCommand c) { return telemetry.call(ServiceTelemetry.Operation.COMPLETE_ACTION, () -> inner.completeAction(s, id, k, c)); }
    @Override public Result revoke(Session s,String id,String k,RevokeCommand c) { return telemetry.call(ServiceTelemetry.Operation.REVOKE, () -> inner.revoke(s, id, k, c)); }
    @Override public void seedRun(Session s,Instant clock,long budget) { telemetry.run(ServiceTelemetry.Operation.SEED_RUN, () -> inner.seedRun(s, clock, budget)); }
    @Override public void advanceClock(Session s,Instant clock) { telemetry.run(ServiceTelemetry.Operation.ADVANCE_CLOCK, () -> inner.advanceClock(s, clock)); }
    @Override public boolean runNext(Session s) { return telemetry.call(ServiceTelemetry.Operation.RUN_NEXT, () -> inner.runNext(s)); }
    @Override public WorkerReceipt runNextRecorded(Session s) { return telemetry.call(ServiceTelemetry.Operation.RUN_NEXT_RECORDED, () -> inner.runNextRecorded(s)); }
    @Override public void reconcile(Session s,UUID id,boolean sealIfAbsent) { telemetry.run(ServiceTelemetry.Operation.RECONCILE, () -> inner.reconcile(s, id, sealIfAbsent)); }
}
