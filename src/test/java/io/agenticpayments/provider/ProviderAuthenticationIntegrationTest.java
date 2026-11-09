package io.agenticpayments.provider;

import io.agenticpayments.application.*;
import io.agenticpayments.infrastructure.DatabaseMigrator;
import io.agenticpayments.infrastructure.provider.*;
import io.agenticpayments.sdk.execution.v1.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static io.agenticpayments.provider.ProviderJournalTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent SQL inspection of the nonfinancial synthetic authentication ledger. */
class ProviderAuthenticationIntegrationTest {
    private static final class Fixture {
        final javax.sql.DataSource database=database();
        final ProviderJournalConfiguration config=configuration("auth-"+UUID.randomUUID(),"merchant-demo","run-"+UUID.randomUUID());
        final ExecutionOperation operation=operation(config,"caller-auth","operation-auth");
        final ProviderActionBinding binding=new ProviderActionBinding(operation,new TrustedActionDescriptor(ExecutionOffer.ActionType.SYNTHETIC_CHALLENGE,
                "action-original",operation.fingerprint(),operation.binding().digest(),EXPIRY));
        final UUID request=UUID.randomUUID();
        Fixture(){DatabaseMigrator.migrate(database);}
        JdbcSyntheticAuthentication adapter(){return new JdbcSyntheticAuthentication(database,config);}
        long decisions(){return count(database,"SELECT count(*) FROM provider_authentication_actions WHERE tenant_id=? AND observation_json IS NOT NULL",config.tenantId());}
    }
    @Test void immutableActionAndDecisionSurviveReconstructionWithoutFinancialEvidence() {
        var f=new Fixture();f.adapter().register(f.binding);assertTrue(f.adapter().resolve(f.binding).isEmpty());
        f.adapter().decide(f.binding,"customer",f.request,"approve",NOW);var original=f.adapter().resolve(f.binding).orElseThrow();
        f.adapter().register(f.binding);f.adapter().decide(f.binding,"customer",f.request,"approve",EXPIRY.plusSeconds(1));
        assertEquals(original,f.adapter().resolve(f.binding).orElseThrow());assertEquals(AuthenticationObservation.Outcome.VERIFIED,original.outcome());
        assertEquals(NOW,original.observedAt());assertEquals(1,f.decisions());
        assertEquals(0,count(f.database,"SELECT count(*) FROM provider_compatibility_evidence WHERE tenant_id=?",f.config.tenantId()));
        assertEquals(0,count(f.database,"SELECT count(*) FROM provider_compatibility_bindings WHERE tenant_id=?",f.config.tenantId()));
    }
    @Test void changedDescriptorSubjectDecisionAndRequestNeverReplaceOriginal() {
        var f=new Fixture();f.adapter().register(f.binding);
        var changed=new ProviderActionBinding(f.operation,new TrustedActionDescriptor(ExecutionOffer.ActionType.SYNTHETIC_CHALLENGE,
                "action-original",f.operation.fingerprint(),f.operation.binding().digest(),EXPIRY.plusSeconds(1)));
        assertThrows(ApplicationException.class,()->f.adapter().register(changed));
        f.adapter().decide(f.binding,"customer",f.request,"deny",NOW);var original=f.adapter().resolve(f.binding).orElseThrow();
        assertThrows(ApplicationException.class,()->f.adapter().decide(f.binding,"other",f.request,"deny",NOW));
        assertThrows(ApplicationException.class,()->f.adapter().decide(f.binding,"customer",f.request,"approve",NOW));
        assertThrows(ApplicationException.class,()->f.adapter().decide(f.binding,"customer",UUID.randomUUID(),"deny",NOW));
        assertEquals(original,f.adapter().resolve(f.binding).orElseThrow());assertEquals(AuthenticationObservation.Outcome.DENIED,original.outcome());
    }
    @Test void exactExpiryAndUnknownActionFailClosed() {
        var f=new Fixture();assertThrows(ApplicationException.class,()->f.adapter().decide(f.binding,"customer",f.request,"approve",NOW));
        f.adapter().register(f.binding);
        assertThrows(ApplicationException.class,()->f.adapter().decide(f.binding,"customer",f.request,"approve",EXPIRY));assertEquals(0,f.decisions());
        var other=new JdbcSyntheticAuthentication(f.database,"another-tenant",f.config.merchantId(),f.config.runId(),f.config.binding());
        assertThrows(ApplicationException.class,()->other.resolve(f.binding));
    }
    @Test void concurrentOppositeDecisionsHaveOneDurableWinner()throws Exception {
        var f=new Fixture();f.adapter().register(f.binding);var barrier=new CyclicBarrier(2);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> results=new ArrayList<>();
            for(String decision:List.of("approve","deny"))results.add(executor.submit(()->{
                barrier.await(10,TimeUnit.SECONDS);try{f.adapter().decide(f.binding,"customer",f.request,decision,NOW);return true;}
                catch(ApplicationException conflict){return false;}
            }));
            int winners=0;for(var result:results)if(result.get(15,TimeUnit.SECONDS))winners++;
            assertEquals(1,winners);assertEquals(1,f.decisions());assertTrue(f.adapter().resolve(f.binding).isPresent());
        }
    }
    @Test void corruptStoredDecisionCannotManufactureSuccess() {
        var f=new Fixture();f.adapter().register(f.binding);f.adapter().decide(f.binding,"customer",f.request,"deny",NOW);
        execute(f.database,"UPDATE provider_authentication_actions SET decision='approve' WHERE tenant_id=?",f.config.tenantId());
        assertThrows(ApplicationException.class,()->f.adapter().resolve(f.binding));
    }
    @Test void committedActionAndDecisionRecoverAcrossIndependentJvms()throws Exception {
        var fixture=new Fixture();assertEquals(73,child(fixture,"register"));
        assertTrue(fixture.adapter().resolve(fixture.binding).isEmpty());assertEquals(73,child(fixture,"decide"));
        var original=fixture.adapter().resolve(fixture.binding).orElseThrow();assertEquals(0,child(fixture,"recover"));
        assertEquals(original,fixture.adapter().resolve(fixture.binding).orElseThrow());assertEquals(1,fixture.decisions());
    }
    private static int child(Fixture fixture,String mode)throws Exception {
        String executable=java.nio.file.Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var process=new ProcessBuilder(executable,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                ProviderAuthenticationCrashProcess.class.getName(),mode,fixture.config.tenantId(),fixture.config.merchantId(),fixture.config.runId(),fixture.request.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30,TimeUnit.SECONDS),"Authentication child exceeded deadline");
            String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(process.exitValue()==73?"":"PROVIDER_AUTHENTICATION_OK",output);return process.exitValue();
        }finally{if(process.isAlive())process.destroyForcibly().waitFor(5,TimeUnit.SECONDS);}
    }
}
