package io.agenticpayments.http;

import io.agenticpayments.application.ProviderEventDeliveryRuntime;
import io.agenticpayments.application.PaymentService.Session;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL host startup with replaceable acceptance and scheduling boundaries. */
class LocalPlatformWebhookHostIntegrationTest {
    private static final String TOKEN = "synthetic-host-webhook-token-00000000000000";

    @Test void explicitHostingStartsOnceAndStopsIngressBeforeRuntimeShutdown() throws Exception {
        var starts = new AtomicInteger();
        var closes = new AtomicInteger();
        var port = new AtomicInteger();
        var ingressStopped = new AtomicBoolean();
        var accepted = new AtomicInteger();
        var config = configuration(database());
        ProviderEventDeliveryRuntime runtime = new ProviderEventDeliveryRuntime() {
            @Override public void start() { starts.incrementAndGet(); }
            @Override public void close() {
                closes.incrementAndGet();
                try (var socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", port.get()), 1000);
                } catch (IOException expected) { ingressStopped.set(true); }
            }
        };
        var webhook = new ProviderWebhookHosting((session, intent, body, signature) -> {
            assertSame(config.runner(), session);
            accepted.incrementAndGet();
            return "synthetic-retained-reference";
        }, authorization -> authorization.equals("Bearer " + TOKEN) ? config.runner() : null, runtime);
        var host = new LocalPlatformHost(config, LocalTelemetry.disabled(), registrations -> { }, webhook);
        assertEquals(0, starts.get());
        try (var client = HttpClient.newHttpClient()) {
            host.start();
            host.start();
            port.set(host.port());
            assertEquals(1, starts.get());
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + host.port()
                    + ProviderWebhookHttpHandler.PREFIX + UUID.randomUUID())).timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + TOKEN).header("Content-Type", "application/json")
                    .header("X-Synthetic-Event-Signature", "0".repeat(64))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            assertEquals(202, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(1, accepted.get());
        } finally { host.close(); }
        host.close();
        assertEquals(1, closes.get());
        assertTrue(ingressStopped.get());
        assertThrows(IllegalStateException.class, host::start);
    }

    @Test void runtimeStartFailureUnwindsOwnedRuntimeAndNeverPublishesRunningHost() {
        var starts = new AtomicInteger();
        var closes = new AtomicInteger();
        var failure = new IllegalStateException("synthetic runtime start failure");
        var runtime = new ProviderEventDeliveryRuntime() {
            @Override public void start() { starts.incrementAndGet(); throw failure; }
            @Override public void close() { closes.incrementAndGet(); }
        };
        var host = new LocalPlatformHost(configuration(database()), LocalTelemetry.disabled(), registrations -> { },
                new ProviderWebhookHosting((s, i, b, h) -> "reference", auth -> null, runtime));
        assertSame(failure, assertThrows(IllegalStateException.class, host::start));
        assertEquals(1, starts.get());
        assertEquals(1, closes.get());
        assertThrows(IllegalStateException.class, host::port);
        host.close();
        assertEquals(1, closes.get());
    }

    @Test void failedMigrationReleasesRuntimeWithoutStartingItAndCloseBeforeStartIsIdempotent() {
        DataSource unavailable = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> { throw new SQLException("synthetic unavailable database"); });
        var starts = new AtomicInteger();
        var closes = new AtomicInteger();
        for (boolean startHost : List.of(false, true)) {
            var runtime = new ProviderEventDeliveryRuntime() {
                @Override public void start() { starts.incrementAndGet(); }
                @Override public void close() { closes.incrementAndGet(); }
            };
            var host = new LocalPlatformHost(configuration(unavailable), LocalTelemetry.disabled(), registrations -> { },
                    new ProviderWebhookHosting((s, i, b, h) -> "reference", auth -> null, runtime));
            if (startHost) assertThrows(IllegalStateException.class, host::start);
            host.close();
            host.close();
        }
        assertEquals(0, starts.get());
        assertEquals(2, closes.get());
    }

    private static DataSource database() {
        String url = System.getenv("PAYMENTS_DB_URL");
        if (url == null) throw new IllegalStateException("PostgreSQL integration-test environment is required");
        var database = new PGSimpleDataSource();
        database.setURL(url);
        database.setUser(System.getenv("PAYMENTS_DB_USER"));
        database.setPassword(System.getenv("PAYMENTS_DB_PASSWORD"));
        return database;
    }
    private static LocalPlatformHost.Configuration configuration(DataSource database) {
        var run = UUID.randomUUID();
        var runner = new Session("webhook-host-test", run, "customer-demo", Set.of("runner:write"), Set.of("merchant-demo"), Set.of());
        var graph = new Session(runner.tenantId(), run, "agent-demo", Set.of("intent:read"), Set.of("merchant-demo"), Set.of());
        return new LocalPlatformHost.Configuration(database, runner, graph, new LocalSessions(List.of()), 0, Path.of("ui"), null, false);
    }
}
