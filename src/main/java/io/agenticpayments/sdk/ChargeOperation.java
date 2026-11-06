package io.agenticpayments.sdk;

import java.util.Objects;
import java.util.Set;

/** Contains safe synthetic identifiers only; credentials are resolved behind the adapter. */
public record ChargeOperation(OperationIdentity identity, Money money, String scenarioId) {
    public static final Set<String> SCENARIOS = Set.of("success", "challenge-then-success",
            "response-loss-after-acceptance", "delayed-success", "definitive-no-effect");

    public ChargeOperation {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(money, "money");
        if (!SCENARIOS.contains(Objects.requireNonNull(scenarioId, "scenarioId"))) {
            throw new IllegalArgumentException("Unsupported local-fake scenario");
        }
    }
}
