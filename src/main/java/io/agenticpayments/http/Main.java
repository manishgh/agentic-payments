package io.agenticpayments.http;

import io.agenticpayments.application.PaymentService;
import io.agenticpayments.application.PaymentService.Session;
import io.agenticpayments.infrastructure.DatabaseMigrator;
import io.agenticpayments.infrastructure.PaymentServices;
import io.agenticpayments.infrastructure.LocalCheckoutRegistration;
import org.postgresql.ds.PGSimpleDataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class Main {
    private Main() { }
    public static void main(String[] args) throws Exception {
        PGSimpleDataSource database = new PGSimpleDataSource();
        database.setURL(required("PAYMENTS_DB_URL"));
        database.setUser(required("PAYMENTS_DB_USER"));
        database.setPassword(required("PAYMENTS_DB_PASSWORD"));
        DatabaseMigrator.migrate(database);
        PaymentService seedService = PaymentServices.local(database);
        UUID run = UUID.fromString(required("PAYMENTS_RUN_ID"));
        String tenant = "tenant-local";
        Session runner = new Session(tenant, run, "customer-demo", Set.of("runner:write"), Set.of("merchant-demo"), Set.of("fixture-grant-1", "fixture-grant-usd"));
        Session graph = new Session(tenant, run, "agent-demo", Set.of("intent:write", "intent:read", "execution:write"), Set.of("merchant-demo"), Set.of());
        Session trusted = new Session(tenant, run, "customer-demo", Set.of("intent:read", "approval:write", "action:complete", "authority:revoke"), Set.of("merchant-demo"), Set.of("fixture-grant-1", "fixture-grant-usd"));
        // Seed is idempotent: restart must not replenish authority or reset virtual time.
        seedService.seedRun(runner, Instant.parse("2026-11-06T10:00:00Z"), 100_000);
        int port = Integer.parseInt(System.getenv().getOrDefault("PAYMENTS_PORT", "8080"));
        LocalSessions sessions = new LocalSessions(List.of(new LocalSessions.Entry(required("PAYMENTS_GRAPH_TOKEN"), graph), new LocalSessions.Entry(required("PAYMENTS_UI_TOKEN"), trusted)));
        String checkoutOrigin = System.getenv("PAYMENTS_CHECKOUT_DEMO_ORIGIN");
        String financeFlag = System.getenv().getOrDefault("PAYMENTS_EXTERNAL_CHECKOUT_FINANCE", "false");
        if (!java.util.Set.of("true", "false").contains(financeFlag) || (financeFlag.equals("true") && checkoutOrigin == null))
            throw new IllegalArgumentException("INVALID_EXTERNAL_CHECKOUT_FINANCE_CONFIGURATION");
        boolean financial = financeFlag.equals("true");
        LocalCheckoutRegistration checkout = checkoutOrigin == null ? null : new LocalCheckoutRegistration(database, graph, checkoutOrigin, financial);
        PaymentService service = financial ? checkout.financialService() : seedService;
        PaymentHttpServer server = new PaymentHttpServer(service, sessions, port, Path.of(System.getenv().getOrDefault("PAYMENTS_UI_DIRECTORY", "ui")),
            checkout == null ? null : checkout.lifecycle());
        var worker = Executors.newSingleThreadScheduledExecutor();
        worker.scheduleWithFixedDelay(() -> {
            try { service.runNext(runner); }
            catch (RuntimeException failure) { System.err.println("Local worker could not complete its step; durable work remains available."); }
        }, 0, 200, TimeUnit.MILLISECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { worker.shutdownNow(); server.close(); if (checkout != null) checkout.close(); }));
        server.start();
        System.out.println("Local-fake payments reference running at http://127.0.0.1:" + server.port());
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing required configuration: " + name);
        return value;
    }
}
