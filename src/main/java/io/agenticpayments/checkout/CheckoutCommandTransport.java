package io.agenticpayments.checkout;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import static io.agenticpayments.checkout.CheckoutSessions.*;

/** Exact-request preparation and sending, independently replaceable from durable storage. */
public interface CheckoutCommandTransport {
    String CONTRACT_VERSION = "checkout-command-transport-0.1";
    Descriptor describe();
    String encodingVersion();
    /** Non-null peer version assertions accepted by this pinned transport profile. Absence stays absence. */
    Set<String> observedProtocolVersions();
    Prepared prepare(Request request);
    Result send(Prepared prepared);

    record Request(Binding binding, Operation operation, String sessionId, String currency,
                   List<ItemReference> items, String idempotencyKey, String requestId) {
        public Request {
            Objects.requireNonNull(binding); Objects.requireNonNull(operation);
            items = List.copyOf(items);
            if (operation == Operation.CREATE) {
                if (sessionId != null) invalid();
                new Create(items, currency); new Command(idempotencyKey, requestId);
            } else {
                new Session(binding, sessionId, currency);
                if (operation == Operation.UPDATE) new Update(items);
                else if (!items.isEmpty()) invalid();
                if (operation == Operation.RETRIEVE) {
                    if (idempotencyKey != null) invalid();
                    new Correlation(requestId);
                } else new Command(idempotencyKey, requestId);
            }
        }
        @Override public String toString() { return "Request[protected]"; }
    }
    record Prepared(Request request, String profile, String encodingVersion,
                    String method, String path, String contentType, byte[] body) {
        public Prepared {
            Objects.requireNonNull(request);
            if (profile == null || encodingVersion == null || method == null || path == null
                    || path.length() > 512 || body != null && body.length > 131072) invalid();
            body = body == null ? null : body.clone();
        }
        @Override public byte[] body() { return body == null ? null : body.clone(); }
        @Override public String toString() { return "Prepared[protected]"; }
    }
    private static void invalid() { throw new IllegalArgumentException("INVALID_CHECKOUT_INPUT"); }
}
