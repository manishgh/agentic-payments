package io.agenticpayments.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agenticpayments.application.*;
import io.agenticpayments.application.PaymentService.Session;
import io.agenticpayments.http.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class HttpApplicationFailureBoundaryTest {
    private static final String CANARY = "private-customer-token-canary";
    private static final String TOKEN = "synthetic-local-session-token-0000000000000000";
    private static final String CREATE = """
            {"merchantId":"merchant-demo","scenarioId":"success","profile":"local-fake-single-tender-0.1","requiredCapabilities":[]}
            """;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void businessIllegalArgumentExceptionRemainsUnexpected503AndInvokesApplicationOnce() throws Exception {
        try (var fixture = new Fixture(new IllegalArgumentException(CANARY))) {
            var response = fixture.send("POST", "/intents", CREATE, "application/json", "synthetic-command-key-0001");
            failure(response, 503, "TEMPORARILY_UNAVAILABLE", "The service is temporarily unavailable.", "SAME_COMMAND");
            assertEquals(1, fixture.calls.get());
        }
    }

    @Test void malformedBodyPathQueryAndHeadersFailBeforeAnyBusinessInvocation() throws Exception {
        try (var fixture = new Fixture(new IllegalArgumentException(CANARY))) {
            for (String body : List.of("{", "[]", "null", "{\"private\":\"" + CANARY + "\"}",
                    CREATE.replace("\"merchant-demo\"", "42"),
                    CREATE.replace("\"merchantId\":\"merchant-demo\"", "\"merchantId\":\"merchant-demo\",\"merchantId\":\"merchant-demo\""))) {
                failure(fixture.send("POST", "/intents", body, "application/json", "synthetic-command-key-0001"),
                        400, "INVALID_REQUEST", "The request does not match the contract.", "NEVER");
            }
            for (String path : List.of("/intents/not-a-uuid", "/intents/AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA", "/intents/" + UUID.randomUUID() + "?private=" + CANARY)) {
                failure(fixture.send("GET", path, null, null, null),
                        400, "INVALID_REQUEST", "The request does not match the contract.", "NEVER");
            }
            failure(fixture.send("POST", "/intents", CREATE, "text/plain", "synthetic-command-key-0001"),
                    400, "INVALID_REQUEST", "The request does not match the contract.", "NEVER");
            failure(fixture.send("POST", "/intents", CREATE, "application/json", "short"),
                    400, "INVALID_REQUEST", "The request does not match the contract.", "NEVER");
            assertEquals(0, fixture.calls.get());
        }
    }

    @Test void baseApplicationExceptionAndReplacementSubclassUseOnlyCatalogWireFields() throws Exception {
        try (var fixture = new Fixture(new ApplicationException(ApplicationFailure.IDEMPOTENCY_CONFLICT))) {
            failure(fixture.send("GET", "/intents/" + UUID.randomUUID(), null, null, null),
                    409, "IDEMPOTENCY_CONFLICT", "idempotency conflict", "NEVER");
            assertEquals(1, fixture.calls.get());
            fixture.problem.set(new ReplacementApplicationException());
            failure(fixture.send("GET", "/intents/" + UUID.randomUUID(), null, null, null),
                    410, "AUTHORITY_EXPIRED", "authority expired", "NEVER");
            assertEquals(2, fixture.calls.get());
        }
    }

    private static final class ReplacementApplicationException extends ApplicationException {
        ReplacementApplicationException() { super(ApplicationFailure.AUTHORITY_EXPIRED); }
        @Override public String getMessage() { return CANARY; }
    }

    private static void failure(HttpResponse<String> response, int status, String code, String message, String retry) throws Exception {
        assertEquals(status, response.statusCode());
        assertFalse(response.body().contains(CANARY));
        assertFalse(response.body().contains("IllegalArgumentException"));
        assertFalse(response.body().contains("ReplacementApplicationException"));
        JsonNode body = JSON.readTree(response.body());
        assertEquals(Set.of("contractVersion", "correlationId", "error"), fields(body));
        assertEquals("simulator-api-0.1-draft", body.path("contractVersion").asText());
        String correlation = body.path("correlationId").asText();
        assertEquals(correlation, UUID.fromString(correlation).toString());
        assertEquals(JSON.valueToTree(Map.of("code", code, "message", message, "retry", retry)), body.path("error"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }
    private static Set<String> fields(JsonNode node) {
        var names = new HashSet<String>(); node.fieldNames().forEachRemaining(names::add); return names;
    }

    private static final class Fixture implements AutoCloseable {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<RuntimeException> problem;
        final PaymentHttpServer server;
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        Fixture(RuntimeException failure) throws Exception {
            problem = new AtomicReference<>(failure);
            PaymentOperations operations = (PaymentOperations) Proxy.newProxyInstance(PaymentOperations.class.getClassLoader(),
                    new Class<?>[]{PaymentOperations.class}, (proxy, method, args) -> {
                        calls.incrementAndGet(); throw problem.get();
                    });
            var session = new Session("http-services-test", UUID.randomUUID(), "synthetic-subject", Set.of(), Set.of(), Set.of());
            server = new PaymentHttpServer(operations, new LocalSessions(List.of(new LocalSessions.Entry(TOKEN, session))), 0, null);
            server.start();
        }
        HttpResponse<String> send(String method, String path, String body, String contentType, String key) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/internal/v0.1" + path))
                    .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + TOKEN);
            if (contentType != null) request.header("Content-Type", contentType);
            if (key != null) request.header("Idempotency-Key", key);
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
        @Override public void close() { client.close(); server.close(); }
    }
}
