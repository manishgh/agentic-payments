package io.agenticpayments.externalbinding;

import io.agenticpayments.application.*;
import io.agenticpayments.application.CheckoutConfigurationResolver.*;
import io.agenticpayments.application.CheckoutLifecycleTransactions.*;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.checkout.CheckoutCommandTransport;
import io.agenticpayments.checkout.CheckoutSessions;
import io.agenticpayments.domain.PaymentRules;
import io.agenticpayments.infrastructure.*;
import io.agenticpayments.infrastructure.provider.*;
import io.agenticpayments.sdk.execution.v1.*;
import io.agenticpayments.sdk.execution.v1.compat.LocalFakeExecutionAdapter;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import static io.agenticpayments.externalbinding.ExternalBindingTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real populated V009, including historical SQL checkout fixtures that cannot call V010 stage logic. */
class ExternalBindingMigrationUpgradeTest {
    private static final List<String> PREFIX=List.of("V001__simulator.sql","V002__conflict_evidence.sql","V003__accepted_evidence.sql",
            "V004__recorded_replay.sql","V005__admission_evidence.sql","V006__recorded_run_constraints.sql","V007__durable_ap2_admission.sql",
            "V008__durable_checkout_lifecycle.sql","V009__provider_compatibility_journal.sql");
    @Test void populatedV009PreservesFinancialCommerceProviderHistoryAndDoesNotInventObservationTime() throws Exception {
        DataSource admin=database();String schema="external_upgrade_"+UUID.randomUUID().toString().replace("-","");
        String quoted=quoted(schema);boolean created=false;var isolated=database();isolated.setCurrentSchema(schema);
        try{
            try(var connection=admin.getConnection();var statement=connection.createStatement()){statement.execute("CREATE SCHEMA "+quoted);created=true;}
            Map<Integer,String> prefix=new TreeMap<>();
            try(var connection=isolated.getConnection();var statement=connection.createStatement()){
                connection.setAutoCommit(false);
                try(var rows=statement.executeQuery("SELECT current_schema()")){assertTrue(rows.next());assertEquals(schema,rows.getString(1));}
                statement.execute("CREATE TABLE schema_migrations(version int PRIMARY KEY,digest text NOT NULL)");
                for(int n=0;n<PREFIX.size();n++){
                    String sql=resource(PREFIX.get(n)),digest=PaymentRules.sha256(sql);prefix.put(n+1,digest);
                    for(String part:sql.split(";"))if(!part.isBlank())statement.execute(part);
                    try(var insert=connection.prepareStatement("INSERT INTO schema_migrations VALUES(?,?)")){insert.setInt(1,n+1);insert.setString(2,digest);insert.executeUpdate();}
                }connection.commit();
            }
            UUID run=UUID.randomUUID();Session customer=session("external-upgrade",run,"customer-demo"),graph=session("external-upgrade",run,"agent-demo");
            PaymentService nativePayment=PaymentServices.local(isolated);nativePayment.seedRun(customer,NOW,100000);
            Result original=nativePayment.create(customer,key(),new CreateCommand("merchant-demo","success",JdbcPaymentStore.NATIVE_PROFILE,List.of()));UUID nativeIntent=uuid(original.body().get("intentId"));
            Result quote=nativePayment.quote(customer,nativeIntent,key(),new QuoteCommand(1,"cart-1250-eur",TENDER));
            Result approved=nativePayment.approve(customer,nativeIntent,key(),approval(quote,"eur"));
            io.agenticpayments.core.HistoricalPaymentRows.completeApproved(isolated,customer,nativeIntent,uuid(approved.body().get("attemptId")));
            assertEquals("SUCCEEDED",nativePayment.status(customer,nativeIntent).body().get("state"));

            ConfigurationIdentity identity=identity(graph);UUID purchase=UUID.randomUUID(),command=UUID.randomUUID(),response=UUID.randomUUID(),claim=UUID.randomUUID();
            // Explicit historical row fixture: current JdbcCheckoutLifecycleTransactions.stage requires V010's seal table.
            var input=new CheckoutLifecycle.StageCommand(CheckoutSessions.Operation.CREATE,0,"merchant-demo","binding-config","eur",List.of("demo-item"));
            try(var transport=new AcpHttpCheckoutSessions(new AcpHttpCheckoutSessions.Configuration(identity.binding(),java.net.URI.create("http://127.0.0.1:9"),true,Duration.ofMillis(100),Duration.ofSeconds(1)),SECRET.toCharArray())){
                var request=new CheckoutCommandTransport.Request(identity.binding(),CheckoutSessions.Operation.CREATE,null,"eur",List.of(new CheckoutSessions.ItemReference("demo-item")),key(),key());
                var prepared=transport.prepare(request);byte[] bytes=document("eur",1250,"demo-item",NOW.plusSeconds(900));
                var result=new CheckoutSessions.Result(201,CheckoutSessions.Code.OK,CheckoutSessions.Observation.OBSERVED,CheckoutSessions.Metadata.absent(),
                        new CheckoutSessions.Document(new CheckoutSessions.Session(identity.binding(),SESSION,"eur"),"ready_for_payment","2026-04-17",bytes));
                var projection=new Purchase(purchase,identity,"eur",SESSION,2,"ready_for_payment",PaymentRules.sha256(Base64.getEncoder().encodeToString(bytes)),command,false);
                execute(isolated,"INSERT INTO checkout_configurations VALUES(?,?,?,?,?,?,?)",graph.tenantId(),run,graph.principalId(),"merchant-demo","binding-config","v1",JSON.writeValueAsString(identity));
                execute(isolated,"INSERT INTO checkout_current_configurations VALUES(?,?,?,?,?,?)",graph.tenantId(),run,graph.principalId(),"merchant-demo","binding-config","v1");
                execute(isolated,"INSERT INTO checkout_purchases VALUES(?,?,?,?,?,?,?,?)",graph.tenantId(),run,graph.principalId(),purchase,"merchant-demo","binding-config","v1",JSON.writeValueAsString(projection));
                var payload=new LinkedHashMap<String,Object>();payload.put("input",input);payload.put("configuration",identity);payload.put("prepared",prepared);payload.put("stagedRevision",1);
                execute(isolated,"INSERT INTO checkout_lifecycle_commands VALUES(?,?,?,?,?,?,?,?,'RECORDED',NULL,'OK','OBSERVED',201,?)",
                        graph.tenantId(),run,graph.principalId(),purchase,command,"historical-accepted-create",historicalFingerprint(),JSON.writeValueAsString(payload),response);
                execute(isolated,"INSERT INTO checkout_lifecycle_outcomes VALUES(?,?,?,?,?,?,?,?,?,false)",graph.tenantId(),run,graph.principalId(),purchase,command,response,claim,
                        PaymentRules.sha256(JSON.writeValueAsString(prepared)).substring(7),JSON.writeValueAsString(result));
            }
            retainProviderHistory(isolated);
            Map<String,List<String>> before=snapshot(isolated,schema);
            assertEquals(1,before.get("checkout_lifecycle_outcomes").size());assertEquals(1,before.get("provider_compatibility_bindings").size());
            assertEquals(1,before.get("provider_compatibility_evidence").size());assertFalse(before.get("native_authorities").isEmpty());
            DatabaseMigrator.migrate(isolated);DatabaseMigrator.migrate(isolated);
            nativePayment.reconcile(customer,nativeIntent,false); // No false conflict after legacy-route backfill.
            Map<String,List<String>> after=snapshot(isolated,schema);
            for(var retained:before.entrySet())assertEquals(retained.getValue(),after.get(retained.getKey()),"Historical rows changed: "+retained.getKey());
            assertEquals(List.of(),after.get(BINDINGS));assertEquals(0,scalar(isolated,"SELECT count(*) FROM checkout_lifecycle_outcomes WHERE observed_at IS NOT NULL"));
            Map<Integer,String> history=new TreeMap<>();
            try(var connection=isolated.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT version,digest FROM schema_migrations ORDER BY version")){
                while(rows.next())history.put(rows.getInt(1),rows.getString(2));
            }
            assertEquals(11,history.size());for(var entry:prefix.entrySet())assertEquals(entry.getValue(),history.get(entry.getKey()));
            assertEquals(PaymentRules.sha256(resource("V010__external_checkout_authority_binding.sql")),history.get(10));
            assertEquals(PaymentRules.sha256(resource("V011__provider_admission_routes.sql")),history.get(11));
            io.agenticpayments.core.HistoricalPaymentRows.assertLegacyRoutes(isolated,1);
            assertEquals("SUCCEEDED",PaymentServices.local(isolated).status(customer,nativeIntent).body().get("state"));
            var domain=new CoordinationDomain();
            try(var transport=new AcpHttpCheckoutSessions(new AcpHttpCheckoutSessions.Configuration(identity.binding(),java.net.URI.create("http://127.0.0.1:9"),true,Duration.ofMillis(100),Duration.ofSeconds(1)),SECRET.toCharArray())){
                CheckoutConfigurationResolver resolver=id->new Resolved(identity,transport);
                PaymentService enabled=payment(isolated,graph,identity,resolver,domain,new JdbcSimulatorAdapter(isolated));
                var draft=enabled.create(graph,key(),new CreateCommand("merchant-demo","success",PROFILE,List.of()));
                assertEquals("UNSUPPORTED_CAPABILITY",assertThrows(PaymentException.class,()->enabled.quoteExternal(graph,uuid(draft.body().get("intentId")),key(),new ExternalQuoteCommand(1,purchase,2,response,TENDER))).code());
                assertEquals(0,scalar(isolated,"SELECT count(*) FROM "+BINDINGS));
                assertEquals(1,scalar(isolated,"SELECT count(*) FROM payment_attempts"));
                DatabaseMigrator.migrate(isolated);
                assertEquals(0,scalar(isolated,"SELECT count(*) FROM checkout_lifecycle_outcomes WHERE observed_at IS NOT NULL"));
            }
        }finally{
            // The exact randomly created schema is validated before this bounded test cleanup.
            if(created)try(var connection=admin.getConnection();var statement=connection.createStatement()){statement.execute("DROP SCHEMA "+quoted(schema)+" CASCADE");}
        }
    }
    /** Independently encode the published historical Stage identity, rather than invoking current policy. */
    private static String historicalFingerprint()throws Exception{
        var bytes=new ByteArrayOutputStream();var output=new DataOutputStream(bytes);
        for(String value:List.of("checkout-lifecycle-0.1","CREATE","0","merchant-demo","binding-config","eur")){
            byte[] field=value.getBytes(StandardCharsets.UTF_8);output.writeInt(field.length);output.write(field);
        }
        output.writeInt(1);byte[] item="demo-item".getBytes(StandardCharsets.UTF_8);output.writeInt(item.length);output.write(item);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    }
    private static void retainProviderHistory(DataSource database){
        var binding=new ExecutionBinding("simulator","upgrade-account","eu-test","upgrade-config","1","upgrade-credential","1","platform-owner");
        var config=new ProviderJournalConfiguration("external-upgrade","merchant-demo","old-provider-run",binding,ProviderJournalConfiguration.MAPPING_POLICY_VERSION,"upgrade-scenario","1",ignored->"success");
        var offer=LocalFakeExecutionAdapter.offer();var combination=offer.combinations().getFirst();var money=new ExactMoney("EUR",2,1250);
        var requirement=new CapabilitySelection.Requirement(ExecutionOffer.VERSION,offer.profileVersion(),offer.providerId(),offer.implementationRevision(),binding.accountRef(),binding.region(),
                combination.methodId(),combination.credentialFormatId(),combination.actions(),OperationPurpose.CHARGE,combination.preparationEffect(),combination.capabilities(),money);
        var operation=ExecutionOperation.create(new ExecutionIdentity("external-upgrade","merchant-demo","old-provider-run","old-intent","old-attempt","old-operation","old-key"),
                binding,CapabilitySelection.select(offer,requirement,binding),money,OperationPurpose.CHARGE);
        var journal=new JdbcCompatibilityBindingJournal(database,config);
        var bridge=new LocalFakeExecutionAdapter(new JdbcSimulatorAdapter(database),journal,binding,config.tenantId(),config.merchantId(),config.runId(),NOW,NOW.plusSeconds(600),ignored->"success");
        bridge.prepare(operation);assertEquals(ExecutionEvidence.State.UNKNOWN,bridge.reconcile(operation).state());
    }
    private static String quoted(String schema){if(!schema.matches("external_upgrade_[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid isolated schema");return '"'+schema+'"';}
    private static String resource(String name)throws Exception{
        try(var input=ExternalBindingMigrationUpgradeTest.class.getResourceAsStream("/db/"+name)){assertNotNull(input);return new String(input.readAllBytes(),StandardCharsets.UTF_8);}
    }
    private static Map<String,List<String>> snapshot(DataSource database,String schema)throws Exception{
        Map<String,List<String>> result=new TreeMap<>();List<String> names=new ArrayList<>();
        try(var connection=database.getConnection();var statement=connection.prepareStatement("SELECT table_name FROM information_schema.tables WHERE table_schema=? AND table_type='BASE TABLE' AND table_name<>'schema_migrations' ORDER BY table_name")){
            statement.setString(1,schema);try(var rows=statement.executeQuery()){while(rows.next())names.add(rows.getString(1));}
        }
        for(String name:names){
            if(!name.matches("[a-z][a-z0-9_]*"))throw new IllegalArgumentException("Unexpected isolated table");
            String expression=name.equals("checkout_lifecycle_outcomes")?"(to_jsonb(t)-'observed_at')::text":"to_jsonb(t)::text";
            List<String> rows=new ArrayList<>();
            try(var connection=database.getConnection();var statement=connection.createStatement();var values=statement.executeQuery("SELECT "+expression+" FROM \""+name+"\" t ORDER BY 1")){
                while(values.next())rows.add(values.getString(1));
            }result.put(name,rows);
        }return result;
    }
}
