package io.agenticpayments.provider;

import io.agenticpayments.infrastructure.provider.JdbcCompatibilityBindingJournal;
import io.agenticpayments.sdk.*;
import io.agenticpayments.sdk.execution.v1.*;
import io.agenticpayments.provider.ProviderJournalTestSupport.Observer;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static io.agenticpayments.provider.ProviderJournalTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQL rollback, commit acknowledgement loss, contention and genuine peer fencing. */
class ProviderJournalTransactionBoundaryTest {
    @Test void uncommittedBindAndClaimFailuresRollBackUnlikeLostCommitAcknowledgement()throws Exception{
        Fixture staged=new Fixture();
        try(Fault ignored=new Fault(staged,"provider_compatibility_bindings","INSERT")){
            unavailable(()->staged.journal().bind(staged.operation,staged.legacy));
        }
        assertNull(staged.binding());assertEquals(0,staged.peerRows());staged.journal().bind(staged.operation,staged.legacy);
        try(Fault ignored=new Fault(staged,"provider_compatibility_bindings","UPDATE")){
            unavailable(()->staged.journal().claim(new SubmissionRequest(staged.operation,Optional.empty())));
        }
        assertFalse((Boolean)staged.binding().get("dispatch_claimed"));assertNull(staged.binding().get("submission_json"));assertTrue(staged.journal().claim(new SubmissionRequest(staged.operation,Optional.empty())).firstDispatchOwner());
        Fixture acknowledged=new Fixture();acknowledged.journal().bind(acknowledged.operation,acknowledged.legacy);
        var lost=new JdbcCompatibilityBindingJournal(loseCommitAcknowledgement(acknowledged.database),acknowledged.config);
        ExecutionFailure failure=unavailable(()->lost.claim(new SubmissionRequest(acknowledged.operation,Optional.empty())));
        assertEquals(ExecutionFailure.Certainty.MAY_HAVE_OCCURRED,failure.certainty());assertTrue((Boolean)acknowledged.binding().get("dispatch_claimed"));assertNotNull(acknowledged.binding().get("submission_json"));
        assertFalse(acknowledged.journal().claim(new SubmissionRequest(acknowledged.operation,Optional.empty())).firstDispatchOwner());assertEquals(0,acknowledged.peerRows());
    }
    @Test void failedRecordRollsBackFactResourceAndProjectionWhilePeerAcceptanceAndClaimSurvive()throws Exception{
        Fixture f=new Fixture();f.journal().bind(f.operation,f.legacy);assertTrue(f.journal().claim(new SubmissionRequest(f.operation,Optional.empty())).firstDispatchOwner());
        new Observer(f.database,f.config,"normal").execute(f.legacy);assertEquals("SUCCEEDED",f.peerState());
        var result=evidence(f.operation,"provisional-capture",ExecutionEvidence.State.CAPTURED);
        try(Fault ignored=new Fault(f,"provider_compatibility_bindings","UPDATE")){unavailable(()->f.journal().record(result));}
        assertTrue((Boolean)f.binding().get("dispatch_claimed"));assertFalse((Boolean)f.binding().get("dispatch_blocked"));assertNull(f.binding().get("projection_evidence_id"));assertEquals(0,f.evidence());
        assertEquals(0,count(f.database,"SELECT count(*) FROM provider_compatibility_resource_owners WHERE tenant_id=? AND merchant_id=? AND run_id=? AND caller_key=?",namespace(f.operation)));
        assertEquals(1,f.peerRows());assertEquals(1,f.peerEvents());assertEquals(1,f.invocations("EXECUTE"));assertEquals(0,child(f,"recover-captured"));assertEquals(1,f.invocations("EXECUTE"));assertTrue(f.journal().read(f.operation).orElseThrow().terminalForSubmission());
    }
    @Test void recordCommitAcknowledgementLossRetainsFullFactWithoutAnotherExecution(){
        Fixture f=new Fixture();f.journal().bind(f.operation,f.legacy);var fact=evidence(f.operation,"lost-record-response",ExecutionEvidence.State.CAPTURED);
        var lost=new JdbcCompatibilityBindingJournal(loseCommitAcknowledgement(f.database),f.config);unavailable(()->lost.record(fact));
        assertEquals(fact,f.journal().read(f.operation).orElseThrow());assertEquals(fact,f.bridge().submit(new SubmissionRequest(f.operation,Optional.empty())));assertEquals(0,f.invocations("EXECUTE"));assertEquals(1,f.evidence());
    }
    @Test void uniqueIndexContentionPreservesOneCompleteMappingAndUnrelatedOperationsProgress()throws Exception{
        Fixture f=new Fixture();CountDownLatch holding=new CountDownLatch(1),release=new CountDownLatch(1);
        DataSource winnerDatabase=new ForwardingDataSource(f.database){@Override Connection wrap(Connection connection){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            if(method.getName().equals("commit")){holding.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new SQLException("Test barrier unavailable");}
            return invoke(method,connection,args);
        });}};
        var named=database();String app="provider-bind-"+UUID.randomUUID();named.setApplicationName(app);
        var changed=operation(f.config,f.operation.identity(),new ExactMoney("EUR",2,1251));
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()){
            var winner=workers.submit(()->new JdbcCompatibilityBindingJournal(winnerDatabase,f.config).bind(f.operation,f.legacy));
            assertTrue(holding.await(5,TimeUnit.SECONDS));
            try{
                var loser=workers.submit(()->assertThrows(ExecutionFailure.class,()->new JdbcCompatibilityBindingJournal(named,f.config).bind(changed,legacy(changed,"success"))));
                awaitBlocked(f.database,app);
                var unrelated=operation(f.config,"caller-unrelated","operation-unrelated");
                assertEquals(legacy(unrelated,"success"),workers.submit(()->f.journal().bind(unrelated,legacy(unrelated,"success"))).get(3,TimeUnit.SECONDS));
                release.countDown();assertEquals(f.legacy,winner.get(5,TimeUnit.SECONDS));assertEquals(ExecutionFailure.Code.IDENTITY_CONFLICT,loser.get(5,TimeUnit.SECONDS).code());
            }finally{release.countDown();}
        }
        assertEquals(f.operation.fingerprint(),f.binding().get("operation_fingerprint"));assertFalse((Boolean)f.binding().get("conflicted"));assertEquals(0,f.peerRows());assertTrue(f.conflicts()>=1);
    }
    @Test void concurrentClaimsHaveOneOwnerAndASeparateRowCanProgressWhileFirstIsLocked()throws Exception{
        Fixture f=new Fixture();f.journal().bind(f.operation,f.legacy);var named=database();String app="provider-claim-"+UUID.randomUUID();named.setApplicationName(app);
        try(var locked=f.database.getConnection();var statement=locked.prepareStatement("SELECT caller_key FROM provider_compatibility_bindings WHERE tenant_id=? AND merchant_id=? AND run_id=? AND caller_key=? FOR UPDATE");var workers=Executors.newVirtualThreadPerTaskExecutor()){
            locked.setAutoCommit(false);Object[] key=namespace(f.operation);for(int i=0;i<key.length;i++)statement.setObject(i+1,key[i]);try(var rows=statement.executeQuery()){assertTrue(rows.next());}
            try{
                var claim=workers.submit(()->new JdbcCompatibilityBindingJournal(named,f.config).claim(new SubmissionRequest(f.operation,Optional.empty())));awaitBlocked(f.database,app);
                var other=operation(f.config,"caller-other","operation-other");workers.submit(()->f.journal().bind(other,legacy(other,"success"))).get(3,TimeUnit.SECONDS);
                locked.commit();assertTrue(claim.get(5,TimeUnit.SECONDS).firstDispatchOwner());
            }finally{locked.rollback();}
        }
        Fixture race=new Fixture();race.journal().bind(race.operation,race.legacy);CountDownLatch start=new CountDownLatch(1);
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()){
            List<Future<Boolean>> claims=new ArrayList<>();for(int i=0;i<8;i++)claims.add(workers.submit(()->{start.await();return race.journal().claim(new SubmissionRequest(race.operation,Optional.empty())).firstDispatchOwner();}));
            start.countDown();int owners=0;for(var claim:claims)if(claim.get(10,TimeUnit.SECONDS))owners++;assertEquals(1,owners);
        }
        assertTrue((Boolean)race.binding().get("dispatch_claimed"));assertEquals(new SubmissionRequest(race.operation,Optional.empty()).fingerprint(),race.binding().get("submission_fingerprint"));assertEquals(0,race.peerRows());
    }
    @Test void genuineProviderFenceWinsAgainstDelayedOriginalWorkerWithoutClaimingZeroJavaCalls()throws Exception{
        Fixture f=new Fixture();CountDownLatch paused=new CountDownLatch(1),resume=new CountDownLatch(1);
        Observer delayed=new Observer(f.database,f.config,"normal"){@Override public Fact execute(ChargeOperation operation){paused.countDown();try{if(!resume.await(10,TimeUnit.SECONDS))throw new AdapterUnavailableException();}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AdapterUnavailableException();}return super.execute(operation);}};
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()){
            var original=workers.submit(()->f.bridge(f.journal(),delayed).submit(new SubmissionRequest(f.operation,Optional.empty())));assertTrue(paused.await(5,TimeUnit.SECONDS));
            try{
                assertTrue((Boolean)f.binding().get("dispatch_claimed"));assertFalse(f.journal().claim(new SubmissionRequest(f.operation,Optional.empty())).firstDispatchOwner());
                var unrelated=operation(f.config,"caller-during-peer","operation-during-peer");workers.submit(()->f.journal().bind(unrelated,legacy(unrelated,"success"))).get(3,TimeUnit.SECONDS);
                var fence=f.bridge().fence(f.operation);assertEquals(ExecutionEvidence.State.NO_EFFECT_FENCED,fence.state());assertEquals("NO_EFFECT",f.peerState());
                resume.countDown();assertEquals(fence,original.get(5,TimeUnit.SECONDS));assertEquals(fence,f.bridge().submit(new SubmissionRequest(f.operation,Optional.empty())));
            }finally{resume.countDown();}
        }
        assertEquals(1,f.invocations("EXECUTE"));assertEquals(1,f.invocations("DELEGATE_EXECUTE"));assertEquals(1,f.peerRows());assertEquals(1,f.peerEvents());assertEquals("NO_EFFECT",f.peerState());
        assertEquals(0,count(f.database,"SELECT count(*) FROM simulator_journal_events WHERE tenant_id=? AND run_id=? AND adapter_key=? AND outcome='SUCCEEDED'",f.legacy.identity().scope().tenantId(),f.legacy.identity().scope().runId(),f.legacy.identity().adapterKey()));
    }
    @Test void inconsistentLoadedSubmissionFlagsCannotRestoreFirstDispatchOwnership(){
        Fixture f=new Fixture();f.journal().bind(f.operation,f.legacy);assertTrue(f.journal().claim(new SubmissionRequest(f.operation,Optional.empty())).firstDispatchOwner());
        DataSource inconsistent=new ForwardingDataSource(f.database){@Override Connection wrap(Connection connection){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            Object result=invoke(method,connection,args);
            if(method.getName().equals("prepareStatement")&&args[0].toString().contains("FROM provider_compatibility_bindings"))return Proxy.newProxyInstance(java.sql.PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
                Object returned=invoke(m,result,a);if(!m.getName().equals("executeQuery"))return returned;
                ResultSet rows=(ResultSet)returned;return Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class<?>[]{ResultSet.class},(r,read,values)->{
                    if(read.getName().equals("getObject")&&values[0] instanceof Integer index){String column=rows.getMetaData().getColumnLabel(index);if(column.equals("dispatch_claimed")||column.equals("dispatch_blocked"))return false;}
                    return invoke(read,rows,values);
                });
            });
            return result;
        });}};
        var failure=assertThrows(ExecutionFailure.class,()->new JdbcCompatibilityBindingJournal(inconsistent,f.config).claim(new SubmissionRequest(f.operation,Optional.empty())));
        assertEquals(ExecutionFailure.Code.CONTRACT_VIOLATION,failure.code());assertTrue((Boolean)f.binding().get("dispatch_claimed"));assertTrue((Boolean)f.binding().get("conflicted"));assertEquals(0,f.peerRows());
        assertEquals(ExecutionFailure.Code.EVIDENCE_CONFLICT,assertThrows(ExecutionFailure.class,()->f.journal().claim(new SubmissionRequest(f.operation,Optional.empty()))).code());
    }
    private static DataSource loseCommitAcknowledgement(DataSource database){return new ForwardingDataSource(database){@Override Connection wrap(Connection c){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
        Object result=invoke(method,c,args);if(method.getName().equals("commit"))throw new SQLException("SYNTHETIC_PRIVATE_COMMIT_CANARY");return result;
    });}};}
    private static void awaitBlocked(DataSource database,String app)throws InterruptedException{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(System.nanoTime()<deadline){if(count(database,"SELECT count(*) FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock'",app)>0)return;Thread.sleep(10);}fail("Contender must be independently observed waiting for a PostgreSQL lock");
    }
    private static ExecutionFailure unavailable(Runnable work){var failure=assertThrows(ExecutionFailure.class,work::run);assertEquals(ExecutionFailure.Code.CONFIGURATION_UNAVAILABLE,failure.code());assertNull(failure.getCause());assertFalse(failure.toString().contains("CANARY"));return failure;}
    private static final class Fault implements AutoCloseable {
        final Fixture fixture;final String table,name="provider_fault_"+UUID.randomUUID().toString().replace("-","");
        Fault(Fixture fixture,String table,String event)throws SQLException{
            this.fixture=fixture;this.table=table;if(!table.equals("provider_compatibility_bindings")||!Set.of("INSERT","UPDATE").contains(event)||!name.matches("provider_fault_[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid test fault target");
            try(var connection=fixture.database.getConnection();var statement=connection.createStatement()){
                statement.execute("CREATE FUNCTION "+name+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.tenant_id='"+fixture.config.tenantId()+"' AND NEW.run_id='"+fixture.config.runId()+"' THEN RAISE EXCEPTION 'INJECTED_PROVIDER_ROLLBACK'; END IF; RETURN NEW; END; $$");
                try{statement.execute("CREATE TRIGGER "+name+" AFTER "+event+" ON "+table+" FOR EACH ROW EXECUTE FUNCTION "+name+"()");}catch(SQLException failure){statement.execute("DROP FUNCTION "+name+"()");throw failure;}
            }
        }
        public void close()throws SQLException{try(var connection=fixture.database.getConnection();var statement=connection.createStatement()){statement.execute("DROP TRIGGER "+name+" ON "+table);statement.execute("DROP FUNCTION "+name+"()");}}
    }
}
