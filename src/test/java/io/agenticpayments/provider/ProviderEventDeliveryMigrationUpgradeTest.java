package io.agenticpayments.provider;

import io.agenticpayments.infrastructure.DatabaseMigrator;
import io.agenticpayments.sdk.execution.v1.*;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static io.agenticpayments.provider.ProviderEventTestSupport.*;
import static io.agenticpayments.provider.ProviderJournalTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class ProviderEventDeliveryMigrationUpgradeTest {
    @Test void populatedV014ManualHistorySurvivesV015WithoutAutomaticAdoption()throws Exception{
        var admin=database();String schema="delivery_upgrade_"+UUID.randomUUID().toString().replace("-","");
        var isolated=new PGSimpleDataSource();isolated.setURL(System.getenv("PAYMENTS_DB_URL"));isolated.setUser(System.getenv("PAYMENTS_DB_USER"));isolated.setPassword(System.getenv("PAYMENTS_DB_PASSWORD"));isolated.setCurrentSchema(schema);
        boolean created=false;
        try{
            try(var c=admin.getConnection();var s=c.createStatement()){s.execute("CREATE SCHEMA \""+schema+"\"");created=true;}
            var names=List.of("V001__simulator.sql","V002__conflict_evidence.sql","V003__accepted_evidence.sql","V004__recorded_replay.sql","V005__admission_evidence.sql","V006__recorded_run_constraints.sql","V007__durable_ap2_admission.sql","V008__durable_checkout_lifecycle.sql","V009__provider_compatibility_journal.sql","V010__external_checkout_authority_binding.sql","V011__provider_admission_routes.sql","V012__provider_authentication.sql","V013__provider_events.sql","V014__provider_interactions.sql");
            try(var c=isolated.getConnection();var statement=c.createStatement()){
                c.setAutoCommit(false);statement.execute("CREATE TABLE schema_migrations(version int PRIMARY KEY,digest text NOT NULL)");
                for(int n=0;n<names.size();n++){
                    String sql;try(var stream=getClass().getResourceAsStream("/db/"+names.get(n))){assertNotNull(stream);sql=new String(stream.readAllBytes(),StandardCharsets.UTF_8);}
                    for(String part:sql.split(";"))if(!part.isBlank())statement.execute(part);
                    try(var p=c.prepareStatement("INSERT INTO schema_migrations VALUES(?,?)")){p.setInt(1,n+1);p.setString(2,io.agenticpayments.domain.PaymentRules.sha256(sql));p.executeUpdate();}
                }
                c.commit();
            }
            var config=ProviderEventTestSupport.configuration(tenant());var operation=ProviderEventTestSupport.operation(config,"historical");var inbox=inbox(isolated,config);
            String envelope=retain(inbox,operation,"manual-history",ExecutionEvidence.State.PENDING);var verified=inbox.verify(new EventVerificationRequest(operation,envelope));
            var rawBefore=row(isolated,"SELECT row_to_json(t)::text AS value FROM provider_event_envelopes t WHERE envelope_ref=?",envelope);
            var verifiedBefore=row(isolated,"SELECT row_to_json(t)::text AS value FROM provider_verified_events t WHERE verified_ref=?",verified.verifiedEventRef());
            var migrationsBefore=new ArrayList<String>();try(var c=isolated.getConnection();var p=c.prepareStatement("SELECT digest FROM schema_migrations ORDER BY version");var rows=p.executeQuery()){while(rows.next())migrationsBefore.add(rows.getString(1));}
            DatabaseMigrator.migrate(isolated);DatabaseMigrator.migrate(isolated);
            assertEquals(rawBefore,row(isolated,"SELECT row_to_json(t)::text AS value FROM provider_event_envelopes t WHERE envelope_ref=?",envelope));
            assertEquals(verifiedBefore,row(isolated,"SELECT row_to_json(t)::text AS value FROM provider_verified_events t WHERE verified_ref=?",verified.verifiedEventRef()));
            assertEquals(0,count(isolated,"SELECT count(*) FROM provider_event_delivery"));assertEquals(16,count(isolated,"SELECT count(*) FROM schema_migrations"));
            for(int n=0;n<14;n++)assertEquals(migrationsBefore.get(n),row(isolated,"SELECT digest FROM schema_migrations WHERE version=?",n+1).get("digest"));
            assertEquals(ExecutionEvidence.State.PENDING,inbox.normalize(operation,verified).state());
        }finally{if(created)try(var c=admin.getConnection();var statement=c.createStatement()){statement.execute("DROP SCHEMA \""+schema+"\" CASCADE");}}
    }
}
