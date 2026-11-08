package io.agenticpayments.infrastructure;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import io.agenticpayments.domain.PaymentRules;

public final class DatabaseMigrator {
    private DatabaseMigrator() {}
    public static void migrate(DataSource ds) {
        try (var c=ds.getConnection()) {
            c.setAutoCommit(false);
            try(var st=c.createStatement()) {
                st.execute("SELECT pg_advisory_xact_lock(719420012)");
                st.execute("CREATE TABLE IF NOT EXISTS schema_migrations(version int PRIMARY KEY,digest text NOT NULL)");
                String[] resources={"/db/V001__simulator.sql","/db/V002__conflict_evidence.sql","/db/V003__accepted_evidence.sql","/db/V004__recorded_replay.sql","/db/V005__admission_evidence.sql","/db/V006__recorded_run_constraints.sql","/db/V007__durable_ap2_admission.sql","/db/V008__durable_checkout_lifecycle.sql","/db/V009__provider_compatibility_journal.sql","/db/V010__external_checkout_authority_binding.sql"};
                for(int index=0;index<resources.length;index++) {
                    try(var input=DatabaseMigrator.class.getResourceAsStream(resources[index])) {
                        if(input==null) throw new IllegalStateException("Migration resource is missing");
                        String sql=new String(input.readAllBytes(),StandardCharsets.UTF_8), digest=PaymentRules.sha256(sql); int version=index+1;
                        try(var p=c.prepareStatement("SELECT digest FROM schema_migrations WHERE version=?")) {
                            p.setInt(1,version);
                            try(var rs=p.executeQuery()) { if(rs.next()) { if(!digest.equals(rs.getString(1))) throw new IllegalStateException("Migration checksum changed"); continue; } }
                        }
                        for(String statement:sql.split(";")) if(!statement.isBlank()) st.execute(statement);
                        try(var p=c.prepareStatement("INSERT INTO schema_migrations VALUES(?,?)")) { p.setInt(1,version); p.setString(2,digest); p.executeUpdate(); }
                    }
                }
                c.commit();
            }
        } catch(Exception e) { throw new IllegalStateException("Database migration failed",e); }
    }
}
