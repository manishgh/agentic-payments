package io.agenticpayments.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agenticpayments.application.PaymentException;
import io.agenticpayments.application.PaymentService;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.domain.PaymentRules;
import io.agenticpayments.infrastructure.PaymentServices;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.util.*;
import static io.agenticpayments.core.CoreTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Characterized against d1eeef70 before extracting approval policy from JDBC. */
class ApprovalCompatibilityIntegrationTest {
    private record Fixture(DataSource ds,Session session,PaymentService service,Result quote,String profile) {
        UUID intent(){return uuid(quote.body().get("intentId"));}
        ApproveCommand command(){return approval(quote);}
    }
    private static Fixture quotedProfile(String profile) {
        DataSource ds=database();Session session=isolated(ds);PaymentService service=PaymentServices.local(ds);
        Result created=service.create(session,key(),new CreateCommand("merchant-demo","success",profile,List.of("single-tender","exact-money")));
        Result quote=service.quote(session,uuid(created.body().get("intentId")),key(),new QuoteCommand(version(created),"cart-1250-eur",
                List.of(new Tender("simulator","synthetic-card","fixture-credential-1"))));
        return new Fixture(ds,session,service,quote,profile);
    }
    @Test void originalRequestDigestAndResponseBytesSurviveRestartAndCurrentAuthorizationStillPrecedesReplay() throws Exception {
        for(String profile:List.of(PaymentRules.PROFILE,"local-ap2-acp-single-tender-0.1")) {
            Fixture f=quotedProfile(profile);String key=key();ApproveCommand command=f.command();
            Result first=f.service().approve(f.session(),f.intent(),key,command);
            String request="{\"authorityReference\":\"fixture-grant-1\",\"checkoutDigest\":\""+command.checkoutDigest()
                +"\",\"checkoutRevision\":"+command.checkoutRevision()+",\"expectedVersion\":"+command.expectedVersion()+"}";
            String authority=profile.equals(PaymentRules.PROFILE)?"local-fake":"ap2-v0.2-direct-tap-synthetic-0.1";
            String response="{\"attemptId\":\""+first.body().get("attemptId")+"\",\"authorityProfile\":\""+authority
                +"\",\"authorityVersion\":1,\"contractVersion\":\"simulator-api-0.1-draft\",\"correlationId\":\""+first.body().get("correlationId")
                +"\",\"executionOwner\":\"simulator-core\",\"intentId\":\""+f.intent()
                +"\",\"reserved\":{\"currency\":\"EUR\",\"minorUnits\":\"1250\"},\"state\":\"APPROVED\",\"version\":3}";
            var stored=row(f.ds(),"SELECT request_digest,response_status,response_body FROM commands WHERE tenant_id=? AND run_id=? AND route='POST /intents/{id}/approve' AND idempotency_key=?",
                f.session().tenantId(),f.session().runId(),key);
            assertEquals(PaymentRules.sha256(request),stored.get("request_digest"));
            assertEquals(200,((Number)stored.get("response_status")).intValue());assertEquals(response,stored.get("response_body"));
            PaymentService restarted=PaymentServices.local(f.ds());
            Result replay=restarted.approve(f.session(),f.intent(),key,command);assertTrue(replay.replayed());
            var json=new ObjectMapper();assertEquals(json.readTree(response),json.valueToTree(replay.body()));
            Session deniedMerchant=new Session(f.session().tenantId(),f.session().runId(),f.session().principalId(),f.session().roles(),Set.of(),f.session().allowedGrants());
            assertEquals("RESOURCE_NOT_FOUND",assertThrows(PaymentException.class,()->restarted.approve(deniedMerchant,f.intent(),key,command)).code());
            Session deniedGrant=new Session(f.session().tenantId(),f.session().runId(),f.session().principalId(),f.session().roles(),f.session().allowedMerchants(),Set.of());
            assertEquals("RESOURCE_NOT_FOUND",assertThrows(PaymentException.class,()->restarted.approve(deniedGrant,f.intent(),key,command)).code());
            restarted.advanceClock(f.session(),CLOCK.plusSeconds(3600));
            assertTrue(restarted.approve(f.session(),f.intent(),key,command).replayed(),"Historical approval does not reapply expiry/state checks");
            assertEquals(1,scalar(f.ds(),"SELECT count(*) FROM payment_attempts WHERE tenant_id=? AND run_id=?",f.session().tenantId(),f.session().runId()));
            assertEquals(1,scalar(f.ds(),"SELECT count(*) FROM reservations WHERE tenant_id=? AND run_id=?",f.session().tenantId(),f.session().runId()));
            assertEquals(0,scalar(f.ds(),"SELECT count(*) FROM financial_operations WHERE tenant_id=? AND run_id=?",f.session().tenantId(),f.session().runId()));
        }
    }
    @Test void conflictingReplayPrecedesDecodingTheHistoricResponse() throws Exception {
        Fixture f=quotedProfile(PaymentRules.PROFILE);String key=key();
        f.service().approve(f.session(),f.intent(),key,f.command());
        try(var connection=f.ds().getConnection();var update=connection.prepareStatement("UPDATE commands SET response_body=? WHERE tenant_id=? AND run_id=? AND idempotency_key=?")) {
            update.setString(1,"{malformed-historical-response");update.setString(2,f.session().tenantId());update.setObject(3,f.session().runId());update.setString(4,key);
            assertEquals(1,update.executeUpdate());
        }
        var different=new ApproveCommand(3,1,f.command().checkoutDigest(),"fixture-grant-1");
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(PaymentException.class,()->f.service().approve(f.session(),f.intent(),key,different)).code());
        assertThrows(IllegalStateException.class,()->f.service().approve(f.session(),f.intent(),key,f.command()));
        assertEquals(1,scalar(f.ds(),"SELECT count(*) FROM payment_attempts WHERE tenant_id=? AND run_id=?",f.session().tenantId(),f.session().runId()));
    }
}
