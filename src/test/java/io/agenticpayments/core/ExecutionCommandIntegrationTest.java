package io.agenticpayments.core;

import io.agenticpayments.http.HttpFailureMapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agenticpayments.application.*;
import io.agenticpayments.application.PaymentCommandTransactions.*;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.composition.NativeComposition;
import io.agenticpayments.domain.PaymentRules;
import io.agenticpayments.infrastructure.*;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static io.agenticpayments.composition.Composition.*;
import static io.agenticpayments.core.CoreTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL guarantees of the extracted application command and its transaction scope. */
class ExecutionCommandIntegrationTest {
    private record Fixture(DataSource ds,Session session,PaymentService service,Result approved) {
        UUID intent(){return uuid(approved.body().get("intentId"));}
        ExecuteCommand command(){return new ExecuteCommand(version(approved),uuid(approved.body().get("attemptId")));}
        Result execute(String key){return service.execute(session,intent(),key,command());}
    }
    private static Fixture approved() {
        DataSource ds=database();Session session=isolated(ds);PaymentService service=PaymentServices.local(ds);
        Result quote=quoted(service,session,"success");Result approved=service.approve(session,uuid(quote.body().get("intentId")),key(),approval(quote));
        return new Fixture(ds,session,service,approved);
    }
    private static long count(Fixture f,String table) throws Exception {
        return scalar(f.ds(),"SELECT count(*) FROM "+table+" WHERE tenant_id=? AND run_id=?",f.session().tenantId(),f.session().runId());
    }
    private static Map<String,Object> sequences(Fixture f) throws Exception {
        return row(f.ds(),"SELECT next_work_sequence,step_sequence FROM simulation_runs WHERE tenant_id=? AND run_id=?",f.session().tenantId(),f.session().runId());
    }
    private static void held(Fixture f) throws Exception {
        assertEquals("HELD",f.service().status(f.session(),f.intent()).body().get("exposureState"));
        assertEquals(1250,scalar(f.ds(),"SELECT held FROM budgets WHERE tenant_id=? AND run_id=? AND budget_id='budget-eur'",f.session().tenantId(),f.session().runId()));
        assertEquals(0,count(f,"simulator_operation_journal"));
    }
    private void rollback(boolean atExecution) throws Exception {
        Fixture f=approved();long commands=count(f,"commands"),audit=count(f,"audit_events");var before=sequences(f);
        String key=key();var fault=new ExecutionCommandFaultDataSource(f.ds(),f.session(),f.intent(),atExecution,"XX000");
        PaymentService failing=PaymentServices.local(fault);
        PaymentException error=assertThrows(PaymentException.class,()->failing.execute(f.session(),f.intent(),key,f.command()));
        assertEquals(503,HttpFailureMapper.map(error).status());assertEquals("TEMPORARILY_UNAVAILABLE",error.code());assertEquals("SAME_COMMAND",HttpFailureMapper.map(error).retry());
        assertNotNull(fault.witness);assertEquals(1,fault.witness.operations());assertEquals(1,fault.witness.work());assertEquals(commands,fault.witness.commands());
        assertEquals(audit+1,fault.witness.audit());assertEquals("PREPARING",fault.witness.state());assertEquals(version(f.approved())+1,fault.witness.version());
        assertEquals(((Number)before.get("next_work_sequence")).longValue()+1,fault.witness.workSequence());assertEquals(((Number)before.get("step_sequence")).longValue()+1,fault.witness.stepSequence());
        assertEquals(0,count(f,"financial_operations"));assertEquals(0,count(f,"outbox"));assertEquals(commands,count(f,"commands"));assertEquals(audit,count(f,"audit_events"));assertEquals(before,sequences(f));
        assertEquals("APPROVED",f.service().status(f.session(),f.intent()).body().get("state"));assertEquals(version(f.approved()),((Number)f.service().status(f.session(),f.intent()).body().get("version")).intValue());held(f);
        Result success=failing.execute(f.session(),f.intent(),key,f.command());assertFalse(success.replayed());
        Result replay=PaymentServices.local(f.ds()).execute(f.session(),f.intent(),key,f.command());assertTrue(replay.replayed());
        assertEquals(success.body().get("operationId").toString(),replay.body().get("operationId"));assertEquals(success.body().get("correlationId"),replay.body().get("correlationId"));
        assertEquals(1,count(f,"financial_operations"));assertEquals(1,count(f,"outbox"));assertEquals(commands+1,count(f,"commands"));held(f);
    }
    @Test void failureBeforeCommandInsertRollsBackAllProvisionalExecutionWrites() throws Exception {rollback(false);}
    @Test void failureExecutingCommandInsertRollsBackAllProvisionalExecutionWrites() throws Exception {rollback(true);}

