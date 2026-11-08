package io.agenticpayments.http;

import io.agenticpayments.application.PaymentService.Session;
import org.postgresql.ds.PGSimpleDataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class Main {
    private Main() { }
    public static void main(String[] args) throws Exception {
        PGSimpleDataSource database = new PGSimpleDataSource();
        database.setURL(required("PAYMENTS_DB_URL"));
        database.setUser(required("PAYMENTS_DB_USER"));
        database.setPassword(required("PAYMENTS_DB_PASSWORD"));
        UUID run = UUID.fromString(required("PAYMENTS_RUN_ID"));
        String tenant = "tenant-local";
        Session runner = new Session(tenant, run, "customer-demo", Set.of("runner:write"), Set.of("merchant-demo"), Set.of("fixture-grant-1", "fixture-grant-usd"));
        Session graph = new Session(tenant, run, "agent-demo", Set.of("intent:write", "intent:read", "execution:write"), Set.of("merchant-demo"), Set.of());
        Session trusted = new Session(tenant, run, "customer-demo", Set.of("intent:read", "approval:write", "action:complete", "authority:revoke"), Set.of("merchant-demo"), Set.of("fixture-grant-1", "fixture-grant-usd"));
        int port = Integer.parseInt(System.getenv().getOrDefault("PAYMENTS_PORT", "8080"));
        LocalSessions sessions = new LocalSessions(List.of(new LocalSessions.Entry(required("PAYMENTS_GRAPH_TOKEN"), graph), new LocalSessions.Entry(required("PAYMENTS_UI_TOKEN"), trusted)));
        String checkoutOrigin = System.getenv("PAYMENTS_CHECKOUT_DEMO_ORIGIN");
        String financeFlag = System.getenv().getOrDefault("PAYMENTS_EXTERNAL_CHECKOUT_FINANCE", "false");
        if (!Set.of("true", "false").contains(financeFlag) || financeFlag.equals("true") && checkoutOrigin == null) {
            throw new IllegalArgumentException("INVALID_EXTERNAL_CHECKOUT_FINANCE_CONFIGURATION");
        }
        var config = new LocalPlatformHost.Configuration(database, runner, graph, sessions, port,
            Path.of(System.getenv().getOrDefault("PAYMENTS_UI_DIRECTORY", "ui")), checkoutOrigin, financeFlag.equals("true"));
        String telemetryMode = System.getenv().getOrDefault("PAYMENTS_TELEMETRY", "disabled");
        LocalTelemetry telemetry = switch (telemetryMode) {
            case "disabled" -> LocalTelemetry.disabled();
            case "json-console" -> LocalTelemetry.console(System.err);
            default -> throw new IllegalArgumentException("INVALID_TELEMETRY_CONFIGURATION");
        };
        var host = new LocalPlatformHost(config, telemetry);
        try {
            host.start();
            Runtime.getRuntime().addShutdownHook(new Thread(host::close, "local-platform-shutdown"));
        } catch (Exception | Error failure) {
            try { host.close(); } catch (RuntimeException | LinkageError | AssertionError ignored) { }
            throw failure;
        }
        System.out.println("Local-fake payments reference running at http://127.0.0.1:" + host.port());
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing required configuration: " + name);
        return value;
    }
}
