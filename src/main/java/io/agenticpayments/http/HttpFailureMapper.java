package io.agenticpayments.http;

import io.agenticpayments.application.ApplicationException;
import io.agenticpayments.application.ApplicationFailure;
import java.util.Objects;

/** Existing HTTP failure representation; application failures contain no HTTP metadata. */
public final class HttpFailureMapper {
    private HttpFailureMapper() { }

    public record MappedFailure(int status, String code, String message, String retry) { }

    public static MappedFailure map(ApplicationException exception) {
        return map(Objects.requireNonNull(exception).failure());
    }

    public static MappedFailure map(ApplicationFailure failure) {
        Objects.requireNonNull(failure);
        int status = switch (failure) {
            case INVALID_REQUEST -> 400;
            case UNAUTHENTICATED -> 401;
            case FORBIDDEN, MERCHANT_NOT_ALLOWED -> 403;
            case NOT_FOUND, RESOURCE_NOT_FOUND -> 404;
            case ACTION_ALREADY_CONSUMED, AUTHORITY_REPLAY, AUTHORITY_REVOKED, BUDGET_EXCEEDED, CHECKOUT_ALREADY_BOUND, CHECKOUT_ALREADY_EXISTS, CHECKOUT_BINDING_CONFLICT, CHECKOUT_CHANGED, CHECKOUT_COMMAND_IN_FLIGHT, CHECKOUT_CORRELATION_CONFLICT, CHECKOUT_MUTATION_BLOCKED, CHECKOUT_REVISION_CONFLICT, CHECKOUT_REVISION_EXHAUSTED, CONFIGURATION_CHANGED, CONFIGURATION_IDENTITY_CONFLICT, IDEMPOTENCY_CONFLICT, INVALID_STATE, TRUSTED_RESOLUTION_REQUIRED, VERSION_CONFLICT -> 409;
            case ACTION_EXPIRED, AUTHORITY_EXPIRED -> 410;
            case AUTHORITY_BINDING_INVALID, CHECKOUT_BINDING_CONFLICT_VALIDATION, CHECKOUT_CONFIGURATION_UNAVAILABLE, INVALID_CHECKOUT_PREPARATION, UNSUPPORTED_CAPABILITY, UNSUPPORTED_PROFILE, UNSUPPORTED_TENDER -> 422;
            case CHECKOUT_STORE_UNAVAILABLE, TEMPORARILY_UNAVAILABLE, TEMPORARILY_UNAVAILABLE_SAME_COMMAND, CHECKOUT_STORE_UNAVAILABLE_SAME_COMMAND -> 503;
        };
        return new MappedFailure(status, failure.code(), failure.message(), retry(failure.retry()));
    }

    public static String retry(ApplicationFailure.Retry retry) {
        return switch (retry) {
            case NEVER -> "NEVER";
            case SAME_COMMAND -> "SAME_COMMAND";
            case RECONCILE_SAME_OPERATION -> "READ_STATUS";
        };
    }

    public static MappedFailure invalidRequest() {
        return new MappedFailure(400, "INVALID_REQUEST", "The request does not match the contract.", "NEVER");
    }

    public static MappedFailure unexpected() {
        return new MappedFailure(503, "TEMPORARILY_UNAVAILABLE", "The service is temporarily unavailable.", "SAME_COMMAND");
    }
}
