package io.agenticpayments.core;

import io.agenticpayments.domain.PaymentRules;
import io.agenticpayments.infrastructure.DatabaseMigrator;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static io.agenticpayments.core.CoreTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** V008 is additive to an already populated native V007 schema. */
class CheckoutMigrationUpgradeTest {
    @Test void populatedNativeV007RetainsEveryExistingRowAndChecksumWhenLifecycleTablesAreAdded() throws Exception {
        var admin=database();String schema="checkout_upgrade_"+UUID.randomUUID().toString().replace("-","");
        if(!schema.matches("checkout_upgrade_[0-9a-f]{32}"))throw new IllegalStateException("Invalid isolated schema");
        String quoted='"'+schema+'"';boolean created=false;
        var isolated=new PGSimpleDataSource();isolated.setURL(System.getenv("PAYMENTS_DB_URL"));
        isolated.setUser(System.getenv("PAYMENTS_DB_USER"));isolated.setPassword(System.getenv("PAYMENTS_DB_PASSWORD"));isolated.setCurrentSchema(schema);
        List<String> names=List.of("V001__simulator.sql","V002__conflict_evidence.sql","V003__accepted_evidence.sql","V004__recorded_replay.sql",
                "V005__admission_evidence.sql","V006__recorded_run_constraints.sql","V007__durable_ap2_admission.sql");
        Map<Integer,String> checksums=new TreeMap<>();String tenant="checkout-migration";UUID run=UUID.randomUUID(),intent=UUID.randomUUID();
        try{
            try(var connection=admin.getConnection();var statement=connection.createStatement()){statement.execute("CREATE SCHEMA "+quoted);created=true;}
            try(var connection=isolated.getConnection();var statement=connection.createStatement()){
                connection.setAutoCommit(false);statement.execute("CREATE TABLE schema_migrations(version int PRIMARY KEY,digest text NOT NULL)");
                for(int i=0;i<names.size();i++){
                    try(var input=getClass().getResourceAsStream("/db/"+names.get(i))){
                        assertNotNull(input);String sql=new String(input.readAllBytes(),StandardCharsets.UTF_8);
                        for(String part:sql.split(";"))if(!part.isBlank())statement.execute(part);
                        String digest=PaymentRules.sha256(sql);checksums.put(i+1,digest);
                        try(var insert=connection.prepareStatement("INSERT INTO schema_migrations VALUES(?,?)")){insert.setInt(1,i+1);insert.setString(2,digest);insert.executeUpdate();}
                    }
                }
                try(var insert=connection.prepareStatement("INSERT INTO simulation_runs(tenant_id,run_id,virtual_time,sequence_origin) VALUES(?,?,'2026-11-08T00:00:00Z','RECORDED')")){
                    insert.setString(1,tenant);insert.setObject(2,run);insert.executeUpdate();
                }
                try(var insert=connection.prepareStatement("INSERT INTO payment_intents(tenant_id,run_id,intent_id,merchant_id,scenario_id,state,creation_sequence,profile) VALUES(?,?,?,'merchant-demo','success','DRAFT',1,'local-ap2-acp-single-tender-0.1')")){
                    insert.setString(1,tenant);insert.setObject(2,run);insert.setObject(3,intent);insert.executeUpdate();
                }
                try(var insert=connection.prepareStatement("INSERT INTO native_artifacts VALUES(?,?,'retained-native','sha256:retained','application/jwt','native-profile',?,3)")){
                    insert.setString(1,tenant);insert.setObject(2,run);insert.setBytes(3,new byte[]{1,2,3});insert.executeUpdate();
                }
                connection.commit();
            }
            var oldRun=row(isolated,"SELECT * FROM simulation_runs WHERE tenant_id=? AND run_id=?",tenant,run);
            var oldIntent=row(isolated,"SELECT * FROM payment_intents WHERE tenant_id=? AND run_id=?",tenant,run);
            byte[] oldContent=(byte[])row(isolated,"SELECT * FROM native_artifacts WHERE tenant_id=? AND run_id=?",tenant,run).get("content");
            DatabaseMigrator.migrate(isolated);DatabaseMigrator.migrate(isolated);
            assertEquals(oldRun,row(isolated,"SELECT * FROM simulation_runs WHERE tenant_id=? AND run_id=?",tenant,run));
            assertEquals(oldIntent,row(isolated,"SELECT * FROM payment_intents WHERE tenant_id=? AND run_id=?",tenant,run));
            assertArrayEquals(oldContent,(byte[])row(isolated,"SELECT * FROM native_artifacts WHERE tenant_id=? AND run_id=?",tenant,run).get("content"));
            try(var connection=isolated.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT version,digest FROM schema_migrations ORDER BY version")){
                int count=0;while(rows.next()){count++;if(count<=7)assertEquals(checksums.get(count),rows.getString(2));}assertEquals(15,count);
            }
            HistoricalPaymentRows.assertLegacyRoutes(isolated,0);
            for(String table:List.of("checkout_configurations","checkout_current_configurations","checkout_purchases","checkout_lifecycle_commands","checkout_lifecycle_outcomes"))
                assertEquals(0,scalar(isolated,"SELECT count(*) FROM "+table));
        }finally{
            // Only the fixed-pattern, randomly created schema from this test may be dropped.
            if(created)try(var connection=admin.getConnection();var statement=connection.createStatement()){statement.execute("DROP SCHEMA "+quoted+" CASCADE");}
        }
    }
}
