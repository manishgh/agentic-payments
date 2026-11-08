package io.agenticpayments.http;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agenticpayments.application.PaymentException;
import io.agenticpayments.application.PaymentService;
import io.agenticpayments.application.CheckoutLifecycle;
import io.agenticpayments.application.PaymentService.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loopback-only development transport; no real payment or webhook ingress. */
public final class PaymentHttpServer implements AutoCloseable {
    private static final String PREFIX = "/internal/v0.1";
    private static final int MAX_BODY = 16 * 1024;
    private final PaymentService service;
    private final CheckoutLifecycle checkout;
    private final LocalSessions sessions;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);
    private final Path uiDirectory;
    private final ObjectMapper json = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    public PaymentHttpServer(PaymentService service, LocalSessions sessions, int port, Path uiDirectory) throws IOException {
        this(service, sessions, port, uiDirectory, null);
    }
    public PaymentHttpServer(PaymentService service, LocalSessions sessions, int port, Path uiDirectory, CheckoutLifecycle checkout) throws IOException {
        this.service = service;
        this.checkout = checkout;
        this.sessions = sessions;
        this.uiDirectory = uiDirectory;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 64);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }
    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (!("127.0.0.1:" + port()).equals(host) && !("localhost:" + port()).equals(host)) {
                error(exchange, 403, "FORBIDDEN", "Request origin is not allowed.", "NEVER"); return;
            }
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null && !origin.equals("http://" + host)) {
                error(exchange, 403, "FORBIDDEN", "Request origin is not allowed.", "NEVER"); return;
            }
            String path = exchange.getRequestURI().getRawPath();
            if (!path.startsWith(PREFIX + "/")) { staticFile(exchange, path); return; }
            if (exchange.getRequestURI().getRawQuery() != null) throw new IllegalArgumentException();
            if (exchange.getRequestHeaders().getOrDefault("Authorization", java.util.List.of()).size() != 1) {
                error(exchange, 401, "UNAUTHENTICATED", "Authentication is required.", "NEVER"); return;
            }
            Session session = sessions.authenticate(exchange.getRequestHeaders().getFirst("Authorization"));
            if (session == null) { error(exchange, 401, "UNAUTHENTICATED", "Authentication is required.", "NEVER"); return; }
            Result result = route(exchange, path.substring(PREFIX.length()), session);
            if (result.replayed()) exchange.getResponseHeaders().set("Idempotency-Replayed", "true");
            send(exchange, result.status(), "application/json; charset=utf-8", json.writeValueAsBytes(result.body()));
        } catch (PaymentException ex) {
            error(exchange, ex.status(), ex.code(), ex.getMessage(), ex.retry());
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JacksonException ex) {
            error(exchange, 400, "INVALID_REQUEST", "The request does not match the contract.", "NEVER");
        } catch (Exception ex) {
            // Do not log exceptions: database and transport messages can contain configuration or payloads.
            error(exchange, 503, "TEMPORARILY_UNAVAILABLE", "The service is temporarily unavailable.", "SAME_COMMAND");
        } finally { exchange.close(); }
    }

    private Result route(HttpExchange exchange, String path, Session session) throws IOException {
        String method = exchange.getRequestMethod();
        String[] segments = path.split("/", -1);
        if (method.equals("GET") && segments.length >= 3) {
            if (segments[1].equals("checkout-purchases") && segments.length == 3)
                return checkoutResult(checkout().status(session, id(segments[2])));
            if (segments[1].equals("intents") && segments.length == 3) return service.status(session, id(segments[2]));
            if (segments[1].equals("intents") && segments.length == 4 && segments[3].equals("checkout")) return service.checkout(session, id(segments[2]));
            if (segments[1].equals("actions") && segments.length == 3) return service.action(session, id(segments[2]));
        }
        if (method.equals("POST")) {
            String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            if (key == null || !key.matches("[\\x20-\\x7E]{16,128}") || exchange.getRequestHeaders().get("Idempotency-Key").size() != 1) throw new IllegalArgumentException();
            String type = exchange.getRequestHeaders().getFirst("Content-Type");
            if (type == null || !type.toLowerCase(java.util.Locale.ROOT).matches("application/json(?:\\s*;\\s*charset=utf-8)?")) throw new IllegalArgumentException();
            byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY || body.length == 0) throw new IllegalArgumentException();
            if (segments.length == 4 && segments[1].equals("checkout-purchases") && segments[3].equals("commands"))
                return checkoutResult(checkout().stage(session, id(segments[2]), key, parse(body, CheckoutLifecycle.StageCommand.class)));
            if (segments.length == 6 && segments[1].equals("checkout-purchases") && segments[3].equals("commands")) {
                parse(body, CheckoutSignal.class);
                UUID purchase = id(segments[2]), command = id(segments[4]);
                return switch (segments[5]) {
                    case "dispatch" -> checkoutResult(checkout().dispatch(session, purchase, command));
                    case "recover-claim" -> checkoutResult(checkout().recoverClaim(session, purchase, command));
                    default -> missing();
                };
            }
            if (path.equals("/intents")) return service.create(session, key, parse(body, CreateCommand.class));
            if (segments.length == 4 && segments[1].equals("intents")) {
                UUID intent = id(segments[2]);
                return switch (segments[3]) {
                    case "quote" -> service.quote(session, intent, key, parse(body, QuoteCommand.class));
                    case "quote-external" -> service.quoteExternal(session, intent, key, parse(body, ExternalQuoteCommand.class));
                    case "approve" -> service.approve(session, intent, key, parse(body, ApproveCommand.class));
                    case "execute" -> service.execute(session, intent, key, parse(body, ExecuteCommand.class));
                    default -> missing();
                };
            }
            if (segments.length == 4 && segments[1].equals("actions") && segments[3].equals("complete")) return service.completeAction(session, id(segments[2]), key, parse(body, CompleteActionCommand.class));
            if (segments.length == 4 && segments[1].equals("authorities") && segments[3].equals("revoke") && segments[2].matches("[a-z0-9][a-z0-9-]{0,63}")) return service.revoke(session, segments[2], key, parse(body, RevokeCommand.class));
        }
        return missing();
    }
    private record CheckoutSignal() {}
    private CheckoutLifecycle checkout() {
        if (checkout == null) throw new PaymentException(422, "UNSUPPORTED_CAPABILITY");
        return checkout;
    }
    private Result checkoutResult(CheckoutLifecycle.SafeView view) {
        Map<String,Object> body = json.convertValue(view, new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
        return new Result(200, body, false);
    }
    private static UUID id(String value) {
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalArgumentException();
        return id;
    }
    private <T> T parse(byte[] bytes, Class<T> type) throws IOException {
        String document;
        try {
            document = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException invalidEncoding) {
            throw new IllegalArgumentException();
        }
        var tree = json.readTree(document);
        if (tree == null || !tree.isObject()) throw new IllegalArgumentException();
        validateTree(tree, "");
        return json.treeToValue(tree, type);
    }
    private void validateTree(com.fasterxml.jackson.databind.JsonNode node, String field) {
        if (node.isNull()) throw new IllegalArgumentException();
        if (node.isObject()) {
            node.properties().forEach(entry -> validateTree(entry.getValue(), entry.getKey()));
        } else if (node.isArray()) {
            node.forEach(value -> validateTree(value, field));
        } else if (java.util.Set.of("attemptId", "purchaseId", "responseReference").contains(field)) {
            if (!node.isTextual()) throw new IllegalArgumentException();
            id(node.textValue());
        } else if (field.equals("checkoutDigest")) {
            if (!node.isTextual() || !node.textValue().matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException();
        } else if (java.util.Set.of("merchantId", "scenarioId", "cartFixtureId", "provider", "method", "credentialReference", "authorityReference", "requiredCapabilities").contains(field)) {
            if (!node.isTextual() || !node.textValue().matches("[a-z0-9][a-z0-9-]{0,63}")) throw new IllegalArgumentException();
        }
    }
    private Result missing() {
        return new Result(404, envelope("RESOURCE_NOT_FOUND", "Resource not found.", "NEVER"), false);
    }
    private static Map<String, Object> envelope(String code, String message, String retry) {
        return Map.of("contractVersion", "simulator-api-0.1-draft", "correlationId", UUID.randomUUID().toString(), "error", Map.of("code", code, "message", message, "retry", retry));
    }
    private void error(HttpExchange exchange, int status, String code, String message, String retry) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", json.writeValueAsBytes(envelope(code, message, retry)));
    }
    private void staticFile(HttpExchange exchange, String path) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) { error(exchange, 404, "RESOURCE_NOT_FOUND", "Resource not found.", "NEVER"); return; }
        String file = switch (path) { case "/" -> "index.html"; case "/app.js" -> "dist/app.js"; case "/style.css" -> "style.css"; default -> null; };
        if (file == null || uiDirectory == null || !Files.isRegularFile(uiDirectory.resolve(file))) { error(exchange, 404, "RESOURCE_NOT_FOUND", "Resource not found.", "NEVER"); return; }
        String type = path.endsWith(".js") ? "text/javascript" : path.endsWith(".css") ? "text/css" : "text/html";
        send(exchange, 200, type + "; charset=utf-8", Files.readAllBytes(uiDirectory.resolve(file)));
    }
    private static void send(HttpExchange exchange, int status, String type, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
