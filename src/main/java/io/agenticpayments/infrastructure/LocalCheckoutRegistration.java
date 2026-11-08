package io.agenticpayments.infrastructure;

import io.agenticpayments.application.CheckoutConfigurationResolver.ConfigurationIdentity;
import io.agenticpayments.application.CheckoutConfigurationResolver.Resolved;
import io.agenticpayments.application.CheckoutLifecycle;
import io.agenticpayments.application.CheckoutLifecycleService;
import io.agenticpayments.application.PaymentException;
import io.agenticpayments.application.ApplicationFailure;
import io.agenticpayments.application.PaymentStore;
import io.agenticpayments.sdk.PaymentAdapter;
import io.agenticpayments.composition.Composition.Ports;
import io.agenticpayments.application.PaymentService;
import io.agenticpayments.application.ExternalCheckoutRegistration;
import io.agenticpayments.application.CheckoutConfigurationResolver;
import io.agenticpayments.application.CheckoutLifecycleTransactions;
import io.agenticpayments.application.PaymentService.Session;
import io.agenticpayments.checkout.CheckoutSessions.Binding;
import io.agenticpayments.composition.Composition.Scope;
import io.agenticpayments.composition.NativeComposition;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import javax.sql.DataSource;

/** Explicit synthetic loopback enrollment only; applications may inject other reviewed implementations. */
public final class LocalCheckoutRegistration implements AutoCloseable {
    public static final String SYNTHETIC_CREDENTIAL = "synthetic-checkout-demo";
    private final AcpHttpCheckoutSessions transport;
    private final CheckoutLifecycle lifecycle;
    private final DataSource database;
    private final Session graph;
    private final ConfigurationIdentity identity;
    private final CheckoutConfigurationResolver resolver;
    private final CheckoutLifecycleTransactions transactions;
    private final CheckoutLifecycleTransactions.CoordinationDomain domain;
    private final boolean financial;
    private boolean started;
    private boolean closed;

    public LocalCheckoutRegistration(DataSource database, Session graph, String origin) {
        this(database, graph, origin, false);
    }
    public LocalCheckoutRegistration(DataSource database, Session graph, String origin, boolean financial) {
        this.database = java.util.Objects.requireNonNull(database);
        this.graph = java.util.Objects.requireNonNull(graph);
        this.financial = financial;
        this.domain = new CheckoutLifecycleTransactions.CoordinationDomain();
        try {
            var binding = new Binding(graph.tenantId(), graph.principalId(), "merchant-demo", "checkout-demo");
            URI uri = URI.create(origin);
            var configuration = new AcpHttpCheckoutSessions.Configuration(binding, uri, true, Duration.ofSeconds(1), Duration.ofSeconds(3));
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                (uri.toASCIIString() + "|" + AcpHttpCheckoutSessions.PROFILE + "|connect=1|total=3|synthetic-v1").getBytes(StandardCharsets.UTF_8)));
            identity = new ConfigurationIdentity(binding, fingerprint, "synthetic-credential-1",
                AcpHttpCheckoutSessions.PROFILE, AcpHttpCheckoutSessions.ENCODING_VERSION, fingerprint);
            transport = new AcpHttpCheckoutSessions(configuration, SYNTHETIC_CREDENTIAL.toCharArray());
            try {
                transactions = new JdbcCheckoutLifecycleTransactions(database, domain);
                resolver = requested -> {
                    if (!identity.equals(requested)) throw new PaymentException(ApplicationFailure.TEMPORARILY_UNAVAILABLE);
                    return new Resolved(identity, transport);
                };
                lifecycle = new CheckoutLifecycleService(transactions, resolver);
            } catch (RuntimeException error) {
                transport.close();
                throw error;
            }
        } catch (Exception error) {
            throw new IllegalArgumentException("INVALID_LOCAL_CHECKOUT_CONFIGURATION");
        }
    }
    /** Explicit host initialization after migration and idempotent run seeding. */
    public synchronized void start() {
        if (closed) throw new IllegalStateException("Local checkout registration is closed");
        if (!started) { transactions.enroll(graph, identity); started = true; }
    }
    public CheckoutLifecycle lifecycle() { return lifecycle; }
    /** Trusted composition intentionally shares this exact database and coordination token. */
    public PaymentService financialService() {
        var artifacts = new JdbcSignedArtifactStore(database);
        return new PaymentService(financialStore(new JdbcSimulatorAdapter(database), NativeComposition.ports(artifacts), NativeComposition.externalPorts(artifacts)));
    }
    public PaymentStore financialStore(PaymentAdapter adapter, Ports nativePorts, Ports externalPorts) {
        if (!financial || transactions.coordinationDomain() != domain
                || !transactions.bindingCapabilities().contains(CheckoutLifecycleTransactions.SEAL_CAPABILITY))
            throw new PaymentException(ApplicationFailure.UNSUPPORTED_CAPABILITY);
        var registration = new ExternalCheckoutRegistration(new Scope(graph.tenantId(), graph.runId()),
            graph.principalId(), identity, resolver, domain, ExternalCheckoutRegistration.REQUIRED_GUARANTEES);
        return new JdbcPaymentStore(database, adapter, null, nativePorts, externalPorts, registration, domain);
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        transport.close();
    }
    @Override public String toString() { return "LocalCheckoutRegistration[redacted]"; }
}
