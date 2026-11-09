package io.agenticpayments.provider;

import io.agenticpayments.application.*;
import io.agenticpayments.application.CheckoutConfigurationResolver.*;
import io.agenticpayments.checkout.CheckoutSessions;
import io.agenticpayments.infrastructure.*;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import static io.agenticpayments.provider.ProviderJournalTestSupport.NOW;
import static org.junit.jupiter.api.Assertions.*;

/** Populated V008 preserves native financial truth and unresolved checkout uncertainty under V009. */
class ProviderJournalMigrationUpgradeTest {
    @Test void populatedV008RowsAndExactMigrationPrefixesRemainUnchangedAfterRepeatedV009()throws Exception{
        DataSource admin=ProviderJournalTestSupport.database();String schema="provider_upgrade_"+UUID.randomUUID().toString().replace("-","");
        if(!schema.matches("provider_upgrade_[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid isolated schema");String quoted='"'+schema+'"';boolean created=false;
        PGSimpleDataSource isolated=ProviderJournalTestSupport.database();isolated.setCurrentSchema(schema);
        List<String> migrations=List.of("V001__simulator.sql","V002__conflict_evidence.sql","V003__accepted_evidence.sql","V004__recorded_replay.sql",
                "V005__admission_evidence.sql","V006__recorded_run_constraints.sql","V007__durable_ap2_admission.sql","V008__durable_checkout_lifecycle.sql");
        Map<Integer,String> prefix=new TreeMap<>();
        try{
            try(var connection=admin.getConnection();var statement=connection.createStatement()){statement.execute("CREATE SCHEMA "+quoted);created=true;}
            try(var connection=isolated.getConnection();var statement=connection.createStatement()){
                connection.setAutoCommit(false);try(var rows=statement.executeQuery("SELECT current_schema()")){assertTrue(rows.next());assertEquals(schema,rows.getString(1));}
                statement.execute("CREATE TABLE schema_migrations(version int PRIMARY KEY,digest text NOT NULL)");
                for(int i=0;i<migrations.size();i++){
                    String sql=resource(migrations.get(i));String digest=digest(sql);prefix.put(i+1,digest);
                    for(String part:sql.split(";"))if(!part.isBlank())statement.execute(part);
                    try(var insert=connection.prepareStatement("INSERT INTO schema_migrations VALUES(?,?)")){insert.setInt(1,i+1);insert.setString(2,digest);insert.executeUpdate();}
                }
                connection.commit();
            }
            UUID run=UUID.randomUUID();var customer=new PaymentService.Session("provider-upgrade",run,"customer-demo",
                    Set.of("runner:write","intent:write","intent:read","approval:write","execution:write"),Set.of("merchant-demo"),Set.of("fixture-grant-1","fixture-grant-usd"));
            PaymentService payment=PaymentServices.local(isolated);payment.seedRun(customer,NOW,100000);
            var createdIntent=payment.create(customer,key(),new PaymentService.CreateCommand("merchant-demo","success",JdbcPaymentStore.NATIVE_PROFILE,List.of()));
            UUID intent=UUID.fromString(createdIntent.body().get("intentId").toString());
            var quote=payment.quote(customer,intent,key(),new PaymentService.QuoteCommand(1,"cart-1250-eur",List.of(new PaymentService.Tender("simulator","synthetic-card","fixture-credential-1"))));
            var approved=payment.approve(customer,intent,key(),new PaymentService.ApproveCommand(((Number)quote.body().get("version")).intValue(),((Number)quote.body().get("checkoutRevision")).intValue(),quote.body().get("checkoutDigest").toString(),"fixture-grant-1"));
            io.agenticpayments.core.HistoricalPaymentRows.completeApproved(isolated,customer,intent,UUID.fromString(approved.body().get("attemptId").toString()));
            assertEquals("SUCCEEDED",payment.status(customer,intent).body().get("state"));
            var graph=new PaymentService.Session(customer.tenantId(),run,"agent-demo",Set.of("intent:write","intent:read"),customer.allowedMerchants(),Set.of());
            var binding=new CheckoutSessions.Binding(graph.tenantId(),graph.principalId(),"merchant-demo","migration-config");
            var identity=new ConfigurationIdentity(binding,"version1","credential1",AcpHttpCheckoutSessions.PROFILE,AcpHttpCheckoutSessions.ENCODING_VERSION,"f".repeat(64));
            var transactions=new JdbcCheckoutLifecycleTransactions(isolated);transactions.enroll(graph,identity);
            try(var transport=new AcpHttpCheckoutSessions(new AcpHttpCheckoutSessions.Configuration(binding,java.net.URI.create("http://127.0.0.1:9"),true,Duration.ofMillis(100),Duration.ofSeconds(1)),"synthetic-migration-credential".toCharArray())){
                CheckoutLifecycle checkout=new CheckoutLifecycleService(transactions,id->new Resolved(identity,transport));UUID purchase=UUID.randomUUID();
                // Seed the historical V008 representation directly: current stage/claim correctly require V010 seals.
                seedHistoricalUnknown(isolated,graph,identity,transport,purchase);
                var unknown=checkout.status(graph,purchase);assertTrue(unknown.mutationBlocked());
                Map<String,List<String>> before=snapshot(isolated,schema);assertTrue(before.get("native_artifacts").size()>0);assertEquals(1,before.get("simulator_operation_journal").size());assertEquals(1,before.get("checkout_lifecycle_commands").size());
                DatabaseMigrator.migrate(isolated);DatabaseMigrator.migrate(isolated);
                payment.reconcile(customer,intent,false); // The historical canonical evidence must replay without quarantine.
                Map<String,List<String>> after=snapshot(isolated,schema);for(var entry:before.entrySet())assertEquals(entry.getValue(),after.get(entry.getKey()),"Historical table changed: "+entry.getKey());
                assertEquals(unknown,checkout.status(graph,purchase));assertEquals("SUCCEEDED",payment.status(customer,intent).body().get("state"));
                for(String table:List.of("provider_compatibility_bindings","provider_compatibility_evidence","provider_compatibility_conflicts","provider_compatibility_resource_owners"))assertEquals(List.of(),after.get(table));
            }
            Map<Integer,String> history=new TreeMap<>();try(var connection=isolated.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT version,digest FROM schema_migrations ORDER BY version")){while(rows.next())history.put(rows.getInt(1),rows.getString(2));}
            assertEquals(15,history.size());for(var entry:prefix.entrySet())assertEquals(entry.getValue(),history.get(entry.getKey()));assertEquals(digest(resource("V009__provider_compatibility_journal.sql")),history.get(9));
            assertEquals(digest(resource("V010__external_checkout_authority_binding.sql")),history.get(10));
            assertEquals(digest(resource("V011__provider_admission_routes.sql")),history.get(11));
            io.agenticpayments.core.HistoricalPaymentRows.assertLegacyRoutes(isolated,1);
        }finally{
            // Only this validated, randomly created test schema may be dropped.
            if(created)try(var connection=admin.getConnection();var statement=connection.createStatement()){statement.execute("DROP SCHEMA "+quoted+" CASCADE");}
        }
    }
    private static String key(){return UUID.randomUUID().toString();}
    private static void seedHistoricalUnknown(DataSource database,PaymentService.Session graph,ConfigurationIdentity identity,
                                              AcpHttpCheckoutSessions transport,UUID purchase)throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper(); UUID command=UUID.randomUUID();
        var input=new CheckoutLifecycle.StageCommand(CheckoutSessions.Operation.CREATE,0,"merchant-demo","migration-config","usd",List.of("migration-item"));
        var request=new io.agenticpayments.checkout.CheckoutCommandTransport.Request(identity.binding(),CheckoutSessions.Operation.CREATE,
            null,"usd",List.of(new CheckoutSessions.ItemReference("migration-item")),key(),key());
        var prepared=transport.prepare(request);
        var current=new CheckoutLifecycleTransactions.Purchase(purchase,identity,"usd",null,2,null,null,command,true);
        String payload=mapper.writeValueAsString(Map.of("input",input,"configuration",identity,"prepared",prepared,"stagedRevision",1));
        try(var connection=database.getConnection()) {
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("INSERT INTO checkout_purchases VALUES(?,?,?,?,?,?,?,?)")) {
                Object[] values={graph.tenantId(),graph.runId(),graph.principalId(),purchase,"merchant-demo","migration-config",identity.configurationVersion(),mapper.writeValueAsString(current)};
                for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]);statement.executeUpdate();
            }
            try(var statement=connection.prepareStatement("INSERT INTO checkout_lifecycle_commands VALUES(?,?,?,?,?,?,?,?,'UNKNOWN',NULL,'WORKER_INTERRUPTED','UNKNOWN',0,NULL)")) {
                Object[] values={graph.tenantId(),graph.runId(),graph.principalId(),purchase,command,"migration-command","a".repeat(64),payload};
                for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]);statement.executeUpdate();
            }
            connection.commit();
        }
    }
    private static String resource(String name)throws Exception{try(var input=ProviderJournalMigrationUpgradeTest.class.getResourceAsStream("/db/"+name)){assertNotNull(input);return new String(input.readAllBytes(),StandardCharsets.UTF_8);}}
    private static String digest(String value)throws Exception{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    private static Map<String,List<String>> snapshot(DataSource database,String schema)throws SQLException{
        Map<String,List<String>> result=new TreeMap<>();List<String> names=new ArrayList<>();
        try(var connection=database.getConnection();var statement=connection.prepareStatement("SELECT table_name FROM information_schema.tables WHERE table_schema=? AND table_type='BASE TABLE' AND table_name<>'schema_migrations' ORDER BY table_name")){statement.setString(1,schema);try(var rows=statement.executeQuery()){while(rows.next())names.add(rows.getString(1));}}
        for(String name:names){if(!name.matches("[a-z][a-z0-9_]*"))throw new IllegalArgumentException("Unexpected isolated table");List<String> values=new ArrayList<>();
            try(var connection=database.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT row_to_json(t)::text FROM \""+name+"\" t ORDER BY row_to_json(t)::text")){while(rows.next())values.add(rows.getString(1));}result.put(name,values);}
        return result;
    }
}
