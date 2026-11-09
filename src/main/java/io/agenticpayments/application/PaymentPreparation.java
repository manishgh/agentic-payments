package io.agenticpayments.application;

import io.agenticpayments.sdk.*;
import java.util.*;

/** Versioned preparation responsibility; implementations may adapt or decorate provider ports. */
public interface PaymentPreparation {
    String CONTRACT="payment-preparation-observation-1";
    PreparationObservation preparePayment(ChargeOperation operation);
    static PaymentPreparation legacy(PaymentAdapter adapter) {
        Objects.requireNonNull(adapter);
        return operation->new PreparationObservation(adapter.prepare(operation).actionType(),Optional.empty());
    }
}
