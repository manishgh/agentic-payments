package io.agenticpayments.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agenticpayments.application.*;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.composition.NativeComposition;
import io.agenticpayments.infrastructure.*;
import io.agenticpayments.infrastructure.provider.*;
import io.agenticpayments.sdk.*;
import io.agenticpayments.sdk.execution.v1.*;
import io.agenticpayments.sdk.execution.v1.compat.LocalFakeExecutionAdapter;
import org.junit.jupiter.api.Test;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import java.util.logging.Logger;
import static io.agenticpayments.core.CoreTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Financial admission, retained event provenance and independent journal/core transaction witnesses. */
class ProviderEventAdmissionIntegrationTest {
    static final byte[] EVENT_KEY="synthetic-core-event-key-32-bytes-only".getBytes(StandardCharsets.US_ASCII);
    static final ExecutionBinding BINDING=new ExecutionBinding("simulator","interaction-account","eu-test","interaction-config","1","fixture-credential-1","1","platform-owner");
    static SyntheticProviderRegistration registration(Session s){return SyntheticProviderRegistration.interactions(s.tenantId(),"merchant-demo",s.runId(),BINDING,CLOCK,CLOCK.plusSeconds(600));}
    static ProviderEventConfiguration eventConfiguration(SyntheticProviderRegistration registration){var c=registration.configuration();return new ProviderEventConfiguration(c.tenantId(),c.merchantId(),c.runId(),c.binding(),c.offer(),"core-event-policy","1","core-event-key","1");}
    static ProviderInteractionServices services(DataSource ds,Session s){var r=registration(s);return PaymentServices.providerInteractions(ds,r,eventConfiguration(r),new HmacProviderEventAuthenticator(EVENT_KEY));}
    record Fixture(DataSource ds,Session session,SyntheticProviderRegistration registration,ProviderInteractionServices services,UUID intent,Result approved){
        PaymentService payments(){return services.payments();}
        ProviderEventOperations events(){return services.events();}
        void execute(){payments().execute(session,intent,key(),new ExecuteCommand(version(approved),uuid(approved.body().get("attemptId"))));}
        void dispatch(){execute();payments().runNext(session);payments().runNext(session);}
        String state(){return payments().status(session,intent).body().get("state").toString();}
        String exposure(){return payments().status(session,intent).body().get("exposureState").toString();}
        long count(String table)throws Exception{return scalar(ds,"SELECT count(*) FROM "+table+" WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId());}
        long providerCount(String table)throws Exception{return scalar(ds,"SELECT count(*) FROM "+table+" WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId().toString());}
        ProviderExecutionBinding binding()throws Exception{
            var r=row(ds,"SELECT o.* FROM financial_operations o JOIN payment_attempts a USING(tenant_id,run_id,attempt_id) WHERE o.tenant_id=? AND o.run_id=? AND a.intent_id=?",session.tenantId(),session.runId(),intent);
            var identity=new OperationIdentity(new ExecutionScope(session.tenantId(),session.runId(),"merchant-demo"),intent,uuid(approved.body().get("attemptId")),(UUID)r.get("operation_id"),(UUID)r.get("adapter_key"),r.get("request_digest").toString());
            return new JdbcProviderExecutionBindings(ds).load(identity);
        }
        String event(String id,ExecutionEvidence.State state)throws Exception{byte[] raw=body(binding(),registration,id,state);return events().retain(session,intent,raw,sign(raw));}
    }
    static Fixture approved(String scenario){
        var ds=database();DatabaseMigrator.migrate(ds);var s=session("provider-event-core",UUID.randomUUID());var service=services(ds,s);service.payments().seedRun(s,CLOCK,5000);
        return approved(ds,s,service,scenario);
    }
    static Fixture approved(DataSource ds,Session s,ProviderInteractionServices service,String scenario){
        var c=service.payments().create(s,key(),new CreateCommand("merchant-demo",scenario,JdbcPaymentStore.NATIVE_PROFILE,List.of("single-tender","exact-money")));
        UUID intent=uuid(c.body().get("intentId"));var q=service.payments().quote(s,intent,key(),new QuoteCommand(version(c),"cart-1250-eur",List.of(new Tender("simulator","synthetic-card","fixture-credential-1"))));
        return new Fixture(ds,s,registration(s),service,intent,service.payments().approve(s,intent,key(),approval(q)));
    }
    static byte[] body(ProviderExecutionBinding binding,SyntheticProviderRegistration registration,String id,ExecutionEvidence.State state){
        var op=binding.operation();var n=new ObjectMapper().createObjectNode();n.put("version","synthetic-provider-event-1");n.put("eventId",id);n.put("operationFingerprint",op.fingerprint());
        n.put("bindingDigest",op.binding().digest());n.put("accountRef",op.binding().accountRef());n.put("currency",op.money().currency());n.put("exponent",op.money().exponent());n.put("minorUnits",Long.toString(op.money().minorUnits()));
        n.put("providerResourceRef","legacy-resource-"+registration.configuration().expectedLegacy(op).identity().adapterKey());n.put("observedAt",CLOCK.plusSeconds(1).toString());n.put("state",state.name());
        try{return new ObjectMapper().writeValueAsBytes(n);}catch(Exception failure){throw new AssertionError(failure);}
    }
    static String sign(byte[] body){try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(EVENT_KEY,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(body));}catch(Exception failure){throw new AssertionError(failure);}}
    @Test void authorizationMerchantAndAdmissionChecksPrecedeRawStorage()throws Exception{
        var f=approved("delayed-success");byte[] raw="{}".getBytes(StandardCharsets.UTF_8);
        assertThrows(ApplicationException.class,()->f.events().retain(null,f.intent,raw,sign(raw)));
        var unauthorized=new Session(f.session.tenantId(),f.session.runId(),f.session.principalId(),Set.of("intent:read"),f.session.allowedMerchants(),f.session.allowedGrants());
        assertThrows(ApplicationException.class,()->f.events().retain(unauthorized,f.intent,raw,sign(raw)));
        assertThrows(ApplicationException.class,()->f.events().retain(f.session,f.intent,raw,sign(raw)));f.execute();f.payments().runNext(f.session);
        assertThrows(ApplicationException.class,()->f.events().retain(f.session,f.intent,raw,sign(raw)));assertEquals(0,f.providerCount("provider_event_envelopes"));
        f.payments().runNext(f.session);var denied=new Session(f.session.tenantId(),f.session.runId(),f.session.principalId(),f.session.roles(),Set.of("another-merchant"),f.session.allowedGrants());
        assertThrows(ApplicationException.class,()->f.events().retain(denied,f.intent,raw,sign(raw)));
        assertThrows(ApplicationException.class,()->f.events().replay(denied,f.intent,"fabricated"));assertEquals(0,f.providerCount("provider_event_envelopes"));assertEquals("PENDING",f.state());
    }
    @Test void pendingAndUnknownCaptureEventsSettleOriginalOperationAndDuplicatesDoNotRegress()throws Exception{
        for(String scenario:List.of("delayed-success","response-loss-after-acceptance")){
            var f=approved(scenario);f.dispatch();var original=f.binding();assertEquals(scenario.startsWith("response")?"UNKNOWN":"PENDING",f.state());
            String pending=f.event("original-pending",ExecutionEvidence.State.PENDING),capture=f.event("capture",ExecutionEvidence.State.CAPTURED);
            f.events().replay(f.session,f.intent,capture);assertEquals("SUCCEEDED",f.state());assertEquals("CONSUMED",f.exposure());long count=f.count("operation_evidence");
            f.events().replay(f.session,f.intent,capture);assertEquals(count,f.count("operation_evidence"));
            f.events().replay(f.session,f.intent,pending);assertEquals("SUCCEEDED",f.state());assertEquals("CONSUMED",f.exposure());
            long afterLate=f.count("operation_evidence");f.events().replay(f.session,f.intent,pending);assertEquals(afterLate,f.count("operation_evidence"));
            assertEquals(original,f.binding());assertEquals(1,f.count("financial_operations"));assertEquals(1,f.providerCount("provider_compatibility_bindings"));
            var peer=f.registration.configuration().expectedLegacy(original.operation());assertEquals(1,scalar(f.ds,"SELECT count(*) FROM simulator_operation_journal WHERE tenant_id=? AND run_id=? AND adapter_key=?",peer.identity().scope().tenantId(),peer.identity().scope().runId(),peer.identity().adapterKey()));
        }
    }
    @Test void forgedAndSignedMisboundEventsCannotChangeFinancialStateOrFreezeBudget()throws Exception{
        var f=approved("delayed-success");f.dispatch();byte[] capture=body(f.binding(),f.registration,"forged",ExecutionEvidence.State.CAPTURED);
        String forged=f.events().retain(f.session,f.intent,capture,"0".repeat(64));assertThrows(ApplicationException.class,()->f.events().replay(f.session,f.intent,forged));
        byte[] wrong=new String(capture,StandardCharsets.UTF_8).replace("\"1250\"","\"9999\"").getBytes(StandardCharsets.UTF_8);
        String misbound=f.events().retain(f.session,f.intent,wrong,sign(wrong));assertThrows(ApplicationException.class,()->f.events().replay(f.session,f.intent,misbound));
        assertEquals("PENDING",f.state());assertEquals("HELD",f.exposure());assertEquals(0,f.count("disputed_exposure"));assertEquals(false,row(f.ds,"SELECT frozen FROM budgets WHERE tenant_id=? AND run_id=? AND budget_id='budget-eur'",f.session.tenantId(),f.session.runId()).get("frozen"));
    }
    @Test void authenticTerminalContradictionRetainsSettlementAndFreezesDisputedExposure()throws Exception{
        var f=approved("success");f.dispatch();String contradiction=f.event("opposite",ExecutionEvidence.State.NO_EFFECT_FENCED);
        assertThrows(ProviderEvidenceConflict.class,()->f.events().replay(f.session,f.intent,contradiction));assertEquals("SUCCEEDED",f.state());assertEquals("CONSUMED",f.exposure());
        assertEquals(1,f.count("disputed_exposure"));assertEquals(true,row(f.ds,"SELECT frozen FROM budgets WHERE tenant_id=? AND run_id=? AND budget_id='budget-eur'",f.session.tenantId(),f.session.runId()).get("frozen"));
        assertThrows(ProviderEvidenceConflict.class,()->services(f.ds,f.session).payments().reconcile(f.session,f.intent,false));assertEquals(1,f.count("disputed_exposure"));
    }
    @Test void authenticSameIdMutationAndCrossOperationReusePropagateDurableQuarantine()throws Exception{
        for(boolean cross:List.of(false,true)){
            var first=approved("delayed-success");first.dispatch();Fixture second=cross?approved(first.ds,first.session,first.services,"delayed-success"):first;if(cross)second.dispatch();
            String accepted=first.event("same-event",ExecutionEvidence.State.PENDING);first.events().replay(first.session,first.intent,accepted);
            String conflict=second.event("same-event",ExecutionEvidence.State.CAPTURED);assertThrows(ProviderEvidenceConflict.class,()->second.events().replay(second.session,second.intent,conflict));
            assertThrows(ProviderEvidenceConflict.class,()->services(first.ds,first.session).payments().reconcile(first.session,first.intent,false));
            assertEquals(cross?2:1,first.count("disputed_exposure"));assertEquals("PENDING",first.state());assertEquals("HELD",first.exposure());
            assertEquals(cross?2:1,first.providerCount("provider_event_quarantine"));
        }
    }
    @Test void lostCoreCommitAcknowledgementReplaysWithoutNewSettlement()throws Exception{
        var f=approved("delayed-success");f.dispatch();String ref=f.event("capture",ExecutionEvidence.State.CAPTURED);var fault=new EventCommitDataSource(f.ds,"ack");
        assertThrows(ApplicationException.class,()->services(fault,f.session).events().replay(f.session,f.intent,ref));assertTrue(fault.triggered.get());assertEquals("SUCCEEDED",f.state());
        long evidence=f.count("operation_evidence");services(f.ds,f.session).events().replay(f.session,f.intent,ref);assertEquals(evidence,f.count("operation_evidence"));assertEquals("CONSUMED",f.exposure());
    }
    @Test void journalAndCoreCrashGapsRecoverThroughFreshJvmOriginalReference()throws Exception{
        for(String phase:List.of("journal","core")){
            var f=approved("delayed-success");f.dispatch();String ref=f.event("process-capture",ExecutionEvidence.State.CAPTURED);assertEquals(73,child(f,phase,ref));
            assertEquals(phase.equals("journal")?"PENDING":"SUCCEEDED",f.state());assertEquals(phase.equals("journal")?"HELD":"CONSUMED",f.exposure());
            assertEquals(0,child(f,"recover",ref));assertEquals("SUCCEEDED",f.state());assertEquals("CONSUMED",f.exposure());assertEquals(1,f.count("financial_operations"));
            long evidence=f.count("operation_evidence");assertEquals(0,child(f,"recover",ref));assertEquals(evidence,f.count("operation_evidence"));
        }
    }
    @Test void advancedDirectAdaptedAndForwardingPortsPreserveEventComposition()throws Exception{
        var f=approved("delayed-success");f.dispatch();String ref=f.event("wrapped",ExecutionEvidence.State.CAPTURED);var calls=new AtomicInteger();
        advanced(f.ds,f.session,"normal",calls).events().replay(f.session,f.intent,ref);assertEquals("SUCCEEDED",f.state());assertTrue(calls.get()>=4);
    }
    @Test void changedRegistrationOrMissingRouteCannotAdoptRetainedEvents()throws Exception{
        var f=approved("delayed-success");f.dispatch();String ref=f.event("retained",ExecutionEvidence.State.CAPTURED);
        var changed=new ExecutionBinding("simulator","other-account","eu-test","interaction-config","1","fixture-credential-1","1","platform-owner");
        var registration=SyntheticProviderRegistration.interactions(f.session.tenantId(),"merchant-demo",f.session.runId(),changed,CLOCK,CLOCK.plusSeconds(600));
        var replacement=PaymentServices.providerInteractions(f.ds,registration,eventConfiguration(registration),new HmacProviderEventAuthenticator(EVENT_KEY));
        assertThrows(ApplicationException.class,()->replacement.events().replay(f.session,f.intent,ref));assertEquals("PENDING",f.state());
        try(var c=f.ds.getConnection();var p=c.prepareStatement("DELETE FROM payment_operation_routes WHERE tenant_id=? AND run_id=?")){p.setString(1,f.session.tenantId());p.setObject(2,f.session.runId());p.executeUpdate();}
        assertThrows(ApplicationException.class,()->f.events().replay(f.session,f.intent,ref));assertEquals(0,f.providerCount("provider_verified_events"));
    }
    static ProviderInteractionServices advanced(DataSource ds,Session session,String phase,AtomicInteger calls){
        var registration=registration(session);var config=registration.configuration();var journal=new JdbcCompatibilityBindingJournal(ds,config);
        var bridge=new LocalFakeExecutionAdapter(new JdbcSimulatorAdapter(ds),journal,config.binding(),config.tenantId(),config.merchantId(),config.runId(),CLOCK,CLOCK.plusSeconds(600),config::scenarioFor,config.offer());
        var authentication=new JdbcSyntheticAuthentication(ds,config);var inbox=new JdbcProviderEventInbox(ds,eventConfiguration(registration),new HmacProviderEventAuthenticator(EVENT_KEY),new SyntheticProviderEventDecoder(op->"legacy-resource-"+config.expectedLegacy(op).identity().adapterKey()));
        var ports=new ProviderInteractionPorts(authentication::register,authentication::resolve,authentication::decide,
                (op,raw,sig)->{calls.incrementAndGet();return inbox.retain(op,raw,sig);},request->{calls.incrementAndGet();return inbox.verify(request);},
                (op,reference)->{calls.incrementAndGet();return inbox.normalize(op,reference);},op->{calls.incrementAndGet();inbox.requireHealthy(op);});
        ProviderEvidenceVerifier verifier=new ProviderEvidenceVerifier(){
            public ExecutionEvidence verify(ExecutionOperation op,ExecutionEvidence received){calls.incrementAndGet();var result=journal.verify(op,received);if(phase.equals("journal"))Runtime.getRuntime().halt(73);return result;}
            public ExecutionEvidence retained(ExecutionOperation op,ExecutionEvidence.State state){calls.incrementAndGet();return journal.retained(op,state);}
        };
        return PaymentServices.providerInteractions(ds,registration,bridge::prepare,bridge::submit,bridge::reconcile,Optional.of(bridge::fence),verifier,ports,NativeComposition.ports(new JdbcSignedArtifactStore(ds)));
    }
    private static int child(Fixture f,String phase,String ref)throws Exception{
        var java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var process=new ProcessBuilder(java,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),ProviderEventAdmissionCrashProcess.class.getName(),phase,f.session.tenantId(),f.session.runId().toString(),f.intent.toString(),ref).redirectErrorStream(true).start();
        try{assertTrue(process.waitFor(30,TimeUnit.SECONDS));String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertEquals(phase.equals("recover")?"PROVIDER_EVENT_CORE_OK":"",output);return process.exitValue();}
        finally{if(process.isAlive())process.destroyForcibly().waitFor(5,TimeUnit.SECONDS);}
    }
    static final class EventCommitDataSource implements DataSource {
        private final DataSource delegate;private final String mode;final AtomicBoolean triggered=new AtomicBoolean();
        EventCommitDataSource(DataSource delegate,String mode){this.delegate=delegate;this.mode=mode;}
        public Connection getConnection()throws SQLException{return wrap(delegate.getConnection());}
        public Connection getConnection(String u,String p)throws SQLException{return wrap(delegate.getConnection(u,p));}
        private Connection wrap(Connection connection){var touched=new AtomicBoolean();return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            if(method.getName().equals("prepareStatement")&&args!=null&&args[0] instanceof String sql&&sql.startsWith("INSERT INTO event_inbox")){
                var statement=(PreparedStatement)invoke(connection,method,args);return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{var result=invoke(statement,m,a);if(m.getName().equals("executeUpdate"))touched.set(true);return result;});
            }
            if(method.getName().equals("commit")&&touched.get()&&triggered.compareAndSet(false,true)){invoke(connection,method,args);if(mode.equals("core"))Runtime.getRuntime().halt(73);throw new SQLException("Injected event commit acknowledgement loss","08006");}
            return invoke(connection,method,args);
        });}
        private static Object invoke(Object target,Method method,Object[]args)throws Throwable{try{return method.invoke(target,args);}catch(InvocationTargetException failure){throw failure.getCause();}}
        public PrintWriter getLogWriter()throws SQLException{return delegate.getLogWriter();}public void setLogWriter(PrintWriter writer)throws SQLException{delegate.setLogWriter(writer);}
        public int getLoginTimeout()throws SQLException{return delegate.getLoginTimeout();}public void setLoginTimeout(int value)throws SQLException{delegate.setLoginTimeout(value);}
        public Logger getParentLogger()throws SQLFeatureNotSupportedException{return delegate.getParentLogger();}public<T>T unwrap(Class<T> type)throws SQLException{return delegate.unwrap(type);}public boolean isWrapperFor(Class<?> type)throws SQLException{return delegate.isWrapperFor(type);}
    }
}
