package io.agenticpayments.core;

import io.agenticpayments.domain.OperationBinding;
import io.agenticpayments.domain.PaymentRules;
import io.agenticpayments.infrastructure.DatabaseMigrator;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static io.agenticpayments.core.CoreTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the additive migration against a populated historical schema, not an already migrated database. */
class NativeMigrationUpgradeTest {
    private static final List<String> HISTORICAL=List.of(
        "/db/V001__simulator.sql","/db/V002__conflict_evidence.sql","/db/V003__accepted_evidence.sql",
        "/db/V004__recorded_replay.sql","/db/V005__admission_evidence.sql","/db/V006__recorded_run_constraints.sql");
    private static final String NATIVE="/db/V007__durable_ap2_admission.sql";
    private static String quotedSchema(String schema) {
        if(schema==null||!schema.matches("native_upgrade_[0-9a-f]{32}")) throw new IllegalArgumentException("Invalid isolated test schema");
        return "\""+schema+"\"";
    }
    private static byte[] resource(String name) throws Exception {
        try(var input=NativeMigrationUpgradeTest.class.getResourceAsStream(name)) {
            if(input==null) throw new IllegalStateException("Required historical migration is missing");
            return input.readAllBytes();
        }
    }
    private static String checksum(byte[] bytes) throws Exception {
        return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static void execute(Connection connection,String sql,Object... values) throws SQLException {
        try(var statement=connection.prepareStatement(sql)) {
            for(int n=0;n<values.length;n++) statement.setObject(n+1,values[n] instanceof Instant time?Timestamp.from(time):values[n]);
            statement.executeUpdate();
        }
    }
    private static Map<Integer,String> migrationHistory(DataSource ds) throws SQLException {
        Map<Integer,String> result=new TreeMap<>();
        try(var connection=ds.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT version,digest FROM schema_migrations ORDER BY version")) {
            while(rows.next()) assertNull(result.put(rows.getInt(1),rows.getString(2)));
        }
        return result;
    }
    @Test void populatedV006UpgradesToV007WithoutChangingLegacyFinancialIdentityOrExposure() throws Exception {
        DataSource admin=database();
        String schema="native_upgrade_"+UUID.randomUUID().toString().replace("-","");
        String safeSchema=quotedSchema(schema); boolean created=false;
        var isolated=new PGSimpleDataSource();isolated.setURL(System.getenv("PAYMENTS_DB_URL"));
        isolated.setUser(System.getenv("PAYMENTS_DB_USER"));isolated.setPassword(System.getenv("PAYMENTS_DB_PASSWORD"));
        isolated.setCurrentSchema(schema);
        try {
            try(var connection=admin.getConnection();var statement=connection.createStatement()) {
                statement.execute("CREATE SCHEMA "+safeSchema); created=true;
            }
            Map<Integer,String> originalChecksums=new TreeMap<>();
            try(var connection=isolated.getConnection()) {
                connection.setAutoCommit(false);
                try(var statement=connection.createStatement()) {
                    try(var row=statement.executeQuery("SELECT current_schema()")) {assertTrue(row.next());assertEquals(schema,row.getString(1));}
                    statement.execute("CREATE TABLE schema_migrations(version int PRIMARY KEY,digest text NOT NULL)");
                    for(int n=0;n<HISTORICAL.size();n++) {
                        byte[] source=resource(HISTORICAL.get(n));String digest=checksum(source);
                        for(String sql:new String(source,StandardCharsets.UTF_8).split(";")) if(!sql.isBlank()) statement.execute(sql);
                        execute(connection,"INSERT INTO schema_migrations(version,digest) VALUES(?,?)",n+1,digest);
                        originalChecksums.put(n+1,digest);
                    }
                }
                connection.commit();
            }
            assertEquals(originalChecksums,migrationHistory(isolated));
            String tenant="historical-native-upgrade";UUID run=UUID.randomUUID(),intent=UUID.randomUUID(),attempt=UUID.randomUUID(),operation=UUID.randomUUID();
            String checkout=PaymentRules.checkout("merchant-demo",1,"cart-1250-eur","EUR","fixture-credential-1");
            String checkoutDigest=PaymentRules.sha256(checkout);
            String operationDigest=new OperationBinding(tenant,run,"merchant-demo",intent,attempt,operation,checkoutDigest,"EUR",1250,
                "fixture-grant-1",1,"customer-demo","success","fixture-credential-1").digest();
            try(var connection=isolated.getConnection()) {
                connection.setAutoCommit(false);
                execute(connection,"INSERT INTO simulation_runs(tenant_id,run_id,virtual_time,next_intent_sequence,next_work_sequence) VALUES(?,?,?,1,1)",tenant,run,CLOCK);
                execute(connection,"INSERT INTO budgets(tenant_id,run_id,budget_id,currency,limit_minor,held,consumed) VALUES(?,?,'budget-eur','EUR',5000,1250,0)",tenant,run);
                execute(connection,"INSERT INTO authority_grants(tenant_id,run_id,grant_id,budget_id,subject_id,merchant_id,valid_from,expires_at) VALUES(?,?,'fixture-grant-1','budget-eur','customer-demo','merchant-demo',?,?)",tenant,run,CLOCK,CLOCK.plusSeconds(3600));
                execute(connection,"INSERT INTO payment_intents(tenant_id,run_id,intent_id,merchant_id,scenario_id,state,version,checkout_revision,creation_sequence) VALUES(?,?,?,'merchant-demo','success','PREPARING',4,1,1)",tenant,run,intent);
                execute(connection,"INSERT INTO checkout_snapshots(tenant_id,run_id,intent_id,revision,digest,canonical_bytes,currency,minor_units,credential_reference,expires_at) VALUES(?,?,?,1,?,?,'EUR',1250,'fixture-credential-1',?)",tenant,run,intent,checkoutDigest,checkout,CLOCK.plusSeconds(900));
                execute(connection,"INSERT INTO payment_attempts(tenant_id,run_id,attempt_id,intent_id,checkout_revision,grant_id,grant_version,subject_id,owner) VALUES(?,?,?,?,1,'fixture-grant-1',1,'customer-demo','simulator-core')",tenant,run,attempt,intent);
                execute(connection,"INSERT INTO reservations(tenant_id,run_id,attempt_id,budget_id,minor_units,state) VALUES(?,?,?,'budget-eur',1250,'HELD')",tenant,run,attempt);
                execute(connection,"INSERT INTO financial_operations(tenant_id,run_id,operation_id,attempt_id,adapter_key,request_digest) VALUES(?,?,?,?,?,?)",tenant,run,operation,attempt,operation,operationDigest);
                execute(connection,"INSERT INTO outbox(tenant_id,run_id,operation_id,kind,enqueue_sequence) VALUES(?,?,?,'PREPARE',1)",tenant,run,operation);
                connection.commit();
            }
            List<String> retainedTables=List.of("simulation_runs","budgets","authority_grants","checkout_snapshots","payment_attempts","reservations","financial_operations","outbox");
            Map<String,Map<String,Object>> before=new LinkedHashMap<>();
            for(String table:retainedTables) before.put(table,row(isolated,"SELECT * FROM "+table+" WHERE tenant_id=? AND run_id=?",tenant,run));
            Map<String,Object> priorIntent=row(isolated,"SELECT * FROM payment_intents WHERE tenant_id=? AND run_id=? AND intent_id=?",tenant,run,intent);
            assertFalse(priorIntent.containsKey("profile"));

            DatabaseMigrator.migrate(isolated);
            DatabaseMigrator.migrate(isolated); // Reopening an upgraded store must be idempotent.

            Map<Integer,String> after=migrationHistory(isolated);assertEquals(10,after.size());
            for(var original:originalChecksums.entrySet()) assertEquals(original.getValue(),after.get(original.getKey()));
            assertEquals(checksum(resource(NATIVE)),after.get(7));
            assertEquals(checksum(resource("/db/V008__durable_checkout_lifecycle.sql")),after.get(8));
            assertEquals(checksum(resource("/db/V009__provider_compatibility_journal.sql")),after.get(9));
            assertEquals(checksum(resource("/db/V010__external_checkout_authority_binding.sql")),after.get(10));
            Map<String,Object> upgradedIntent=row(isolated,"SELECT * FROM payment_intents WHERE tenant_id=? AND run_id=? AND intent_id=?",tenant,run,intent);
            assertEquals("local-fake-single-tender-0.1",upgradedIntent.remove("profile"));assertEquals(priorIntent,upgradedIntent);
            for(String table:retainedTables) assertEquals(before.get(table),row(isolated,"SELECT * FROM "+table+" WHERE tenant_id=? AND run_id=?",tenant,run),"Historical row changed in "+table);
            assertEquals(attempt,before.get("payment_attempts").get("attempt_id"));
            assertEquals(operation,before.get("financial_operations").get("operation_id"));assertEquals(operation,before.get("financial_operations").get("adapter_key"));
            assertEquals(operationDigest,before.get("financial_operations").get("request_digest"));
            assertEquals("HELD",before.get("reservations").get("state"));assertEquals(1250L,before.get("budgets").get("held"));assertEquals(0L,before.get("budgets").get("consumed"));
            for(String table:List.of("native_artifacts","native_checkouts","native_trust_keys","native_authorities","native_challenges","native_replay_identities"))
                assertEquals(0,scalar(isolated,"SELECT count(*) FROM "+table));
        } finally {
            // Only the exact, validated, randomly created schema from this invocation can be dropped.
            if(created) try(var connection=admin.getConnection();var statement=connection.createStatement()) {
                statement.execute("DROP SCHEMA "+quotedSchema(schema)+" CASCADE");
            }
        }
    }
}
