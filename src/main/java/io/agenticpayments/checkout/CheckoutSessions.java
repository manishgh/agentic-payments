package io.agenticpayments.checkout;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Commerce-session responsibility. No method authorizes, completes or executes a payment. */
public interface CheckoutSessions {
    String CONTRACT_VERSION = "1";

    Descriptor describe();
    Result create(Binding binding, Command command, Create request);
    Result retrieve(Session session, Correlation correlation);
    Result update(Session session, Command command, Update request);
    Result cancel(Session session, Command command);

    enum Operation { CREATE, RETRIEVE, UPDATE, CANCEL }
    enum Code { OK, INVALID_INPUT, UNSUPPORTED_CAPABILITY, ACCESS_REFUSED, NOT_FOUND,
        NOT_CANCELABLE, IDEMPOTENCY_REQUIRED, IDEMPOTENCY_IN_FLIGHT, IDEMPOTENCY_CONFLICT,
        RATE_LIMITED, PEER_FAILURE, INVALID_RESPONSE, TRANSPORT_UNCERTAIN }
    /** UNKNOWN is never evidence of no financial or commerce effect. */
    enum Observation { NOT_SENT, OBSERVED, UNKNOWN }

    record Descriptor(String contractVersion, String implementation, Set<String> profiles,
                      Set<Operation> operations) {
        public Descriptor {
            token(contractVersion); token(implementation);
            profiles = Set.copyOf(profiles); profiles.forEach(CheckoutSessions::token);
            operations = Set.copyOf(operations);
        }
        public void require(String profile, Set<Operation> required) {
            if (!CONTRACT_VERSION.equals(contractVersion) || !profiles.contains(profile)
                    || !operations.containsAll(required)) throw new IllegalArgumentException("UNSUPPORTED_CAPABILITY");
        }
    }

    /** Trusted enrollment/authorization binding, never an arbitrary destination or credential. */
    record Binding(String tenantId, String principalId, String merchantId, String configurationId) {
        public Binding { token(tenantId); token(principalId); token(merchantId); token(configurationId); }
        @Override public String toString() { return "Binding[redacted]"; }
    }

    record Command(String idempotencyKey, String requestId) {
        public Command {
            if (idempotencyKey == null || idempotencyKey.isEmpty() || idempotencyKey.length() > 255
                    || idempotencyKey.chars().anyMatch(c -> c < 33 || c > 126)) invalid();
            if (requestId != null) token(requestId);
        }
        @Override public String toString() { return "Command[redacted]"; }
    }

    record Correlation(String requestId) {
        public Correlation { if (requestId != null) token(requestId); }
        @Override public String toString() { return "Correlation[redacted]"; }
    }

    record ItemReference(String id) {
        public ItemReference { token(id); }
        @Override public String toString() { return "ItemReference[redacted]"; }
    }

    record Create(List<ItemReference> items, String currency) {
        public Create { items = itemList(items); supportedCurrency(currency); }
        @Override public String toString() { return "Create[redacted]"; }
    }

    record Update(List<ItemReference> items) {
        public Update { items = itemList(items); }
        @Override public String toString() { return "Update[redacted]"; }
    }

    record Session(Binding binding, String id, String currency) {
        public Session { Objects.requireNonNull(binding); token(id); supportedCurrency(currency); }
        @Override public String toString() { return "Session[redacted]"; }
    }

    /** Validated echo booleans never contain arbitrary peer text. Retry seconds are bounded. */
    record Metadata(boolean requestIdMatched, boolean idempotencyKeyMatched,
                    boolean idempotentReplayed, Long retryAfterSeconds) {
        public Metadata {
            if (retryAfterSeconds != null && (retryAfterSeconds < 0 || retryAfterSeconds > 86400)) invalid();
        }
        public static Metadata absent() { return new Metadata(false, false, false, null); }
    }

    /** Exact protected response bytes. Projection is lifecycle evidence, never payment authority. */
    record Document(Session session, String status, String observedProtocolVersion, byte[] bytes) {
        public Document {
            Objects.requireNonNull(session); token(status);
            if (observedProtocolVersion != null) token(observedProtocolVersion);
            if (bytes == null || bytes.length == 0 || bytes.length > 131072) invalid();
            bytes = bytes.clone();
        }
        @Override public byte[] bytes() { return bytes.clone(); }
        @Override public String toString() { return "Document[protected]"; }
    }

    /** httpStatus=0 means no response headers were observed, not proof that a request was not sent. */
    record Result(int httpStatus, Code code, Observation observation, Metadata metadata, Document document) {
        public Result {
            if (httpStatus != 0 && (httpStatus < 100 || httpStatus > 599)) invalid();
            Objects.requireNonNull(code); Objects.requireNonNull(observation); Objects.requireNonNull(metadata);
            if ((code == Code.OK) != (document != null)) invalid();
        }
        public static Result notSent(Code code) {
            return new Result(0, code, Observation.NOT_SENT, Metadata.absent(), null);
        }
        @Override public String toString() {
            return "Result[httpStatus=" + httpStatus + ",code=" + code + ",observation=" + observation + "]";
        }
    }

    private static List<ItemReference> itemList(List<ItemReference> items) {
        if (items == null || items.isEmpty() || items.size() > 32) invalid();
        List<ItemReference> copy = List.copyOf(items);
        if (copy.stream().map(ItemReference::id).distinct().count() != copy.size()) invalid();
        return copy;
    }
    private static void supportedCurrency(String currency) {
        if (!"usd".equals(currency) && !"eur".equals(currency)) invalid();
    }
    private static void token(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
                || value.equals(".") || value.equals("..")) invalid();
    }
    private static void invalid() { throw new IllegalArgumentException("INVALID_CHECKOUT_INPUT"); }
}
