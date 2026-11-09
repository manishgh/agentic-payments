package io.agenticpayments.core;

import io.agenticpayments.application.PaymentService;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.infrastructure.*;
import io.agenticpayments.infrastructure.provider.*;
import io.agenticpayments.sdk.execution.v1.ExecutionBinding;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static io.agenticpayments.core.CoreTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** The new interaction tables cannot adopt or rewrite a populated historical provider route. */
class ProviderInteractionsMigrationUpgradeTest {
    @Test void populatedV011ProviderHistoryAndOfferSurviveUpgradeAndReconciliation()throws Exception {
        DataSource admin=database();String schema="interactions_upgrade_"+UUID.randomUUID().toString().replace("-","");
        if(!schema.matches("interactions_upgrade_[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid test schema");
        var isolated=new PGSimpleDataSource();isolated.setURL(System.getenv("PAYMENTS_DB_URL"));isolated.setUser(System.getenv("PAYMENTS_DB_USER"));isolated.setPassword(System.getenv("PAYMENTS_DB_PASSWORD"));isolated.setCurrentSchema(schema);
        boolean created=false;
        try {
            try(var c=admin.getConnection();var s=c.createStatement()){s.execute("CREATE SCHEMA \""+schema+"\"");created=true;}
            List<String> names=List.of("V001__simulator.sql","V002__conflict_evidence.sql","V003__accepted_evidence.sql","V004__recorded_replay.sql","V005__admission_evidence.sql","V006__recorded_run_constraints.sql","V007__durable_ap2_admission.sql","V008__durable_checkout_lifecycle.sql","V009__provider_compatibility_journal.sql","V010__external_checkout_authority_binding.sql","V011__provider_admission_routes.sql");
            var prefix=new TreeMap<Integer,String>();
            try(var c=isolated.getConnection();var s=c.createStatement()) {
                c.setAutoCommit(false);s.execute("CREATE TABLE schema_migrations(version int PRIMARY KEY,digest text NOT NULL)");
                for(int i=0;i<names.size();i++) {
                    String sql=resource(names.get(i)),digest=io.agenticpayments.domain.PaymentRules.sha256(sql);prefix.put(i+1,digest);
                    for(String part:sql.split(";"))if(!part.isBlank())s.execute(part);
                    try(var p=c.prepareStatement("INSERT INTO schema_migrations VALUES(?,?)")){p.setInt(1,i+1);p.setString(2,digest);p.executeUpdate();}
                }
                c.commit();
            }
            Session session=session("interactions-upgrade",UUID.randomUUID());var binding=new ExecutionBinding("simulator","upgrade-account","eu-test","upgrade-config","1","fixture-credential-1","1","owner");
            var registration=new SyntheticProviderRegistration(session.tenantId(),"merchant-demo",session.runId(),binding,CLOCK,CLOCK.plusSeconds(600));
            PaymentService service=PaymentServices.providerJournal(isolated,registration);service.seedRun(session,CLOCK,1250);
            var create=service.create(session,key(),new CreateCommand("merchant-demo","success",JdbcPaymentStore.NATIVE_PROFILE,List.of()));UUID intent=uuid(create.body().get("intentId"));
            var quote=service.quote(session,intent,key(),new QuoteCommand(version(create),"cart-1250-eur",List.of(new Tender("simulator","synthetic-card","fixture-credential-1"))));
            var approved=service.approve(session,intent,key(),approval(quote));service.execute(session,intent,key(),new ExecuteCommand(version(approved),uuid(approved.body().get("attemptId"))));
            service.runNext(session);service.runNext(session);assertEquals("SUCCEEDED",service.status(session,intent).body().get("state"));
            var before=snapshot(isolated,schema);assertFalse(before.get("provider_compatibility_bindings").isEmpty());
            DatabaseMigrator.migrate(isolated);DatabaseMigrator.migrate(isolated);
            PaymentServices.providerJournal(isolated,registration).reconcile(session,intent,false);
            var after=snapshot(isolated,schema);for(var table:before.entrySet())assertEquals(table.getValue(),after.get(table.getKey()),table.getKey());
            assertEquals(14,scalar(isolated,"SELECT count(*) FROM schema_migrations"));
            for(var migration:prefix.entrySet())assertEquals(migration.getValue(),row(isolated,"SELECT digest FROM schema_migrations WHERE version=?",migration.getKey()).get("digest"));
            for(String name:List.of("V012__provider_authentication.sql","V013__provider_events.sql","V014__provider_interactions.sql")) {
                int version=Integer.parseInt(name.substring(1,4));assertEquals(io.agenticpayments.domain.PaymentRules.sha256(resource(name)),row(isolated,"SELECT digest FROM schema_migrations WHERE version=?",version).get("digest"));
            }
            assertEquals(SyntheticProviderRegistration.PROFILE,row(isolated,"SELECT route FROM payment_operation_routes").get("route"));
            assertEquals(0,scalar(isolated,"SELECT count(*) FROM customer_action_provider_bindings"));assertEquals(0,scalar(isolated,"SELECT count(*) FROM provider_event_envelopes"));
        } finally {
            if(created)try(var c=admin.getConnection();var s=c.createStatement()){s.execute("DROP SCHEMA \""+schema+"\" CASCADE");}
        }
    }
    private static String resource(String name)throws Exception{try(var input=ProviderInteractionsMigrationUpgradeTest.class.getResourceAsStream("/db/"+name)){assertNotNull(input);return new String(input.readAllBytes(),StandardCharsets.UTF_8);}}
    private static Map<String,List<String>> snapshot(DataSource ds,String schema)throws Exception {
        var result=new TreeMap<String,List<String>>();
        try(var c=ds.getConnection();var p=c.prepareStatement("SELECT table_name FROM information_schema.tables WHERE table_schema=? AND table_name<>'schema_migrations' AND table_type='BASE TABLE' ORDER BY table_name")) {
            p.setString(1,schema);var tables=new ArrayList<String>();try(var rows=p.executeQuery()){while(rows.next())tables.add(rows.getString(1));}
            for(String table:tables) {
                if(!table.matches("[a-z_]+"))throw new IllegalStateException("Invalid table");var values=new ArrayList<String>();
                try(var s=c.createStatement();var rows=s.executeQuery("SELECT row_to_json(t)::text FROM \""+table+"\" t ORDER BY row_to_json(t)::text")){while(rows.next())values.add(rows.getString(1));}result.put(table,values);
            }
        }
        return result;
    }
}
