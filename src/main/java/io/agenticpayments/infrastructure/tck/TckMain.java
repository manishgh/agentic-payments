package io.agenticpayments.infrastructure.tck;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agenticpayments.infrastructure.DatabaseMigrator;
import io.agenticpayments.sdk.CapabilityOffer;
import io.agenticpayments.sdk.tck.*;
import org.postgresql.ds.PGSimpleDataSource;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** Explicit local simulator TCK entry point, with no provider/network adapter activation. */
public final class TckMain {
    private TckMain() { }
    public static void main(String[] arguments) {
        try {
            if (arguments.length != 0) throw new IllegalArgumentException();
            var database = new PGSimpleDataSource();
            database.setURL(required("PAYMENTS_DB_URL"));
            database.setUser(required("PAYMENTS_DB_USER"));
            database.setPassword(required("PAYMENTS_DB_PASSWORD"));
            DatabaseMigrator.migrate(database);
            var plan = new TckPlan(TckPlan.VERSION, CapabilityOffer.PROFILE, Set.of("atomic-no-effect-seal"),
                    Set.copyOf(TckPlan.CASES), TckPlan.CASES, "42", Instant.parse("2026-11-06T10:00:00Z"));
            var report = new AdapterTck().run(new JdbcAdapterTckFactory(database), plan);
            System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "certificationVerdict", "NOT_CERTIFIED", "adapterChecks", report)));
            if (!report.requiredCasesPassed()) System.exit(1);
        } catch (Exception failure) {
            System.err.println("Local adapter evidence could not be completed; check isolated database configuration.");
            System.exit(2);
        }
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException();
        return value;
    }
}
