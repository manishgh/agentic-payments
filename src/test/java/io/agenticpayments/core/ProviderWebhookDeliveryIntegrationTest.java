package io.agenticpayments.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agenticpayments.application.ProviderExecutionBinding;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.application.instrumentation.ServiceTelemetry;
import io.agenticpayments.http.*;
import io.agenticpayments.infrastructure.*;
import io.agenticpayments.infrastructure.provider.*;
import io.agenticpayments.sdk.*;
import io.agenticpayments.sdk.execution.v1.ExecutionEvidence;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import static io.agenticpayments.core.CoreTestSupport.*;
import static io.agenticpayments.core.ProviderEventAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP acceptance and host-owned background delivery converge through the same payment store. */
class ProviderWebhookDeliveryIntegrationTest {
    private static final String EVENT_TOKEN = "synthetic-webhook-e2e-token-0000000000000000";
    private static final String READ_TOKEN = "synthetic-status-e2e-token-00000000000000000";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void webhookAutomaticallySettlesOriginalPendingPaymentAndDuplicateCannotResend() throws Exception {
        var database = new SubmissionCountingDataSource(database());
        var session = session("webhook-delivery-e2e", UUID.randomUUID());
        var registration = registration(session);
        var services = PaymentServices.providerEventDelivery(database, registration, eventConfiguration(registration),
                new HmacProviderEventAuthenticator(EVENT_KEY), session);
        var telemetry = LocalTelemetry.disabled();
        var delivery = new ScheduledProviderEventDelivery(services.delivery(),
                new ServiceTelemetry(telemetry.loggers(), telemetry.meters(), System::nanoTime), Duration.ofMillis(20), Duration.ofSeconds(2));
        var readSessions = new LocalSessions(List.of(new LocalSessions.Entry(READ_TOKEN, session)));
        var eventSessions = new LocalSessions(List.of(new LocalSessions.Entry(EVENT_TOKEN, session)));
        var configuration = new LocalPlatformHost.Configuration(database, session, session, readSessions, 0, Path.of("ui"), null, false);
        var hosting = new ProviderWebhookHosting(services.acceptance(), eventSessions::authenticate, delivery);
        try (var host = new LocalPlatformHost(configuration, telemetry,
                registrations -> registrations.replace(LocalPlatformHost.STORE).instance(LocalPlatformHost.STORE, services.store()), hosting);
             var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            host.start();
            var payments = services.payments();
            var created = payments.create(session, key(), new CreateCommand("merchant-demo", "delayed-success",
                    JdbcPaymentStore.NATIVE_PROFILE, List.of("single-tender", "exact-money")));
            UUID intent = uuid(created.body().get("intentId"));
            var quote = payments.quote(session, intent, key(), new QuoteCommand(version(created), "cart-1250-eur",
                    List.of(new Tender("simulator", "synthetic-card", "fixture-credential-1"))));
            var approved = payments.approve(session, intent, key(), approval(quote));
            payments.execute(session, intent, key(), new ExecuteCommand(version(approved), uuid(approved.body().get("attemptId"))));
            await("Host payment worker did not retain the original pending charge", () -> state(client, host, intent).equals("PENDING"));
            assertEquals(1, database.submissions.get());
            var original = binding(database, session, intent, approved);
            byte[] event = body(original, registration, "http-capture", ExecutionEvidence.State.CAPTURED);

            var rejected = webhook(client, host, intent, event, "0".repeat(64));
            assertEquals(422, rejected.statusCode());
            assertEquals(0, providerCount(database, session, "provider_event_envelopes", ""));
            assertEquals(0, providerCount(database, session, "provider_event_delivery", ""));
            assertEquals("PENDING", state(client, host, intent));

            var accepted = webhook(client, host, intent, event, sign(event));
            assertEquals(202, accepted.statusCode());
            assertTrue(JSON.readTree(accepted.body()).path("accepted").asBoolean());
            await("Signed HTTP event did not converge through background delivery", () -> state(client, host, intent).equals("SUCCEEDED")
                    && providerCount(database, session, "provider_event_delivery", " AND status='DONE'") == 1);
            assertEquals("CONSUMED", payments.status(session, intent).body().get("exposureState"));
            long evidence = scalar(database, "SELECT count(*) FROM operation_evidence WHERE tenant_id=? AND run_id=?", session.tenantId(), session.runId());

            assertEquals(202, webhook(client, host, intent, event, sign(event)).statusCode());
            await("Duplicate HTTP event was not durably acknowledged", () -> providerCount(database, session, "provider_event_delivery", " AND status='DONE'") == 2);
            assertEquals("SUCCEEDED", state(client, host, intent));
            assertEquals(evidence, scalar(database, "SELECT count(*) FROM operation_evidence WHERE tenant_id=? AND run_id=?", session.tenantId(), session.runId()));
            assertEquals(original, binding(database, session, intent, approved));
            assertEquals(1, scalar(database, "SELECT count(*) FROM financial_operations WHERE tenant_id=? AND run_id=?", session.tenantId(), session.runId()));
            assertEquals(1, database.submissions.get(), "Webhook delivery must never submit another synthetic charge");
        }
    }

