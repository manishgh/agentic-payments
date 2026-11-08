package io.agenticpayments.application.instrumentation;

import io.agenticpayments.application.CheckoutLifecycle;
import io.agenticpayments.application.PaymentService.Session;
import java.util.Objects;
import java.util.UUID;

/** Commerce observation is outside the application transaction and never changes its outcome. */
public final class InstrumentedCheckoutLifecycle implements CheckoutLifecycle {
    private final CheckoutLifecycle inner;
    private final ServiceTelemetry telemetry;
    public InstrumentedCheckoutLifecycle(CheckoutLifecycle inner, ServiceTelemetry telemetry) {
        this.inner = Objects.requireNonNull(inner); this.telemetry = Objects.requireNonNull(telemetry);
    }
    @Override public SafeView stage(Session session, UUID purchase, String key, StageCommand command) {
        return telemetry.call(ServiceTelemetry.Operation.COMMERCE_STAGE, () -> inner.stage(session, purchase, key, command));
    }
    @Override public SafeView status(Session session, UUID purchase) {
        return telemetry.call(ServiceTelemetry.Operation.COMMERCE_STATUS, () -> inner.status(session, purchase));
    }
    @Override public SafeView dispatch(Session session, UUID purchase, UUID command) {
        return telemetry.call(ServiceTelemetry.Operation.COMMERCE_DISPATCH, () -> inner.dispatch(session, purchase, command));
    }
    @Override public SafeView recoverClaim(Session session, UUID purchase, UUID command) {
        return telemetry.call(ServiceTelemetry.Operation.COMMERCE_RECOVER, () -> inner.recoverClaim(session, purchase, command));
    }
}
