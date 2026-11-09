package io.agenticpayments.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.agenticpayments.application.ApplicationException;
import io.agenticpayments.application.ProviderEventAcceptance;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Bounded synthetic loopback ingress. Neither parses event bytes nor dispatches a payment. */
public final class ProviderWebhookHttpHandler implements HttpHandler {
    public static final String PREFIX = "/synthetic/provider-events/v1/";
    public static final String CONTRACT = "synthetic-provider-webhook-1";
    private static final int MAX_BODY = 16 * 1024;
    private final ProviderEventAcceptance acceptance;
    private final ProviderWebhookAuthentication authentication;
    private final ObjectMapper json = new ObjectMapper();

    public ProviderWebhookHttpHandler(ProviderEventAcceptance acceptance, ProviderWebhookAuthentication authentication) {
        this.acceptance = Objects.requireNonNull(acceptance);
        this.authentication = Objects.requireNonNull(authentication);
    }

    @Override public void handle(HttpExchange exchange) throws IOException {
        try {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            Reply reply;
            try {
                reply = receive(exchange);
            } catch (Rejected rejected) {
                reply = failure(rejected.status, rejected.code, rejected.message, "NEVER");
            } catch (ApplicationException failure) {
                var mapped = HttpFailureMapper.map(failure);
                reply = failure(mapped.status(), mapped.code(), mapped.message(), mapped.retry());
            } catch (Exception unexpected) {
                var mapped = HttpFailureMapper.unexpected();
                reply = failure(mapped.status(), mapped.code(), mapped.message(), mapped.retry());
            }
            // Response failure must never rerun acceptance after its durable commit.
            byte[] bytes = json.writeValueAsBytes(reply.body());
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
        } finally {
            exchange.close();
        }
    }

    private Reply receive(HttpExchange exchange) throws IOException {
        String host = single(exchange, "Host", 403, "FORBIDDEN");
        int port = exchange.getLocalAddress().getPort();
        if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()
                || !(host.equals("127.0.0.1:" + port) || host.equals("localhost:" + port)))
            throw rejected(403, "FORBIDDEN");
        List<String> origins = values(exchange, "Origin");
        if (origins.size() > 1 || origins.size() == 1 && !origins.getFirst().equals("http://" + host))
            throw rejected(403, "FORBIDDEN");
        String path = exchange.getRequestURI().getRawPath();
        if (!path.startsWith(PREFIX)) throw rejected(404, "RESOURCE_NOT_FOUND");
        if (exchange.getRequestURI().getRawQuery() != null || exchange.getRequestURI().getRawFragment() != null)
            throw rejected(400, "INVALID_REQUEST");
        UUID intent;
        try {
            String value = path.substring(PREFIX.length());
            intent = UUID.fromString(value);
            if (!intent.toString().equals(value)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw rejected(400, "INVALID_REQUEST");
        }
        if (!exchange.getRequestMethod().equals("POST")) {
            exchange.getResponseHeaders().set("Allow", "POST");
            throw rejected(405, "METHOD_NOT_ALLOWED");
        }
        String authorization = single(exchange, "Authorization", 401, "UNAUTHENTICATED");
        if (authorization.length() > 512 || !authorization.matches("Bearer [A-Za-z0-9_-]{32,505}"))
            throw rejected(401, "UNAUTHENTICATED");
        var session = authentication.authenticate(authorization);
        if (session == null) throw rejected(401, "UNAUTHENTICATED");
        String signature = single(exchange, "X-Synthetic-Event-Signature", 400, "INVALID_REQUEST");
        if (!signature.matches("[0-9a-f]{64}")) throw rejected(400, "INVALID_REQUEST");
        String type = single(exchange, "Content-Type", 415, "UNSUPPORTED_MEDIA_TYPE");
        if (type.length() > 80 || !type.toLowerCase(Locale.ROOT).matches("application/json(?:[ \\t]*;[ \\t]*charset=utf-8)?"))
            throw rejected(415, "UNSUPPORTED_MEDIA_TYPE");
        if (!values(exchange, "Content-Encoding").isEmpty()) throw rejected(415, "UNSUPPORTED_MEDIA_TYPE");
        if (!values(exchange, "Trailer").isEmpty()) throw rejected(400, "INVALID_REQUEST");
        List<String> lengths = values(exchange, "Content-Length");
        List<String> transfers = values(exchange, "Transfer-Encoding");
        if (lengths.size() > 1 || transfers.size() > 1 || !lengths.isEmpty() && !transfers.isEmpty()
                || !transfers.isEmpty() && !transfers.getFirst().equalsIgnoreCase("chunked"))
            throw rejected(400, "INVALID_REQUEST");
        Integer declared = null;
        if (!lengths.isEmpty()) {
            String length = lengths.getFirst();
            if (!length.matches("0|[1-9][0-9]{0,18}")) throw rejected(400, "INVALID_REQUEST");
            if (length.length() > 5 || Long.parseLong(length) > MAX_BODY) throw rejected(413, "PAYLOAD_TOO_LARGE");
            declared = Integer.parseInt(length);
        }
        byte[] rawBody = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
        if (rawBody.length > MAX_BODY) throw rejected(413, "PAYLOAD_TOO_LARGE");
        if (rawBody.length == 0 || declared != null && declared != rawBody.length) throw rejected(400, "INVALID_REQUEST");
        Objects.requireNonNull(acceptance.accept(session, intent, rawBody, signature));
        return new Reply(202, Map.of("contractVersion", CONTRACT, "accepted", true));
    }

    private static List<String> values(HttpExchange exchange, String header) {
        return exchange.getRequestHeaders().getOrDefault(header, List.of());
    }
    private static String single(HttpExchange exchange, String header, int status, String code) {
        var values = values(exchange, header);
        if (values.size() != 1) throw rejected(status, code);
        return values.getFirst();
    }
    private static Rejected rejected(int status, String code) {
        return new Rejected(status, code, "The request does not match the synthetic webhook contract.");
    }
    private static Reply failure(int status, String code, String message, String retry) {
        return new Reply(status, Map.of("contractVersion", CONTRACT, "correlationId", UUID.randomUUID().toString(),
                "error", Map.of("code", code, "message", message, "retry", retry)));
    }
    private record Reply(int status, Map<String, Object> body) { }
    private static final class Rejected extends RuntimeException {
        final int status;
        final String code;
        final String message;
        Rejected(int status, String code, String message) {
            this.status = status; this.code = code; this.message = message;
        }
    }
}