    private static HttpResponse<String> webhook(HttpClient client, LocalPlatformHost host, UUID intent, byte[] body, String signature) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(origin(host) + ProviderWebhookHttpHandler.PREFIX + intent))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + EVENT_TOKEN)
                .header("Content-Type", "application/json").header("X-Synthetic-Event-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
    private static String state(HttpClient client, LocalPlatformHost host, UUID intent) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(origin(host) + "/internal/v0.1/intents/" + intent))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + READ_TOKEN).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return JSON.readTree(response.body()).path("state").asText();
    }
    private static String origin(LocalPlatformHost host) { return "http://127.0.0.1:" + host.port(); }
    private static long providerCount(DataSource database, Session session, String table, String filter) throws Exception {
        return scalar(database, "SELECT count(*) FROM " + table + " WHERE tenant_id=? AND run_id=?" + filter,
                session.tenantId(), session.runId().toString());
    }
    private static ProviderExecutionBinding binding(DataSource database, Session session, UUID intent, Result approved) throws Exception {
        var operation = row(database, "SELECT o.* FROM financial_operations o JOIN payment_attempts a USING(tenant_id,run_id,attempt_id) WHERE o.tenant_id=? AND o.run_id=? AND a.intent_id=?",
                session.tenantId(), session.runId(), intent);
        var identity = new OperationIdentity(new ExecutionScope(session.tenantId(), session.runId(), "merchant-demo"), intent,
                uuid(approved.body().get("attemptId")), (UUID) operation.get("operation_id"), (UUID) operation.get("adapter_key"), operation.get("request_digest").toString());
        return new JdbcProviderExecutionBindings(database).load(identity);
    }
    @FunctionalInterface private interface Observation { boolean reached() throws Exception; }
    private static void await(String failure, Observation observation) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            if (observation.reached()) return;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        fail(failure);
    }

    /** Counts actual synthetic submission entry while preserving all real PostgreSQL behavior. */
    private static final class SubmissionCountingDataSource implements DataSource {
        private final DataSource delegate;
        final AtomicInteger submissions = new AtomicInteger();
        SubmissionCountingDataSource(DataSource delegate) { this.delegate = delegate; }
        @Override public Connection getConnection() throws SQLException { return observe(delegate.getConnection()); }
        @Override public Connection getConnection(String user, String password) throws SQLException { return observe(delegate.getConnection(user, password)); }
        private Connection observe(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                if (method.getName().equals("prepareStatement") && arguments != null
                        && arguments[0] instanceof String sql && sql.startsWith("INSERT INTO simulator_operation_journal(")) submissions.incrementAndGet();
                try { return method.invoke(connection, arguments); }
                catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
        }
        @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
        @Override public void setLogWriter(PrintWriter writer) throws SQLException { delegate.setLogWriter(writer); }
        @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
        @Override public void setLoginTimeout(int timeout) throws SQLException { delegate.setLoginTimeout(timeout); }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return delegate.getParentLogger(); }
        @Override public <T> T unwrap(Class<T> type) throws SQLException { return delegate.unwrap(type); }
        @Override public boolean isWrapperFor(Class<?> type) throws SQLException { return delegate.isWrapperFor(type); }
    }
}
