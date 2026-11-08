package io.agenticpayments.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agenticpayments.application.*;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.application.instrumentation.InstrumentedPaymentOperations;
import io.agenticpayments.domain.PaymentRules;
import io.agenticpayments.infrastructure.*;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Mandatory PostgreSQL proof: telemetry failure after return cannot undo or repeat a committed command. */
class InstrumentedPaymentCommitIntegrationTest {
    private static DataSource database;
    private static final ObjectMapper JSON = new ObjectMapper();
    @BeforeAll static void postgres() {
        String url = System.getenv("PAYMENTS_DB_URL");
        if (url == null) throw new IllegalStateException("PAYMENTS_DB_URL, PAYMENTS_DB_USER and PAYMENTS_DB_PASSWORD are required for PostgreSQL tests");
        var pg = new PGSimpleDataSource(); pg.setURL(url);
        pg.setUser(System.getenv("PAYMENTS_DB_USER")); pg.setPassword(System.getenv("PAYMENTS_DB_PASSWORD"));
        database = pg; DatabaseMigrator.migrate(database);
    }

    @Test void acceptedCreateQuoteAndApprovalSurviveEachTelemetryReplacementFailureAndReplayWithoutDuplicates() throws Exception {
        for (var fault : TelemetryFaults.Fault.values()) {
          for (var telemetryFailure : TelemetryFaults.Failure.values()) {
            var session = new Session("services-commit-test", UUID.randomUUID(), "customer-demo",
                    Set.of("runner:write", "intent:write", "intent:read", "approval:write"),
                    Set.of("merchant-demo"), Set.of("fixture-grant-1"));
            PaymentOperations real = PaymentServices.local(database);
            real.seedRun(session, Instant.parse("2026-11-06T10:00:00Z"), 1250);
            var calls = new AtomicInteger(); var returned = new AtomicReference<Object>();
            PaymentOperations witness = (PaymentOperations) Proxy.newProxyInstance(PaymentOperations.class.getClassLoader(),
                    new Class<?>[]{PaymentOperations.class}, (proxy, method, args) -> {
                        calls.incrementAndGet();
                        try { Object result = method.invoke(real, args); returned.set(result); return result; }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
            String createKey = key(), quoteKey = key(), approveKey = key();
            var createCommand = new CreateCommand("merchant-demo", "success", PaymentRules.PROFILE, List.of());
            var createFault = new TelemetryFaults(fault, telemetryFailure);
            var created = new InstrumentedPaymentOperations(witness, createFault.telemetry).create(session, createKey, createCommand);
            assertSame(returned.get(), created); assertTrue(createFault.failures.get() > 0, fault.name()); assertEquals(1, calls.get());
            UUID intent = UUID.fromString(created.body().get("intentId").toString());
            var quoteCommand = new QuoteCommand(version(created), "cart-1250-eur",
                    List.of(new Tender("simulator", "synthetic-card", "fixture-credential-1")));
            var quoteFault = new TelemetryFaults(fault, telemetryFailure);
            var quoted = new InstrumentedPaymentOperations(witness, quoteFault.telemetry).quote(session, intent, quoteKey, quoteCommand);
            assertSame(returned.get(), quoted); assertTrue(quoteFault.failures.get() > 0, fault.name()); assertEquals(2, calls.get());
            var approvalCommand = new ApproveCommand(version(quoted), ((Number) quoted.body().get("checkoutRevision")).intValue(),
                    quoted.body().get("checkoutDigest").toString(), "fixture-grant-1");
            var approveFault = new TelemetryFaults(fault, telemetryFailure);
            var approved = new InstrumentedPaymentOperations(witness, approveFault.telemetry).approve(session, intent, approveKey, approvalCommand);
            assertSame(returned.get(), approved); assertTrue(approveFault.failures.get() > 0, fault.name()); assertEquals(3, calls.get());
            UUID attempt = UUID.fromString(approved.body().get("attemptId").toString());

            // Reconstruct from the same durable store; replay uses the original immutable command identities.
            PaymentOperations restarted = PaymentServices.local(database);
            replay(created, restarted.create(session, createKey, createCommand));
            replay(quoted, restarted.quote(session, intent, quoteKey, quoteCommand));
            replay(approved, restarted.approve(session, intent, approveKey, approvalCommand));
            assertEquals("APPROVED", restarted.status(session, intent).body().get("state"));
            assertEquals("HELD", restarted.status(session, intent).body().get("exposureState"));
            assertEquals(3, scalar(session, "SELECT count(*) FROM commands WHERE tenant_id=? AND run_id=?"));
            assertEquals(1, scalar(session, "SELECT count(*) FROM payment_intents WHERE tenant_id=? AND run_id=?"));
            assertEquals(1, scalar(session, "SELECT count(*) FROM checkout_snapshots WHERE tenant_id=? AND run_id=?"));
            assertEquals(1, scalar(session, "SELECT count(*) FROM payment_attempts WHERE tenant_id=? AND run_id=?"));
            assertEquals(1, scalar(session, "SELECT count(*) FROM reservations WHERE tenant_id=? AND run_id=?"));
            assertEquals(1, scalar(session, "SELECT count(*) FROM reservations WHERE tenant_id=? AND run_id=? AND attempt_id=? AND state='HELD'", attempt));
            assertEquals(1250, scalar(session, "SELECT held FROM budgets WHERE tenant_id=? AND run_id=? AND budget_id='budget-eur'"));
            assertEquals(0, scalar(session, "SELECT consumed FROM budgets WHERE tenant_id=? AND run_id=? AND budget_id='budget-eur'"));
            assertEquals(0, scalar(session, "SELECT count(*) FROM financial_operations WHERE tenant_id=? AND run_id=?"));
          }
        }
    }

    private static void replay(Result accepted, Result replayed) throws Exception {
        assertFalse(accepted.replayed()); assertTrue(replayed.replayed()); assertEquals(accepted.status(), replayed.status());
        // Compare the wire representation: JSONB reload may narrow Long values to Integer.
        assertEquals(JSON.readTree(JSON.writeValueAsBytes(accepted.body())), JSON.readTree(JSON.writeValueAsBytes(replayed.body())));
    }
    private static String key() { return UUID.randomUUID().toString(); }
    private static int version(Result result) { return ((Number) result.body().get("version")).intValue(); }
    private static long scalar(Session session, String sql, Object... tail) throws Exception {
        try (var connection = database.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, session.tenantId()); statement.setObject(2, session.runId());
            for (int i = 0; i < tail.length; i++) statement.setObject(i + 3, tail[i]);
            try (var rows = statement.executeQuery()) { assertTrue(rows.next()); return rows.getLong(1); }
        }
    }
}
