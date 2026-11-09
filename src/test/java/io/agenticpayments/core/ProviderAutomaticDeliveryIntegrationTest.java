package io.agenticpayments.core;

import io.agenticpayments.application.*;
import io.agenticpayments.infrastructure.*;
import io.agenticpayments.infrastructure.provider.*;
import io.agenticpayments.sdk.execution.v1.ExecutionEvidence;
import io.agenticpayments.sdk.execution.v1.ExecutionBinding;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import static io.agenticpayments.core.CoreTestSupport.*;
import static io.agenticpayments.core.ProviderEventAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real inbox -> provider journal -> core -> delivery acknowledgement, without caller replay. */
class ProviderAutomaticDeliveryIntegrationTest {
    static ProviderEventDeliveryServices automatic(DataSource ds,PaymentService.Session session){
        var registration=registration(session);
        return PaymentServices.providerEventDelivery(ds,registration,eventConfiguration(registration),new HmacProviderEventAuthenticator(EVENT_KEY),session);
    }
    static String accept(ProviderEventDeliveryServices services,Fixture fixture,String id,ExecutionEvidence.State state)throws Exception{
        byte[] raw=body(fixture.binding(),fixture.registration(),id,state);return services.acceptance().accept(fixture.session(),fixture.intent(),raw,sign(raw));
    }
    @Test void automaticPendingUnknownAndDuplicateEventsConvergeWithoutProviderIo()throws Exception{
        for(String scenario:List.of("delayed-success","response-loss-after-acceptance")){
            var f=approved(scenario);f.dispatch();var original=f.binding();var guarded=new DeliveryDataSource(f.ds(),"no-provider");var services=automatic(guarded,f.session());
            accept(services,f,"capture",ExecutionEvidence.State.CAPTURED);
            assertEquals(scenario.startsWith("response")?"UNKNOWN":"PENDING",f.state());
            assertTrue(automatic(guarded,f.session()).delivery().runOnce());assertEquals("SUCCEEDED",f.state());assertEquals("CONSUMED",f.exposure());
            long evidence=f.count("operation_evidence");accept(services,f,"capture",ExecutionEvidence.State.CAPTURED);assertTrue(services.delivery().runOnce());assertEquals(evidence,f.count("operation_evidence"));
            accept(services,f,"late-pending",ExecutionEvidence.State.PENDING);assertTrue(services.delivery().runOnce());assertEquals("SUCCEEDED",f.state());assertFalse(services.delivery().runOnce());
            assertEquals(original,f.binding());assertEquals(1,f.count("financial_operations"));assertEquals(0,guarded.providerIo.get());
        }
    }
    @Test void automaticAcceptanceRejectsUnauthenticatedOrMisboundBytesBeforePersistence()throws Exception{
        var f=approved("delayed-success");f.dispatch();var services=automatic(f.ds(),f.session());byte[] raw=body(f.binding(),f.registration(),"capture",ExecutionEvidence.State.CAPTURED);
        assertThrows(ApplicationException.class,()->services.acceptance().accept(f.session(),f.intent(),raw,"0".repeat(64)));
        byte[] wrong=new String(raw,StandardCharsets.UTF_8).replace("\"1250\"","\"9999\"").getBytes(StandardCharsets.UTF_8);
        assertThrows(ApplicationException.class,()->services.acceptance().accept(f.session(),f.intent(),wrong,sign(wrong)));
        assertEquals(0,f.providerCount("provider_event_envelopes"));assertEquals(0,f.providerCount("provider_event_delivery"));assertEquals("PENDING",f.state());
    }
    @Test void conflictFreezesBothOwnersEvenWhenOriginalEventWasAlreadyAcknowledged()throws Exception{
        var first=approved("delayed-success");first.dispatch();var second=approved(first.ds(),first.session(),first.services(),"delayed-success");second.dispatch();
        var services=automatic(first.ds(),first.session());accept(services,first,"same-event",ExecutionEvidence.State.CAPTURED);assertTrue(services.delivery().runOnce());assertEquals("SUCCEEDED",first.state());
        accept(services,second,"same-event",ExecutionEvidence.State.PENDING);drain(services);
        assertEquals(2,first.count("disputed_exposure"));assertEquals("SUCCEEDED",first.state());assertEquals("CONSUMED",first.exposure());assertEquals("PENDING",second.state());assertEquals("HELD",second.exposure());
        assertEquals(true,row(first.ds(),"SELECT frozen FROM budgets WHERE tenant_id=? AND run_id=? AND budget_id='budget-eur'",first.session().tenantId(),first.session().runId()).get("frozen"));
        assertEquals(2,scalar(first.ds(),"SELECT count(*) FROM provider_event_delivery WHERE tenant_id=? AND run_id=? AND kind='QUARANTINE' AND status='DONE'",first.session().tenantId(),first.session().runId().toString()));
    }
    @Test void changedConfigurationStillProjectsHistoricalOwnersAuthenticatedQuarantine()throws Exception{
        var first=approved("delayed-success");first.dispatch();var original=first.binding();var initial=automatic(first.ds(),first.session());
        accept(initial,first,"same-event",ExecutionEvidence.State.CAPTURED);assertTrue(initial.delivery().runOnce());
        var old=first.registration().configuration().binding();
        var changedBinding=new ExecutionBinding(old.providerId(),old.accountRef(),old.region(),old.configurationRef(),"2",old.credentialRef(),"2",old.executionOwnerId());
        var registration=SyntheticProviderRegistration.interactions(first.session().tenantId(),"merchant-demo",first.session().runId(),changedBinding,CLOCK,CLOCK.plusSeconds(600));
        var config=new ProviderEventConfiguration(first.session().tenantId(),"merchant-demo",first.session().runId().toString(),changedBinding,registration.configuration().offer(),"core-event-policy","2","core-event-key","2");
        var currentPayments=PaymentServices.providerInteractions(first.ds(),registration,config,new HmacProviderEventAuthenticator(EVENT_KEY));
        var second=approved(first.ds(),first.session(),currentPayments,"delayed-success");second.dispatch();
        var current=PaymentServices.providerEventDelivery(first.ds(),registration,config,new HmacProviderEventAuthenticator(EVENT_KEY),first.session());
        byte[] raw=body(second.binding(),registration,"same-event",ExecutionEvidence.State.PENDING);current.acceptance().accept(second.session(),second.intent(),raw,sign(raw));drain(current);
        assertEquals(2,first.count("disputed_exposure"));assertEquals("SUCCEEDED",first.state());assertEquals("PENDING",second.state());assertEquals(original,first.binding());
        assertEquals(2,scalar(first.ds(),"SELECT count(*) FROM provider_event_delivery WHERE tenant_id=? AND run_id=? AND kind='QUARANTINE' AND status='DONE'",first.session().tenantId(),first.session().runId().toString()));
        assertEquals("1",row(first.ds(),"SELECT key_version FROM provider_event_delivery WHERE tenant_id=? AND run_id=? AND kind='QUARANTINE' AND operation_fingerprint=?",first.session().tenantId(),first.session().runId().toString(),original.operation().fingerprint()).get("key_version"));
    }
    @Test void manualRetentionIsNotSilentlyAdoptedAndMissingOriginalPolicyRetries()throws Exception{
        var f=approved("delayed-success");f.dispatch();f.event("manual",ExecutionEvidence.State.CAPTURED);var services=automatic(f.ds(),f.session());
        assertFalse(services.delivery().runOnce());assertEquals("PENDING",f.state());assertEquals(0,f.providerCount("provider_event_delivery"));
        accept(services,f,"automatic",ExecutionEvidence.State.CAPTURED);var config=eventConfiguration(f.registration());
        var replacement=new ProviderEventConfiguration(config.tenantId(),config.merchantId(),config.runId(),config.binding(),config.offer(),config.policyReference(),"2",config.keyReference(),"2");
        var changed=PaymentServices.providerEventDelivery(f.ds(),f.registration(),replacement,new HmacProviderEventAuthenticator(EVENT_KEY),f.session());
        assertTrue(changed.delivery().runOnce());assertEquals("PENDING",f.state());
        var delivery=row(f.ds(),"SELECT status,failure_code FROM provider_event_delivery WHERE tenant_id=? AND run_id=?",f.session().tenantId(),f.session().runId().toString());
        assertEquals("READY",delivery.get("status"));assertEquals("CONFIGURATION_UNAVAILABLE",delivery.get("failure_code"));
        makeDue(f);assertTrue(services.delivery().runOnce());assertEquals("SUCCEEDED",f.state());
    }
    @Test void journalOnlyTerminalConflictFreezesCoreBeforeDeliveryBecomesDead()throws Exception{
        var f=approved("success");f.dispatch();var services=automatic(f.ds(),f.session());
        accept(services,f,"distinct-opposite-event",ExecutionEvidence.State.NO_EFFECT_FENCED);assertTrue(services.delivery().runOnce());
        assertEquals("SUCCEEDED",f.state());assertEquals("CONSUMED",f.exposure());assertEquals(1,f.count("disputed_exposure"));
        assertEquals(0,f.providerCount("provider_event_quarantine"));
        assertEquals(true,row(f.ds(),"SELECT frozen FROM budgets WHERE tenant_id=? AND run_id=? AND budget_id='budget-eur'",f.session().tenantId(),f.session().runId()).get("frozen"));
        var result=row(f.ds(),"SELECT status,failure_code FROM provider_event_delivery WHERE tenant_id=? AND run_id=?",f.session().tenantId(),f.session().runId().toString());
        assertEquals("DEAD",result.get("status"));assertEquals("CONFLICT",result.get("failure_code"));
    }
    @Test void lostCoreAcknowledgementAndFreshJvmJournalCoreCrashesRedeliverOriginalFact()throws Exception{
        var f=approved("delayed-success");f.dispatch();accept(automatic(f.ds(),f.session()),f,"ack-loss",ExecutionEvidence.State.CAPTURED);
        var lost=new EventCommitDataSource(f.ds(),"ack");assertTrue(automatic(lost,f.session()).delivery().runOnce());assertTrue(lost.triggered.get());assertEquals("SUCCEEDED",f.state());
        long evidence=f.count("operation_evidence");makeDue(f);assertTrue(automatic(f.ds(),f.session()).delivery().runOnce());assertEquals(evidence,f.count("operation_evidence"));
        for(String phase:List.of("journal","core")){
            var crashed=approved("delayed-success");crashed.dispatch();accept(automatic(crashed.ds(),crashed.session()),crashed,"crash",ExecutionEvidence.State.CAPTURED);
            assertEquals(73,child(crashed,phase));assertEquals(phase.equals("core")?"SUCCEEDED":"PENDING",crashed.state());
            makeDue(crashed);assertEquals(0,child(crashed,"recover"));assertEquals("SUCCEEDED",crashed.state());assertEquals("CONSUMED",crashed.exposure());assertEquals(1,crashed.count("financial_operations"));
            assertEquals(1,scalar(crashed.ds(),"SELECT count(*) FROM provider_event_delivery WHERE tenant_id=? AND run_id=? AND status='DONE'",crashed.session().tenantId(),crashed.session().runId().toString()));
        }
    }
    private static void drain(ProviderEventDeliveryServices services){for(int n=0;n<12;n++)if(!services.delivery().runOnce())return;fail("Delivery did not drain within its bounded test workload.");}
    static void makeDue(Fixture f)throws SQLException{
        try(var c=f.ds().getConnection();var p=c.prepareStatement("UPDATE provider_event_delivery SET available_at=clock_timestamp()-interval '1 second',lease_until=CASE WHEN status='LEASED' THEN clock_timestamp()-interval '1 second' ELSE NULL END WHERE tenant_id=? AND run_id=?")){
            p.setString(1,f.session().tenantId());p.setString(2,f.session().runId().toString());p.executeUpdate();
        }
    }
    private static int child(Fixture f,String phase)throws Exception{
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var process=new ProcessBuilder(java,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),ProviderAutomaticDeliveryCrashProcess.class.getName(),phase,f.session().tenantId(),f.session().runId().toString()).redirectErrorStream(true).start();
        try{assertTrue(process.waitFor(30,TimeUnit.SECONDS));assertEquals(phase.equals("recover")?"PROVIDER_AUTOMATIC_DELIVERY_OK":"",new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8));return process.exitValue();}
        finally{if(process.isAlive())process.destroyForcibly().waitFor(5,TimeUnit.SECONDS);}
    }
    static final class DeliveryDataSource implements DataSource {
        private final DataSource base;private final String mode;final AtomicInteger providerIo=new AtomicInteger();private final AtomicBoolean crashed=new AtomicBoolean();
        DeliveryDataSource(DataSource base,String mode){this.base=base;this.mode=mode;}
        public Connection getConnection()throws SQLException{return wrap(base.getConnection());}public Connection getConnection(String u,String p)throws SQLException{return wrap(base.getConnection(u,p));}
        private Connection wrap(Connection c){var touched=new AtomicBoolean();return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            if(method.getName().equals("prepareStatement")&&args[0] instanceof String sql){
                if(sql.contains("simulator_operation_journal")){providerIo.incrementAndGet();throw new SQLException("Financial provider I/O is forbidden during delivery.");}
                if(mode.equals("journal")&&sql.startsWith("INSERT INTO provider_compatibility_evidence")){
                    var statement=(PreparedStatement)invoke(c,method,args);return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(ignored,m,a)->{Object result=invoke(statement,m,a);if(m.getName().equals("executeUpdate"))touched.set(true);return result;});
                }
            }
            Object result=invoke(c,method,args);if(method.getName().equals("commit")&&touched.get()&&crashed.compareAndSet(false,true))Runtime.getRuntime().halt(73);return result;
        });}
        private static Object invoke(Object target,Method method,Object[] args)throws Throwable{try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}
        public PrintWriter getLogWriter()throws SQLException{return base.getLogWriter();}public void setLogWriter(PrintWriter v)throws SQLException{base.setLogWriter(v);}
        public void setLoginTimeout(int v)throws SQLException{base.setLoginTimeout(v);}public int getLoginTimeout()throws SQLException{return base.getLoginTimeout();}
        public Logger getParentLogger()throws SQLFeatureNotSupportedException{return base.getParentLogger();}public<T>T unwrap(Class<T> type)throws SQLException{return base.unwrap(type);}public boolean isWrapperFor(Class<?> type)throws SQLException{return base.isWrapperFor(type);}
    }
}
