package io.agenticpayments.sdk;

import java.util.Objects;

/** Effect-free result; any trusted action is created and verified by the core. */
public record Preparation(ActionType actionType) {
    public Preparation {
        Objects.requireNonNull(actionType, "actionType");
    }
}
