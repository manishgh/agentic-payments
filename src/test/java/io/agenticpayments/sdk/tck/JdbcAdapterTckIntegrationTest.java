package io.agenticpayments.sdk.tck;

import io.agenticpayments.infrastructure.DatabaseMigrator;
import io.agenticpayments.infrastructure.tck.JdbcAdapterTckFactory;
import io.agenticpayments.sdk.CapabilityOffer;
import org.postgresql.ds.PGSimpleDataSource;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class JdbcAdapterTckIntegrationTest {
    @Test void reusableSuiteChecksReferenceAdapterAgainstIndependentPostgresObservations() {
        var database = new PGSimpleDataSource();
        String url = System.getenv("PAYMENTS_DB_URL");
        assertNotNull(url, "Real PostgreSQL test configuration is required.");
        database.setURL(url); database.setUser(System.getenv("PAYMENTS_DB_USER")); database.setPassword(System.getenv("PAYMENTS_DB_PASSWORD"));
        DatabaseMigrator.migrate(database);
        var plan = new TckPlan(TckPlan.VERSION, CapabilityOffer.PROFILE, Set.of("atomic-no-effect-seal"), Set.copyOf(TckPlan.CASES), TckPlan.CASES, "42", Instant.parse("2026-11-06T10:00:00Z"));
        var report = new AdapterTck().run(new JdbcAdapterTckFactory(database), plan);
        assertTrue(report.requiredCasesPassed(), report.toString());
        assertEquals(4, report.results().size());
        assertTrue(report.results().stream().allMatch(result -> result.status() == TckStatus.PASS));
    }
}
