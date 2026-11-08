package io.agenticpayments.application;

/** Closed, transport-independent catalog for application-failure-0.1.
 * Certainty applies only to this invocation and never permits changing operation identity
 * or releasing exposure from an earlier or concurrent invocation.
 */
public enum ApplicationFailure {
    INVALID_REQUEST("INVALID_REQUEST", "invalid request", Category.VALIDATION, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    UNAUTHENTICATED("UNAUTHENTICATED", "unauthenticated", Category.UNAUTHENTICATED, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    FORBIDDEN("FORBIDDEN", "forbidden", Category.FORBIDDEN, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    MERCHANT_NOT_ALLOWED("MERCHANT_NOT_ALLOWED", "merchant not allowed", Category.FORBIDDEN, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    NOT_FOUND("NOT_FOUND", "not found", Category.NOT_FOUND, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", "resource not found", Category.NOT_FOUND, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    ACTION_ALREADY_CONSUMED("ACTION_ALREADY_CONSUMED", "action already consumed", Category.CONFLICT, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    AUTHORITY_REPLAY("AUTHORITY_REPLAY", "authority replay", Category.CONFLICT, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    AUTHORITY_REVOKED("AUTHORITY_REVOKED", "authority revoked", Category.CONFLICT, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    BUDGET_EXCEEDED("BUDGET_EXCEEDED", "budget exceeded", Category.CONFLICT, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    CHECKOUT_ALREADY_BOUND("CHECKOUT_ALREADY_BOUND", "checkout already bound", Category.CONFLICT, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    CHECKOUT_ALREADY_EXISTS("CHECKOUT_ALREADY_EXISTS", "checkout already exists", Category.CONFLICT, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    CHECKOUT_BINDING_CONFLICT("CHECKOUT_BINDING_CONFLICT", "checkout binding conflict", Category.CONFLICT, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    CHECKOUT_CHANGED("CHECKOUT_CHANGED", "checkout changed", Category.CONFLICT, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    CHECKOUT_COMMAND_IN_FLIGHT("CHECKOUT_COMMAND_IN_FLIGHT", "checkout command in flight", Category.CONFLICT, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    CHECKOUT_CORRELATION_CONFLICT("CHECKOUT_CORRELATION_CONFLICT", "checkout correlation conflict", Category.CONFLICT, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    CHECKOUT_MUTATION_BLOCKED("CHECKOUT_MUTATION_BLOCKED", "checkout mutation blocked", Category.CONFLICT, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    CHECKOUT_REVISION_CONFLICT("CHECKOUT_REVISION_CONFLICT", "checkout revision conflict", Category.CONFLICT, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    CHECKOUT_REVISION_EXHAUSTED("CHECKOUT_REVISION_EXHAUSTED", "checkout revision exhausted", Category.CONFLICT, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    CONFIGURATION_CHANGED("CONFIGURATION_CHANGED", "configuration changed", Category.CONFLICT, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    CONFIGURATION_IDENTITY_CONFLICT("CONFIGURATION_IDENTITY_CONFLICT", "configuration identity conflict", Category.CONFLICT, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    IDEMPOTENCY_CONFLICT("IDEMPOTENCY_CONFLICT", "idempotency conflict", Category.CONFLICT, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    INVALID_STATE("INVALID_STATE", "invalid state", Category.CONFLICT, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    TRUSTED_RESOLUTION_REQUIRED("TRUSTED_RESOLUTION_REQUIRED", "trusted resolution required", Category.CONFLICT, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    VERSION_CONFLICT("VERSION_CONFLICT", "version conflict", Category.CONFLICT, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    ACTION_EXPIRED("ACTION_EXPIRED", "action expired", Category.EXPIRED, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    AUTHORITY_EXPIRED("AUTHORITY_EXPIRED", "authority expired", Category.EXPIRED, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    AUTHORITY_BINDING_INVALID("AUTHORITY_BINDING_INVALID", "authority binding invalid", Category.VALIDATION, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    CHECKOUT_BINDING_CONFLICT_VALIDATION("CHECKOUT_BINDING_CONFLICT", "checkout binding conflict", Category.VALIDATION, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    CHECKOUT_CONFIGURATION_UNAVAILABLE("CHECKOUT_CONFIGURATION_UNAVAILABLE", "checkout configuration unavailable", Category.VALIDATION, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    INVALID_CHECKOUT_PREPARATION("INVALID_CHECKOUT_PREPARATION", "invalid checkout preparation", Category.VALIDATION, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    UNSUPPORTED_CAPABILITY("UNSUPPORTED_CAPABILITY", "unsupported capability", Category.UNSUPPORTED, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    UNSUPPORTED_PROFILE("UNSUPPORTED_PROFILE", "unsupported profile", Category.UNSUPPORTED, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    UNSUPPORTED_TENDER("UNSUPPORTED_TENDER", "unsupported tender", Category.UNSUPPORTED, Retry.NEVER, Certainty.NOT_STARTED_THIS_INVOCATION),
    CHECKOUT_STORE_UNAVAILABLE("CHECKOUT_STORE_UNAVAILABLE", "checkout store unavailable", Category.UNAVAILABLE, Retry.NEVER, Certainty.MAY_HAVE_OCCURRED),
    TEMPORARILY_UNAVAILABLE("TEMPORARILY_UNAVAILABLE", "temporarily unavailable", Category.UNAVAILABLE, Retry.NEVER, Certainty.MAY_HAVE_OCCURRED),
    TEMPORARILY_UNAVAILABLE_SAME_COMMAND("TEMPORARILY_UNAVAILABLE", "temporarily unavailable", Category.UNAVAILABLE, Retry.SAME_COMMAND, Certainty.MAY_HAVE_OCCURRED),
    CHECKOUT_STORE_UNAVAILABLE_SAME_COMMAND("CHECKOUT_STORE_UNAVAILABLE", "checkout store unavailable", Category.UNAVAILABLE, Retry.SAME_COMMAND, Certainty.MAY_HAVE_OCCURRED);

    public enum Category { VALIDATION, UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, CONFLICT, UNSUPPORTED, EXPIRED, UNAVAILABLE, INTERNAL }
    public enum Retry { NEVER, SAME_COMMAND, RECONCILE_SAME_OPERATION }
    public enum Certainty { NOT_STARTED_THIS_INVOCATION, MAY_HAVE_OCCURRED, NOT_APPLICABLE }

    private final String code;
    private final String message;
    private final Category category;
    private final Retry retry;
    private final Certainty certainty;

    ApplicationFailure(String code, String message, Category category, Retry retry, Certainty certainty) {
        this.code = code;
        this.message = message;
        this.category = category;
        this.retry = retry;
        this.certainty = certainty;
    }

    public String id() { return name(); }
    public String code() { return code; }
    public String message() { return message; }
    public Category category() { return category; }
    public Retry retry() { return retry; }
    public Certainty certainty() { return certainty; }
}
