package io.agenticpayments.application;

import io.agenticpayments.application.PaymentService.Session;
import java.util.Objects;
import static io.agenticpayments.application.ProviderEventDeliveryQueue.*;

/** Dispute-only delivery: no event policy, signing key or financial provider dependency is required. */
public final class QuarantineProviderEventDelivery implements ProviderEventDeliveryHandler {
    private final Session runner;private final PaymentWorkerTransactions transactions;private final ProviderEventDisputeVerifier disputes;
    public QuarantineProviderEventDelivery(Session runner,PaymentWorkerTransactions transactions,ProviderEventDisputeVerifier disputes) {
        this.runner=Objects.requireNonNull(runner);this.transactions=Objects.requireNonNull(transactions);this.disputes=Objects.requireNonNull(disputes);
        if(!runner.roles().contains("runner:write"))throw new PaymentException(ApplicationFailure.FORBIDDEN);
    }
    public void deliver(Delivery delivery) {
        Objects.requireNonNull(delivery);var expected=delivery.operation();var id=expected.identity();
        if(delivery.kind()!=Kind.QUARANTINE||!runner.tenantId().equals(id.tenantId())||!runner.runId().toString().equals(id.runId())
                ||!runner.allowedMerchants().contains(id.merchantId()))throw new PaymentException(ApplicationFailure.FORBIDDEN);
        disputes.requireQuarantined(expected);
        transactions.run(runner,tx->{tx.quarantineProviderOperation(expected);return null;});
    }
}
