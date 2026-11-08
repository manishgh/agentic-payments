package io.agenticpayments.infrastructure;

import io.agenticpayments.application.CheckoutConfigurationResolver.ConfigurationIdentity;
import io.agenticpayments.application.CheckoutConfigurationResolver.Resolved;
import io.agenticpayments.application.CheckoutLifecycle;
import io.agenticpayments.application.CheckoutLifecycleService;
import io.agenticpayments.application.PaymentException;
import io.agenticpayments.application.PaymentService.Session;
import io.agenticpayments.checkout.CheckoutSessions.Binding;
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

    public LocalCheckoutRegistration(DataSource database, Session graph, String origin) {
        try {
            var binding = new Binding(graph.tenantId(), graph.principalId(), "merchant-demo", "checkout-demo");
            URI uri = URI.create(origin);
            var configuration = new AcpHttpCheckoutSessions.Configuration(binding, uri, true, Duration.ofSeconds(1), Duration.ofSeconds(3));
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                (uri.toASCIIString() + "|" + AcpHttpCheckoutSessions.PROFILE + "|connect=1|total=3|synthetic-v1").getBytes(StandardCharsets.UTF_8)));
            var identity = new ConfigurationIdentity(binding, fingerprint, "synthetic-credential-1",
                AcpHttpCheckoutSessions.PROFILE, AcpHttpCheckoutSessions.ENCODING_VERSION, fingerprint);
            transport = new AcpHttpCheckoutSessions(configuration, SYNTHETIC_CREDENTIAL.toCharArray());
            try {
                var transactions = new JdbcCheckoutLifecycleTransactions(database);
                transactions.enroll(graph, identity);
                lifecycle = new CheckoutLifecycleService(transactions, requested -> {
                    if (!identity.equals(requested)) throw new PaymentException(503, "TEMPORARILY_UNAVAILABLE");
                    return new Resolved(identity, transport);
                });
            } catch (RuntimeException error) {
                transport.close();
                throw error;
            }
        } catch (Exception error) {
            throw new IllegalArgumentException("INVALID_LOCAL_CHECKOUT_CONFIGURATION");
        }
    }
    public CheckoutLifecycle lifecycle() { return lifecycle; }
    @Override public void close() { transport.close(); }
    @Override public String toString() { return "LocalCheckoutRegistration[redacted]"; }
}