    @Test void serializationRetryRecreatesOnlyUncommittedOperationAndCommitsOneIdentity() throws Exception {
        Fixture f=approved();var fault=new ExecutionCommandFaultDataSource(f.ds(),f.session(),f.intent(),true,"40001");String key=key();
        Result result=PaymentServices.local(fault).execute(f.session(),f.intent(),key,f.command());
        assertNotEquals(fault.witness.operation(),uuid(result.body().get("operationId")),"Failed transaction identity was never published to a provider");
        assertEquals(1,count(f,"financial_operations"));assertEquals(1,count(f,"outbox"));assertTrue(f.execute(key).replayed());held(f);
    }

    @Test void historicExecuteDigestAndResponseJsonReplayAfterRestartButCurrentMerchantAuthorizationPrecedesReplay() throws Exception {
        Fixture f=approved();String key=key();Result first=f.execute(key);var json=new ObjectMapper();
        String historicRequest="{\"attemptId\":\""+f.command().attemptId()+"\",\"expectedVersion\":"+f.command().expectedVersion()+"}";
        String historicResponse="{\"attemptId\":\""+f.command().attemptId()+"\",\"contractVersion\":\""+PaymentRules.CONTRACT+"\",\"correlationId\":\""+first.body().get("correlationId")
            +"\",\"intentId\":\""+f.intent()+"\",\"operationId\":\""+first.body().get("operationId")+"\",\"state\":\"PREPARING\",\"version\":"+(f.command().expectedVersion()+1)+"}";
        var stored=row(f.ds(),"SELECT request_digest,response_body FROM commands WHERE tenant_id=? AND run_id=? AND idempotency_key=?",f.session().tenantId(),f.session().runId(),key);
        assertEquals(PaymentRules.sha256(historicRequest),stored.get("request_digest"));assertEquals(historicResponse,stored.get("response_body"));
        var restarted=PaymentServices.local(f.ds());Result replay=restarted.execute(f.session(),f.intent(),key,f.command());assertTrue(replay.replayed());assertEquals(json.readTree(historicResponse),json.valueToTree(replay.body()));
        Session denied=new Session(f.session().tenantId(),f.session().runId(),f.session().principalId(),f.session().roles(),Set.of(),f.session().allowedGrants());
        assertEquals(404,HttpFailureMapper.map(assertThrows(PaymentException.class,()->restarted.execute(denied,f.intent(),key,f.command()))).status());
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(PaymentException.class,()->restarted.execute(f.session(),f.intent(),key,new ExecuteCommand(4,f.command().attemptId()))).code());
        f.service().revoke(f.session(),"fixture-grant-1",key(),new RevokeCommand("user-request"));
        assertTrue(restarted.execute(f.session(),f.intent(),key,f.command()).replayed(),"Accepted replay does not reapply current state/grant policy");
        assertEquals(1,count(f,"financial_operations"));assertEquals(0,count(f,"simulator_operation_journal"));
    }

    @Test void conflictingReplayRejectsBeforeDecodingMalformedHistoricResponse() throws Exception {
        Fixture f=approved();String key=key();f.execute(key);long commands=count(f,"commands");
        try(var connection=f.ds().getConnection();var update=connection.prepareStatement("UPDATE commands SET response_body=? WHERE tenant_id=? AND run_id=? AND idempotency_key=?")) {
            update.setString(1,"{malformed-historic-fixture");update.setString(2,f.session().tenantId());update.setObject(3,f.session().runId());update.setString(4,key);assertEquals(1,update.executeUpdate());
        }
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(PaymentException.class,()->f.service().execute(f.session(),f.intent(),key,new ExecuteCommand(4,f.command().attemptId()))).code());
        assertThrows(IllegalStateException.class,()->f.execute(key),"Identical replay retains the existing stored-JSON decoding failure");
        assertEquals(commands,count(f,"commands"));assertEquals(1,count(f,"financial_operations"));assertEquals(1,count(f,"outbox"));held(f);
    }

    private static List<Object> ordered(DataSource ds,RunLockBarrierDataSource first,RunLockBarrierDataSource second,Callable<?> winner,Callable<?> loser) throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        try {
            Future<Object> one=pool.submit(()->outcome(winner));assertTrue(first.acquired.await(10,TimeUnit.SECONDS));
            Future<Object> two=pool.submit(()->outcome(loser));assertTrue(second.entered.await(10,TimeUnit.SECONDS));
            // Observe actual server-side lock contention, not a thread paused just before sending SQL.
            boolean blocked=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            try(var connection=ds.getConnection();var query=connection.prepareStatement("SELECT ? = ANY(pg_blocking_pids(?))")) {
                query.setInt(1,first.backendPid);query.setInt(2,second.backendPid);
                while(System.nanoTime()<deadline&&!blocked){try(var rows=query.executeQuery()){assertTrue(rows.next());blocked=rows.getBoolean(1);}if(!blocked)Thread.sleep(10);}
            }
            assertTrue(blocked,"PostgreSQL must observe second backend blocked by first backend");assertEquals(1,second.acquired.getCount());
            first.resume.countDown();return List.of(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS));
        } finally {first.resume.countDown();second.resume.countDown();pool.shutdownNow();}
    }
    private static Object outcome(Callable<?> work) throws Exception {try{return work.call();}catch(PaymentException failure){return failure.code();}}
    private void executionRace(boolean sameKey) throws Exception {
        Fixture f=approved();var first=new RunLockBarrierDataSource(f.ds(),true);var second=new RunLockBarrierDataSource(f.ds(),false);
        var one=PaymentServices.local(first);var two=PaymentServices.local(second);String key=key(),other=sameKey?key:key();
        var results=ordered(f.ds(),first,second,()->one.execute(f.session(),f.intent(),key,f.command()),()->two.execute(f.session(),f.intent(),other,f.command()));
        Result winner=(Result)results.getFirst();assertFalse(winner.replayed());
        if(sameKey){Result replay=(Result)results.get(1);assertTrue(replay.replayed());assertEquals(winner.body().get("operationId").toString(),replay.body().get("operationId"));assertEquals(winner.body().get("correlationId"),replay.body().get("correlationId"));}
        else assertEquals("VERSION_CONFLICT",results.get(1));
        assertEquals(1,count(f,"financial_operations"));assertEquals(1,count(f,"outbox"));held(f);
    }
    @Test void concurrentSameKeyCommandsReplayOneAcceptedOperationUnderActualRunLock() throws Exception {executionRace(true);}
    @Test void concurrentDifferentKeysCannotCreateTwoOperationsForApprovedVersion() throws Exception {executionRace(false);}

    private void revocationRace(boolean revokeFirst) throws Exception {
        Fixture f=approved();var first=new RunLockBarrierDataSource(f.ds(),true);var second=new RunLockBarrierDataSource(f.ds(),false);
        var one=PaymentServices.local(first);var two=PaymentServices.local(second);
        var results=ordered(f.ds(),first,second,
            ()->revokeFirst?one.revoke(f.session(),"fixture-grant-1",key(),new RevokeCommand("user-request")):one.execute(f.session(),f.intent(),key(),f.command()),
            ()->revokeFirst?two.execute(f.session(),f.intent(),key(),f.command()):two.revoke(f.session(),"fixture-grant-1",key(),new RevokeCommand("user-request")));
        assertInstanceOf(Result.class,results.getFirst());if(revokeFirst)assertEquals("VERSION_CONFLICT",results.get(1));else assertInstanceOf(Result.class,results.get(1));
        assertEquals(revokeFirst?0:1,count(f,"financial_operations"));assertEquals("CANCELLED",f.service().status(f.session(),f.intent()).body().get("state"));
        assertEquals("RELEASED",f.service().status(f.session(),f.intent()).body().get("exposureState"));f.service().runNext(f.session());assertEquals(0,count(f,"simulator_operation_journal"));
        assertEquals(0,scalar(f.ds(),"SELECT count(*) FROM financial_operations WHERE tenant_id=? AND run_id=? AND admitted_sequence IS NOT NULL",f.session().tenantId(),f.session().runId()));
    }
    @Test void revocationWinningRunLockPreventsExecutionOperation() throws Exception {revocationRace(true);}
    @Test void executionWinningRunLockStillCannotDispatchAfterRevocation() throws Exception {revocationRace(false);}

    @Test void replayExecutionOwnershipIsCheckedBeforeAcceptedCommandReplay() throws Exception {
        Fixture f=approved();String key=key();f.execute(key);UUID owner=UUID.randomUUID();
        try(var connection=f.ds().getConnection();var update=connection.prepareStatement("UPDATE simulation_runs SET execution_mode='REPLAY',replay_owner=? WHERE tenant_id=? AND run_id=?")) {
            update.setObject(1,owner);update.setString(2,f.session().tenantId());update.setObject(3,f.session().runId());assertEquals(1,update.executeUpdate());
        }
        assertEquals(403,HttpFailureMapper.map(assertThrows(PaymentException.class,()->f.execute(key))).status());
        var owned=new PaymentService(new JdbcPaymentStore(f.ds(),new JdbcSimulatorAdapter(f.ds()),owner));assertTrue(owned.execute(f.session(),f.intent(),key,f.command()).replayed());
        assertEquals(1,count(f,"financial_operations"));held(f);
    }

    private void nativeOutage(boolean artifactOutage) throws Exception {
        DataSource ds=database();Session session=isolated(ds);Ports base=NativeComposition.ports(new JdbcSignedArtifactStore(ds));var fail=new AtomicBoolean();
        SignedArtifactStore artifacts=new SignedArtifactStore() {
            public Descriptor describe(){return base.artifacts().describe();}
            public ArtifactReference put(Scope scope,Artifact value){return base.artifacts().put(scope,value);}
            public Artifact get(Scope scope,ArtifactReference ref){if(artifactOutage&&fail.get())throw new CompositionException(Failure.ARTIFACT_UNAVAILABLE);return base.artifacts().get(scope,ref);}
        };
        AuthorityVerifier verifier=new AuthorityVerifier() {
            public Descriptor describe(){return base.verifier().describe();}
            public VerifiedAuthority verify(AuthorityRequest request,KeyResolver keys,Instant now){if(!artifactOutage&&fail.get())throw new CompositionException(Failure.CRYPTO_UNAVAILABLE);return base.verifier().verify(request,keys,now);}
        };
        var service=PaymentServices.local(ds,new JdbcSimulatorAdapter(ds),new Ports(base.checkout(),base.issuer(),verifier,base.provisioner(),artifacts,base.keyResolvers()));
        Result created=service.create(session,key(),new CreateCommand("merchant-demo","success",PROFILE,List.of("single-tender","exact-money")));UUID intent=uuid(created.body().get("intentId"));
        Result quote=service.quote(session,intent,key(),new QuoteCommand(version(created),"cart-1250-eur",List.of(new Tender("simulator","synthetic-card","fixture-credential-1"))));
        Fixture f=new Fixture(ds,session,service,service.approve(session,intent,key(),approval(quote)));String key=key();
        long commands=count(f,"commands"),audit=count(f,"audit_events"),artifactsBefore=count(f,"native_artifacts"),replay=count(f,"native_replay_identities");var before=sequences(f);
        fail.set(true);PaymentException error=assertThrows(PaymentException.class,()->f.execute(key));assertEquals(503,HttpFailureMapper.map(error).status());assertEquals("SAME_COMMAND",HttpFailureMapper.map(error).retry());
        assertEquals("APPROVED",service.status(session,intent).body().get("state"));assertEquals(version(f.approved()),((Number)service.status(session,intent).body().get("version")).intValue());held(f);
        assertEquals(0,count(f,"financial_operations"));assertEquals(0,count(f,"outbox"));assertEquals(commands,count(f,"commands"));assertEquals(audit,count(f,"audit_events"));assertEquals(before,sequences(f));
        assertEquals(artifactsBefore,count(f,"native_artifacts"));assertEquals(replay,count(f,"native_replay_identities"));
        fail.set(false);Result result=f.execute(key);fail.set(true);assertTrue(f.execute(key).replayed());assertEquals(1,count(f,"financial_operations"));
        fail.set(false);for(int n=0;n<5&&service.runNext(session);n++){}assertEquals("SUCCEEDED",service.status(session,intent).body().get("state"));
        assertEquals(result.body().get("operationId"),service.status(session,intent).body().get("operationId"));assertEquals(1,count(f,"simulator_operation_journal"));
    }
    @Test void nativeArtifactOutageLeavesApprovalExposureAndSameKeyRecoversWithoutReissuance() throws Exception {nativeOutage(true);}
    @Test void nativeCryptoOutageLeavesApprovalExposureAndSameKeyRecoversWithoutReissuance() throws Exception {nativeOutage(false);}

    /** Test-only reflection obtains the internal composition; no production inspection/bypass hook is added. */
    private static PaymentCommandTransactions transactions(JdbcPaymentStore store) throws Exception {
        Field execution=JdbcPaymentStore.class.getDeclaredField("execution");execution.setAccessible(true);
        Field transactions=ExecutePayment.class.getDeclaredField("transactions");transactions.setAccessible(true);
        return (PaymentCommandTransactions)transactions.get(execution.get(store));
    }
    @Test void realTransactionScopeRejectsEveryOperationAfterSuccessOrFailureAndRejectsAnotherThread() throws Exception {
        Fixture f=approved();PaymentCommandTransactions transactions=transactions(new JdbcPaymentStore(f.ds(),new JdbcSimulatorAdapter(f.ds())));
        for(boolean fail:List.of(false,true)) {
            var escaped=new AtomicReference<PaymentCommandTransactions.Transaction>();
            PaymentCommandTransactions.Work work=tx->{
                escaped.set(tx);assertEquals(CLOCK,tx.now());var pool=Executors.newSingleThreadExecutor();
                assertThrows(IllegalArgumentException.class,()->tx.replay(new AcceptedCommand("fabricated-command-digest")));
                try{assertInstanceOf(IllegalStateException.class,pool.submit(()->{try{tx.now();return null;}catch(RuntimeException error){return error;}}).get(5,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
                assertThrows(IllegalStateException.class,()->transactions.run(f.session(),nested->new Result(200,Map.of(),false)));
                if(fail)throw new PaymentException(io.agenticpayments.application.ApplicationFailure.INVALID_STATE);return new Result(200,Map.of(),false);
            };
            if(fail)assertThrows(PaymentException.class,()->transactions.run(f.session(),work));else transactions.run(f.session(),work);
            var tx=escaped.get();assertThrows(IllegalStateException.class,tx::now);assertThrows(IllegalStateException.class,()->tx.intent(f.intent()));
            assertThrows(IllegalStateException.class,()->tx.attempt(f.intent()));assertThrows(IllegalStateException.class,()->tx.checkout(null));assertThrows(IllegalStateException.class,()->tx.grant("fixture-grant-1"));
            assertThrows(IllegalStateException.class,()->tx.accepted(null));assertThrows(IllegalStateException.class,()->tx.verifyNative(null,null,null));
            assertThrows(IllegalStateException.class,()->tx.replay(null));
            assertThrows(IllegalStateException.class,()->tx.prepare(null));assertThrows(IllegalStateException.class,()->tx.accept(null,null,null));
        }
        assertEquals(0,count(f,"financial_operations"));assertEquals(0,count(f,"outbox"));held(f);
    }
}
