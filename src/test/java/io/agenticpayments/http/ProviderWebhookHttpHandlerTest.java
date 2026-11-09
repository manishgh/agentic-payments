package io.agenticpayments.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agenticpayments.application.ApplicationException;
import io.agenticpayments.application.ApplicationFailure;
import io.agenticpayments.application.PaymentOperations;
import io.agenticpayments.application.PaymentService.Session;
import io.agenticpayments.application.ProviderEventAcceptance;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class ProviderWebhookHttpHandlerTest {
    private static final String TOKEN = "synthetic-webhook-token-000000000000000000";
    private static final String OTHER_TOKEN = "synthetic-ui-token-0000000000000000000000";
    private static final String SIGNATURE = "ab".repeat(32);
    private static final UUID INTENT = UUID.fromString("11111111-aaaa-4444-8888-111111111111");
    private static final String PATH = ProviderWebhookHttpHandler.PREFIX + INTENT;
    private static final String CANARY = "private-body-token-reference-canary";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Session SESSION = new Session("webhook-tenant", UUID.randomUUID(), "synthetic-provider",
            Set.of("runner:write"), Set.of("merchant-demo"), Set.of());

    @Test void preservesExactBytesAndUsesOnlyTrustedAuthenticationAndCanonicalRoute() throws Exception {
        byte[] raw = new byte[]{' ', '{', '\n', (byte) 0xff, '}', '\r', '\n'};
        var observed = new AtomicReference<byte[]>();
        try (var fixture = new Fixture((session, intent, body, signature) -> {
            assertSame(SESSION, session);
            assertEquals(INTENT, intent);
            assertEquals(SIGNATURE, signature);
            observed.set(body.clone());
            return CANARY;
        })) {
            var response = fixture.send("POST", PATH, raw, b -> { });
            assertEquals(202, response.statusCode());
            assertArrayEquals(raw, observed.get());
            assertEquals(JSON.readTree("{\"contractVersion\":\"synthetic-provider-webhook-1\",\"accepted\":true}"), JSON.readTree(response.body()));
            assertFalse(response.body().contains(CANARY));
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals(1, fixture.calls.get());
        }
    }

    @Test void waitsForDurableAcceptanceBeforeReturning202() throws Exception {
        var entered = new CountDownLatch(1);
        var committed = new CountDownLatch(1);
        try (var fixture = new Fixture((session, intent, body, signature) -> {
            entered.countDown();
            try {
                if (!committed.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Acceptance test deadline");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return "retained-reference";
        })) {
            var future = fixture.client.sendAsync(fixture.request("POST", PATH, new byte[]{'{', '}'}, b -> { }), HttpResponse.BodyHandlers.ofString());
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertFalse(future.isDone());
            } finally { committed.countDown(); }
            assertEquals(202, future.get(5, TimeUnit.SECONDS).statusCode());
            assertEquals(1, fixture.calls.get());
        }
    }

    @Test void rejectsUnauthenticatedAndAmbiguousHeadersBeforeApplicationWork() throws Exception {
        try (var fixture = new Fixture((s, i, b, h) -> "reference")) {
            assertEquals(401, fixture.send("POST", PATH, new byte[]{1}, b -> b.setHeader("Authorization", "Bearer " + OTHER_TOKEN)).statusCode());
            assertEquals(401, fixture.send("POST", PATH, new byte[]{1}, b -> b.setHeader("Authorization", "")).statusCode());
            assertEquals(401, fixture.send("POST", PATH, new byte[]{1}, b -> b.header("Authorization", "Bearer " + TOKEN)).statusCode());
            assertEquals(400, fixture.send("POST", PATH, new byte[]{1}, b -> b.header("X-Synthetic-Event-Signature", SIGNATURE)).statusCode());
            assertEquals(400, fixture.send("POST", PATH, new byte[]{1}, b -> b.setHeader("X-Synthetic-Event-Signature", "")).statusCode());
            assertEquals(400, fixture.send("POST", PATH, new byte[]{1}, b -> b.setHeader("X-Synthetic-Event-Signature", SIGNATURE.toUpperCase())).statusCode());
            assertEquals(415, fixture.send("POST", PATH, new byte[]{1}, b -> b.header("Content-Type", "application/json")).statusCode());
            assertEquals(403, fixture.send("POST", PATH, new byte[]{1}, b -> b.header("Origin", "https://untrusted.example.invalid")).statusCode());
            assertEquals(0, fixture.calls.get());
        }
    }

    @Test void rejectsAmbiguousHostOriginAndLengthThroughTheSharedServer() throws Exception {
        try (var fixture = new Fixture((s, i, b, h) -> "reference")) {
            String host = "127.0.0.1:" + fixture.server.port();
            // The JDK may reject duplicate Host framing before dispatching to the handler.
            assertTrue(Set.of(400, 403).contains(fixture.raw("Host: " + host + "\r\nHost: " + host + "\r\nContent-Length: 2\r\n")));
            assertEquals(403, fixture.raw("Host: " + host + "\r\nOrigin: http://" + host + "\r\nOrigin: http://" + host + "\r\nContent-Length: 2\r\n"));
            assertEquals(400, fixture.raw("Host: " + host + "\r\nContent-Length: 02\r\n"));
            // JDK framing or the handler must reject this before application acceptance.
            assertEquals(400, fixture.raw("Host: " + host + "\r\nContent-Length: 2\r\nContent-Length: 2\r\n"));
            assertEquals(0, fixture.calls.get());
        }
    }

    @Test void enforcesCanonicalRouteMethodMediaAndBodyBounds() throws Exception {
        try (var fixture = new Fixture((s, i, b, h) -> "reference")) {
            for (String path : List.of(PATH + "?account=untrusted", PATH + "/", PATH.replace("aaaa", "AAAA"), PATH + "%2f", ProviderWebhookHttpHandler.PREFIX + "not-an-intent"))
                assertEquals(400, fixture.send("POST", path, new byte[]{1}, b -> { }).statusCode());
            var method = fixture.send("GET", PATH, new byte[]{1}, b -> { });
            assertEquals(405, method.statusCode());
            assertEquals("POST", method.headers().firstValue("Allow").orElseThrow());
            assertEquals(415, fixture.send("POST", PATH, new byte[]{1}, b -> b.setHeader("Content-Type", "text/plain")).statusCode());
            assertEquals(415, fixture.send("POST", PATH, new byte[]{1}, b -> b.header("Content-Encoding", "gzip")).statusCode());
            assertEquals(400, fixture.send("POST", PATH, new byte[0], b -> { }).statusCode());
            assertEquals(413, fixture.send("POST", PATH, new byte[16385], b -> { }).statusCode());
            assertEquals(0, fixture.calls.get());
            assertEquals(202, fixture.send("POST", PATH, new byte[16384], b -> b.setHeader("Content-Type", "application/json; charset=utf-8")).statusCode());
            assertEquals(1, fixture.calls.get());
            assertEquals(202, fixture.send("POST", PATH, new byte[]{1}, b -> b.setHeader("Content-Type", "application/json\t;\tcharset=utf-8")).statusCode());
            assertEquals(2, fixture.calls.get());
            var chunked = HttpRequest.newBuilder(fixture.uri(PATH)).timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + TOKEN).header("X-Synthetic-Event-Signature", SIGNATURE)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(new byte[16385]))).build();
            assertEquals(413, fixture.client.send(chunked, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(2, fixture.calls.get());
        }
    }

    @Test void neverAcknowledgesFailedAcceptanceAndSanitizesReplacements() throws Exception {
        var problem = new AtomicReference<RuntimeException>(new IllegalArgumentException(CANARY));
        try (var fixture = new Fixture((s, i, b, h) -> { throw problem.get(); })) {
            var response = fixture.send("POST", PATH, CANARY.getBytes(StandardCharsets.UTF_8), b -> { });
            assertEquals(503, response.statusCode());
            assertEquals("SAME_COMMAND", JSON.readTree(response.body()).path("error").path("retry").asText());
            assertFalse(response.body().contains(CANARY));
            assertFalse(response.body().contains(SIGNATURE));
            assertFalse(response.body().contains(TOKEN));
            problem.set(new ApplicationException(ApplicationFailure.FORBIDDEN));
            assertEquals(403, fixture.send("POST", PATH, new byte[]{1}, b -> { }).statusCode());
            assertEquals(2, fixture.calls.get());
        }
    }

    @Test void endpointRequiresExplicitCompleteOptIn() throws Exception {
        PaymentOperations operations = unusedPayments();
        var sessions = new LocalSessions(List.of());
        try (var server = new PaymentHttpServer(operations, sessions, 0, null); var client = HttpClient.newHttpClient()) {
            server.start();
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + PATH)).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            assertEquals(404, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        assertThrows(IllegalArgumentException.class, () -> new PaymentHttpServer(operations, sessions, 0, null, null, (s, i, b, h) -> "reference", null));
    }

    private static PaymentOperations unusedPayments() {
        return (PaymentOperations) Proxy.newProxyInstance(PaymentOperations.class.getClassLoader(), new Class<?>[]{PaymentOperations.class},
                (proxy, method, args) -> { throw new AssertionError("Webhook must not call payment operations"); });
    }
    private static final class Fixture implements AutoCloseable {
        final AtomicInteger calls = new AtomicInteger();
        final PaymentHttpServer server;
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        Fixture(ProviderEventAcceptance acceptance) throws Exception {
            server = new PaymentHttpServer(unusedPayments(), new LocalSessions(List.of()), 0, null, null,
                    (s, i, b, h) -> { calls.incrementAndGet(); return acceptance.accept(s, i, b, h); },
                    value -> value.equals("Bearer " + TOKEN) ? SESSION : null);
            server.start();
        }
        URI uri(String path) { return URI.create("http://127.0.0.1:" + server.port() + path); }
        HttpRequest request(String method, String path, byte[] body, Consumer<HttpRequest.Builder> customize) {
            var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + TOKEN).header("X-Synthetic-Event-Signature", SIGNATURE)
                    .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofByteArray(body));
            customize.accept(builder);
            return builder.build();
        }
        HttpResponse<String> send(String method, String path, byte[] body, Consumer<HttpRequest.Builder> customize) throws Exception {
            return client.send(request(method, path, body, customize), HttpResponse.BodyHandlers.ofString());
        }
        int raw(String headers) throws Exception {
            try (var socket = new Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(5000);
                String request = "POST " + PATH + " HTTP/1.1\r\n" + headers
                        + "Authorization: Bearer " + TOKEN + "\r\nX-Synthetic-Event-Signature: " + SIGNATURE
                        + "\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n{}";
                socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                return Integer.parseInt(response.split(" ", 3)[1]);
            }
        }
        @Override public void close() { client.close(); server.close(); }
    }
}
