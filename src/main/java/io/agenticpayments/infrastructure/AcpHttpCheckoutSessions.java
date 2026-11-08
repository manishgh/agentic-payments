package io.agenticpayments.infrastructure;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agenticpayments.checkout.CheckoutSessions;
import io.agenticpayments.checkout.CheckoutCommandTransport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static io.agenticpayments.checkout.CheckoutSessions.*;

/**
 * Independently authored ACP 2026-04-17 bounded lifecycle envelope transport; no complete/payment.
 * One application sendAsync invocation, not a universal single physical HTTP request guarantee:
 * JDK connection/safe-GET/classified-unprocessed retries may occur inside the same total deadline.
 * Supported launch configuration must not enable all-method retries, including before JDK static
 * initialization. The current-property check cannot establish an earlier cached JDK setting.
 * No process-global configuration is changed. Caller owns durable command/outcome persistence.
 */
public final class AcpHttpCheckoutSessions implements CheckoutSessions, CheckoutCommandTransport, AutoCloseable {
    public static final String PROFILE = "acp-2026-04-17-lifecycle-envelope-0.1";
    public static final String ACP_VERSION = "2026-04-17";
    public static final String ENCODING_VERSION = "acp-lifecycle-json-0.1";
    private static final int MAX_BODY = 131072;
    private static final Set<String> STATUSES = Set.of("incomplete", "not_ready_for_payment", "requires_escalation",
            "authentication_required", "ready_for_payment", "pending_approval", "complete_in_progress", "completed",
            "canceled", "in_progress", "expired");
    private static final Set<String> FIELDS = Set.of("id", "protocol", "capabilities", "buyer", "status", "currency",
            "presentment_currency", "exchange_rate", "exchange_rate_timestamp", "locale", "timezone", "line_items",
            "fulfillment_details", "fulfillment_options", "selected_fulfillment_options", "fulfillment_groups", "totals",
            "messages", "links", "authentication_metadata", "created_at", "updated_at", "expires_at", "continue_url",
            "metadata", "quote_id", "quote_expires_at", "marketing_consent_options", "discounts", "order");
    private final Configuration configuration;
    private final char[] bearer;
    private final HttpClient client;
    private final Object lifecycle = new Object();
    private volatile boolean closed;
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(32).maxDocumentLength(MAX_BODY)
                    .maxStringLength(65536).maxNameLength(128).maxNumberLength(128).maxTokenCount(32768).build()).build())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Trusted fixed origin. HTTP is available only for explicit literal-loopback synthetic tests. */
    public record Configuration(Binding binding, URI origin, boolean loopbackTest,
                                Duration connectTimeout, Duration totalTimeout) {
        public Configuration {
            if (binding == null || origin == null || connectTimeout == null || totalTimeout == null
                    || connectTimeout.isNegative() || connectTimeout.isZero() || totalTimeout.isNegative() || totalTimeout.isZero()
                    || totalTimeout.compareTo(Duration.ofSeconds(30)) > 0 || connectTimeout.compareTo(totalTimeout) > 0)
                invalidConfiguration();
            if (origin.getUserInfo() != null || origin.getRawQuery() != null || origin.getRawFragment() != null
                    || origin.getHost() == null || (!"".equals(origin.getRawPath()) && !"/".equals(origin.getRawPath()))
                    || origin.getPort() < -1 || origin.getPort() == 0 || origin.getPort() > 65535) invalidConfiguration();
            if (loopbackTest) {
                if (!"http".equals(origin.getScheme()) || !"127.0.0.1".equals(origin.getHost())) invalidConfiguration();
            } else if (!"https".equals(origin.getScheme())) invalidConfiguration();
        }
        @Override public String toString() { return "Configuration[redacted]"; }
    }

    public AcpHttpCheckoutSessions(Configuration configuration, char[] bearer) {
        this.configuration = java.util.Objects.requireNonNull(configuration);
        String retry = System.getProperty("jdk.httpclient.enableAllMethodRetry");
        if (retry != null && (retry.isEmpty() || Boolean.parseBoolean(retry))) invalidConfiguration();
        if (bearer == null || bearer.length == 0 || bearer.length > 4096) invalidConfiguration();
        for (char c : bearer) if (c < 33 || c > 126) invalidConfiguration();
        if (configuration.loopbackTest() && !new String(bearer).startsWith("synthetic-")) invalidConfiguration();
        this.bearer = bearer.clone();
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(configuration.connectTimeout())
                .proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
                }).build();
    }

    @Override public Descriptor describe() {
        return new Descriptor(CheckoutSessions.CONTRACT_VERSION, "independent-acp-http", Set.of(PROFILE), Set.of(Operation.values()));
    }

    @Override public String encodingVersion() { return ENCODING_VERSION; }
    @Override public Set<String> observedProtocolVersions() { return Set.of(ACP_VERSION); }
    @Override public CheckoutCommandTransport.Prepared prepare(CheckoutCommandTransport.Request request) {
        if (!configuration.binding().equals(request.binding())) throw new IllegalArgumentException("INVALID_CHECKOUT_INPUT");
        ObjectNode body = null;
        if (request.operation() == Operation.CREATE) {
            body = itemBody(request.items()); body.put("currency", request.currency()); body.putObject("capabilities");
        } else if (request.operation() == Operation.UPDATE) body = itemBody(request.items());
        try {
            String path = "/checkout_sessions" + (request.sessionId() == null ? "" : "/" + request.sessionId())
                    + (request.operation() == Operation.CANCEL ? "/cancel" : "");
            return new CheckoutCommandTransport.Prepared(request, PROFILE, ENCODING_VERSION,
                    request.operation() == Operation.RETRIEVE ? "GET" : "POST", path,
                    body == null ? null : "application/json", body == null ? null : json.writeValueAsBytes(body));
        } catch (IOException failure) { throw new IllegalArgumentException("INVALID_CHECKOUT_INPUT"); }
    }
    @Override public Result send(CheckoutCommandTransport.Prepared prepared) {
        if (prepared == null) return Result.notSent(Code.INVALID_INPUT);
        try {
            var expected = prepare(prepared.request());
            if (!expected.profile().equals(prepared.profile()) || !expected.encodingVersion().equals(prepared.encodingVersion())
                    || !expected.method().equals(prepared.method()) || !expected.path().equals(prepared.path())
                    || !java.util.Objects.equals(expected.contentType(), prepared.contentType())
                    || !Arrays.equals(expected.body(), prepared.body())) return Result.notSent(Code.INVALID_INPUT);
        } catch (RuntimeException failure) { return Result.notSent(Code.INVALID_INPUT); }
        var r = prepared.request();
        // Preflight is complete. A failure from this point cannot establish that nothing was sent.
        return sendBytes(r.binding(), r.sessionId(), r.currency(), r.operation(), r.idempotencyKey(),
                r.requestId(), prepared.body());
    }

    @Override public Result create(Binding binding, Command command, Create request) {
        if (request == null || command == null || binding == null) return Result.notSent(Code.INVALID_INPUT);
        ObjectNode body = itemBody(request.items()); body.put("currency", request.currency()); body.putObject("capabilities");
        return send(binding, null, request.currency(), Operation.CREATE, command.idempotencyKey(), command.requestId(), body);
    }
    @Override public Result retrieve(Session session, Correlation correlation) {
        if (session == null || correlation == null) return Result.notSent(Code.INVALID_INPUT);
        return send(session.binding(), session.id(), session.currency(), Operation.RETRIEVE, null, correlation.requestId(), null);
    }
    @Override public Result update(Session session, Command command, Update request) {
        if (session == null || command == null || request == null) return Result.notSent(Code.INVALID_INPUT);
        return send(session.binding(), session.id(), session.currency(), Operation.UPDATE,
                command.idempotencyKey(), command.requestId(), itemBody(request.items()));
    }
    @Override public Result cancel(Session session, Command command) {
        if (session == null || command == null) return Result.notSent(Code.INVALID_INPUT);
        return send(session.binding(), session.id(), session.currency(), Operation.CANCEL,
                command.idempotencyKey(), command.requestId(), null);
    }
    private ObjectNode itemBody(List<ItemReference> items) {
        ObjectNode body = json.createObjectNode();
        var array = body.putArray("line_items");
        for (ItemReference item : items) array.addObject().put("id", item.id());
        return body;
    }

    private Result send(Binding binding, String sessionId, String currency, Operation operation,
                        String key, String requestId, ObjectNode body) {
        try { return sendBytes(binding, sessionId, currency, operation, key, requestId,
                body == null ? null : json.writeValueAsBytes(body)); }
        catch (IOException failure) { return Result.notSent(Code.INVALID_INPUT); }
    }
    private Result sendBytes(Binding binding, String sessionId, String currency, Operation operation,
                             String key, String requestId, byte[] body) {
        if (!configuration.binding().equals(binding)) return Result.notSent(Code.ACCESS_REFUSED);
        if (closed) return Result.notSent(Code.ACCESS_REFUSED);
        long deadline = System.nanoTime() + configuration.totalTimeout().toNanos();
        AtomicInteger status = new AtomicInteger();
        AtomicReference<HttpHeaders> headers = new AtomicReference<>();
        AtomicReference<BoundedBody> activeBody = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        CompletableFuture<HttpResponse<byte[]>> future = null;
        try {
            String path = "/checkout_sessions" + (sessionId == null ? "" : "/" + sessionId)
                    + (operation == Operation.CANCEL ? "/cancel" : "");
            URI destination = configuration.origin().resolve(path);
            byte[] requestBytes = body == null ? new byte[0] : body;
            var builder = HttpRequest.newBuilder(destination).timeout(configuration.totalTimeout())
                    .header("API-Version", ACP_VERSION)
                    .header("User-Agent", "agentic-payments-lifecycle/0.1").header("Accept", "application/json");
            if (requestId != null) builder.header("Request-Id", requestId);
            if (key != null) builder.header("Idempotency-Key", key);
            if (body != null) builder.header("Content-Type", "application/json");
            if (operation == Operation.RETRIEVE) builder.GET();
            else builder.POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(requestBytes));
            // Exactly one application invocation; permitted JDK attempts reuse this immutable request.
            synchronized (lifecycle) {
                if (closed) return Result.notSent(Code.ACCESS_REFUSED);
                remaining(deadline);
                builder.header("Authorization", "Bearer " + new String(bearer));
                future = client.sendAsync(builder.build(), info -> {
                    status.set(info.statusCode()); headers.set(info.headers());
                    BoundedBody subscriber = new BoundedBody(); activeBody.set(subscriber);
                    if (cancelled.get()) subscriber.cancel();
                    return subscriber;
                });
            }
            HttpResponse<byte[]> response = future.get(remaining(deadline), TimeUnit.NANOSECONDS);
            Metadata metadata = metadata(response.headers(), key, requestId);
            int expected = operation == Operation.CREATE ? 201 : 200;
            if (response.statusCode() != expected) {
                Code code = errorCode(response.statusCode(), response.body(), response.headers(), metadata);
                remaining(deadline);
                return new Result(response.statusCode(), code, Observation.UNKNOWN, metadata, null);
            }
            requireJson(response.headers());
            JsonNode parsed = read(response.body());
            Document document = document(binding, sessionId, currency, parsed, response.body());
            remaining(deadline);
            return new Result(response.statusCode(), Code.OK, Observation.OBSERVED, metadata, document);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return failure(status.get(), Code.TRANSPORT_UNCERTAIN, headers.get(), key, requestId);
        } catch (TimeoutException error) {
            return failure(status.get(), Code.TRANSPORT_UNCERTAIN, headers.get(), key, requestId);
        } catch (ExecutionException error) {
            Code code = activeBody.get() != null && activeBody.get().overflow ? Code.INVALID_RESPONSE : Code.TRANSPORT_UNCERTAIN;
            return failure(status.get(), code, headers.get(), key, requestId);
        } catch (IllegalStateException error) {
            return failure(status.get(), Code.TRANSPORT_UNCERTAIN, headers.get(), key, requestId);
        } catch (IOException | IllegalArgumentException error) {
            return failure(status.get(), Code.INVALID_RESPONSE, headers.get(), key, requestId);
        } catch (RuntimeException error) {
            return failure(status.get(), Code.TRANSPORT_UNCERTAIN, headers.get(), key, requestId);
        } finally {
            cancelled.set(true);
            BoundedBody subscriber = activeBody.get();
            if (subscriber != null) subscriber.cancel();
            if (future != null && !future.isDone()) future.cancel(true);
        }
    }

    private Result failure(int status, Code code, HttpHeaders headers, String key, String requestId) {
        Metadata safe = Metadata.absent();
        if (headers != null) {
            try { safe = metadata(headers, key, requestId); }
            catch (IllegalArgumentException ignored) { code = Code.INVALID_RESPONSE; }
        }
        return new Result(status, code, Observation.UNKNOWN, safe, null);
    }
    private static long remaining(long deadline) throws TimeoutException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new TimeoutException();
        return nanos;
    }
    private Metadata metadata(HttpHeaders headers, String key, String requestId) {
        String echoedRequest = requestId == null ? null : single(headers, "Request-Id");
        String echoedKey = key == null ? null : single(headers, "Idempotency-Key");
        String replayed = single(headers, "Idempotent-Replayed");
        if (echoedKey != null && !echoedKey.equals(key)) invalidResponse();
        if (echoedRequest != null && !echoedRequest.equals(requestId)) invalidResponse();
        if (replayed != null && !replayed.equals("true")) invalidResponse();
        String retry = single(headers, "Retry-After");
        Long seconds = null;
        if (retry != null && retry.matches("[0-9]{1,5}")) {
            long parsed = Long.parseLong(retry);
            if (parsed <= 86400) seconds = parsed;
        }
        return new Metadata(echoedRequest != null, echoedKey != null, replayed != null, seconds);
    }
    private static String single(HttpHeaders headers, String name) {
        List<String> values = headers.allValues(name);
        if (values.size() > 1 || (!values.isEmpty() && values.getFirst().length() > 512)) invalidResponse();
        return values.isEmpty() ? null : values.getFirst();
    }
    private static void requireJson(HttpHeaders headers) {
        String contentType = single(headers, "Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) invalidResponse();
        String encoding = single(headers, "Content-Encoding");
        if (encoding != null && !encoding.equalsIgnoreCase("identity")) invalidResponse();
    }
    private JsonNode read(byte[] bytes) throws IOException {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) { throw new IOException("INVALID_CHECKOUT_RESPONSE"); }
        JsonNode node = json.readTree(text);
        if (node == null || !node.isObject()) invalidResponse();
        return node;
    }
    private Document document(Binding binding, String sessionId, String currency, JsonNode root, byte[] bytes) {
        root.fieldNames().forEachRemaining(name -> { if (!FIELDS.contains(name)) invalidResponse(); });
        String id = text(root, "id"), status = text(root, "status");
        if (!STATUSES.contains(status) || !text(root, "currency").equals(currency)) invalidResponse();
        if (sessionId != null && !sessionId.equals(id)) invalidResponse();
        for (String field : List.of("line_items", "totals", "fulfillment_options", "messages", "links"))
            if (!root.has(field) || !root.get(field).isArray()) invalidResponse();
        if (!root.has("capabilities") || !root.get("capabilities").isObject()) invalidResponse();
        String version = null;
        if (root.has("protocol")) {
            JsonNode protocol = root.get("protocol");
            if (!protocol.isObject() || protocol.size() != 1) invalidResponse();
            version = text(protocol, "version");
            if (!ACP_VERSION.equals(version)) invalidResponse();
        }
        return new Document(new Session(binding, id, currency), status, version, bytes);
    }
    private static String text(JsonNode node, String name) {
        if (!node.has(name) || !node.get(name).isTextual()) invalidResponse();
        return node.get(name).textValue();
    }
    private Code errorCode(int status, byte[] bytes, HttpHeaders headers, Metadata metadata) {
        if (status == 401 || status == 403) return Code.ACCESS_REFUSED;
        if (status == 404) return Code.NOT_FOUND;
        if (status == 405) return Code.NOT_CANCELABLE;
        if (status == 429) return Code.RATE_LIMITED;
        if (status >= 500) return Code.PEER_FAILURE;
        String code = "";
        try { requireJson(headers); JsonNode root = read(bytes); code = text(root, "code"); }
        catch (IOException | IllegalArgumentException ignored) { }
        if (status == 400 && code.equals("idempotency_key_required")) return Code.IDEMPOTENCY_REQUIRED;
        if (status == 409 && code.equals("idempotency_in_flight"))
            return metadata.retryAfterSeconds() == null ? Code.INVALID_RESPONSE : Code.IDEMPOTENCY_IN_FLIGHT;
        if (status == 422 && code.equals("idempotency_conflict")) return Code.IDEMPOTENCY_CONFLICT;
        return status >= 200 && status < 300 ? Code.INVALID_RESPONSE : Code.PEER_FAILURE;
    }
    private static void invalidConfiguration() { throw new IllegalArgumentException("INVALID_CHECKOUT_CONFIGURATION"); }
    private static void invalidResponse() { throw new IllegalArgumentException("INVALID_CHECKOUT_RESPONSE"); }

    /** Completes only after the complete bounded body, so get(deadline) covers post-header stalls. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private volatile boolean overflow;
        private boolean cancelled;
        @Override public CompletableFuture<byte[]> getBody() { return body; }
        @Override public synchronized void onSubscribe(Flow.Subscription subscription) {
            if (this.subscription != null || cancelled) { subscription.cancel(); return; }
            this.subscription = subscription; subscription.request(1);
        }
        @Override public synchronized void onNext(List<ByteBuffer> buffers) {
            if (cancelled || body.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_BODY - bytes.size()) {
                    overflow = true; cancel(); body.completeExceptionally(new IOException("CHECKOUT_BODY_TOO_LARGE")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            if (subscription != null) subscription.request(1);
        }
        @Override public synchronized void onError(Throwable error) { body.completeExceptionally(new IOException("CHECKOUT_BODY_UNAVAILABLE")); }
        @Override public synchronized void onComplete() { body.complete(bytes.toByteArray()); }
        synchronized void cancel() {
            cancelled = true;
            if (subscription != null) subscription.cancel();
            if (!body.isDone()) body.completeExceptionally(new IOException("CHECKOUT_BODY_CANCELLED"));
        }
    }

    @Override public void close() {
        synchronized (lifecycle) {
            closed = true; Arrays.fill(bearer, '\0'); client.shutdownNow();
        }
    }
    @Override public String toString() { return "AcpHttpCheckoutSessions[redacted]"; }
}
