package io.agenticpayments.core;

import io.agenticpayments.application.*;
import io.agenticpayments.application.PaymentCommandTransactions.*;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.domain.PaymentRules;
import io.agenticpayments.http.HttpFailureMapper;
import io.agenticpayments.infrastructure.*;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static io.agenticpayments.core.CoreTestSupport.*;
import static io.agenticpayments.core.ApprovalCommandFaultDataSource.Point.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real transaction ownership, rollback, acknowledgement loss and scope-lifetime evidence. */
class ApprovalCommandIntegrationTest {
    private record Fixture(DataSource ds,Session session,PaymentService service,Result quote,boolean nativeProfile) {
        UUID intent(){return uuid(quote.body().get("intentId"));}
        ApproveCommand command(){return approval(quote);}
        Result approve(String key){return service.approve(session,intent(),key,command());}
    }
    private static Fixture quoted(boolean nativeProfile) {
        DataSource ds=database();Session session=isolated(ds);PaymentService service=PaymentServices.local(ds);
        Result created=service.create(session,key(),new CreateCommand("merchant-demo","success",nativeProfile?"local-ap2-acp-single-tender-0.1":PaymentRules.PROFILE,List.of("single-tender","exact-money")));
        Result quote=service.quote(session,uuid(created.body().get("intentId")),key(),new QuoteCommand(version(created),"cart-1250-eur",List.of(new Tender("simulator","synthetic-card","fixture-credential-1"))));
        return new Fixture(ds,session,service,quote,nativeProfile);
    }
    private static long count(Fixture f,String table) throws Exception {
        return scalar(f.ds(),"SELECT count(*) FROM "+table+" WHERE tenant_id=? AND run_id=?",f.session().tenantId(),f.session().runId());
    }
    private static Map<String,Long> counts(Fixture f) throws Exception {
        var result=new LinkedHashMap<String,Long>();for(String table:ApprovalCommandFaultDataSource.TABLES)result.put(table,count(f,table));return result;
    }
    private static long held(Fixture f) throws Exception {
        return scalar(f.ds(),"SELECT held FROM budgets WHERE tenant_id=? AND run_id=? AND budget_id='budget-eur'",f.session().tenantId(),f.session().runId());
    }
    private static long sequence(Fixture f) throws Exception {
        return scalar(f.ds(),"SELECT step_sequence FROM simulation_runs WHERE tenant_id=? AND run_id=?",f.session().tenantId(),f.session().runId());
    }
    private static void approvedOnce(Fixture f) throws Exception {
        assertEquals(1,count(f,"payment_attempts"));assertEquals(1,count(f,"reservations"));assertEquals(1250,held(f));
        assertEquals(f.nativeProfile()?1:0,count(f,"native_authorities"));assertEquals(f.nativeProfile()?3:0,count(f,"native_challenges"));
        assertEquals(f.nativeProfile()?3:0,count(f,"native_replay_identities"));assertEquals(0,count(f,"financial_operations"));assertEquals(0,count(f,"outbox"));
    }
    private void rollback(ApprovalCommandFaultDataSource.Point point) throws Exception {
        for(boolean nativeProfile:List.of(false,true)) {
            Fixture f=quoted(nativeProfile);var before=counts(f);long beforeSequence=sequence(f);String key=key();
            var fault=new ApprovalCommandFaultDataSource(f.ds(),f.session(),f.intent(),point,"XX000");var service=PaymentServices.local(fault);
            PaymentException failure=assertThrows(PaymentException.class,()->service.approve(f.session(),f.intent(),key,f.command()));
            assertEquals("SAME_COMMAND",HttpFailureMapper.map(failure).retry());assertEquals(503,HttpFailureMapper.map(failure).status());
            var witness=fault.witness;assertNotNull(witness);assertEquals("APPROVED",witness.state());assertEquals(3,witness.version());assertEquals(1250,witness.held());
            assertEquals(1L,witness.counts().get("payment_attempts"));assertEquals(1L,witness.counts().get("reservations"));
            assertEquals(nativeProfile?1L:0L,witness.counts().get("native_authorities"));assertEquals(nativeProfile?3L:0L,witness.counts().get("native_challenges"));
            assertEquals(nativeProfile?3L:0L,witness.counts().get("native_replay_identities"));assertEquals(before.get("commands"),witness.counts().get("commands"));
            assertEquals(before.get("audit_events")+1,witness.counts().get("audit_events"));assertEquals(beforeSequence+1,witness.sequence());
            assertEquals(before,counts(f));assertEquals(beforeSequence,sequence(f));assertEquals(0,held(f));
            assertEquals("QUOTED",f.service().status(f.session(),f.intent()).body().get("state"));
            if(nativeProfile)assertTrue(count(f,"native_artifacts")>2,"Unreferenced immutable artifacts may survive SQL rollback");
            Result result=service.approve(f.session(),f.intent(),key,f.command());assertFalse(result.replayed());approvedOnce(f);
            var committed=counts(f);long artifacts=count(f,"native_artifacts");
            Result replay=PaymentServices.local(f.ds()).approve(f.session(),f.intent(),key,f.command());assertTrue(replay.replayed());
            assertEquals(result.body().get("attemptId").toString(),replay.body().get("attemptId"));assertEquals(committed,counts(f));assertEquals(artifacts,count(f,"native_artifacts"));
        }
    }
    @Test void failureBeforeCommandInsertRollsBackEveryProvisionalApprovalWrite() throws Exception {rollback(BEFORE_INSERT);}
    @Test void failureDuringCommandInsertRollsBackEveryProvisionalApprovalWrite() throws Exception {rollback(DURING_INSERT);}
    @Test void serializationRetryCommitsOnlyOneAttemptAndReservation() throws Exception {
        Fixture f=quoted(true);String key=key();var fault=new ApprovalCommandFaultDataSource(f.ds(),f.session(),f.intent(),DURING_INSERT,"40001");
        Result accepted=PaymentServices.local(fault).approve(f.session(),f.intent(),key,f.command());
        assertNotEquals(fault.witness.attempt(),uuid(accepted.body().get("attemptId")));approvedOnce(f);assertTrue(f.approve(key).replayed());
    }
    @Test void lostCommitAcknowledgementRecoversOriginalApprovalWithoutIssuingAgain() throws Exception {
        Fixture f=quoted(true);String key=key();var fault=new ApprovalCommandFaultDataSource(f.ds(),f.session(),f.intent(),AFTER_COMMIT,"08006");
        var failing=PaymentServices.local(fault);
        PaymentException error=assertThrows(PaymentException.class,()->failing.approve(f.session(),f.intent(),key,f.command()));
        assertEquals("SAME_COMMAND",HttpFailureMapper.map(error).retry());approvedOnce(f);
        var before=counts(f);long artifacts=count(f,"native_artifacts");
        Result recovered=PaymentServices.local(f.ds()).approve(f.session(),f.intent(),key,f.command());assertTrue(recovered.replayed());
        assertEquals(fault.witness.attempt(),uuid(recovered.body().get("attemptId")));assertEquals(before,counts(f));assertEquals(artifacts,count(f,"native_artifacts"));
    }
    private static Object outcome(Callable<?> work) throws Exception {try{return work.call();}catch(PaymentException failure){return failure.code();}}
    private static List<Object> ordered(Fixture f,RunLockBarrierDataSource first,RunLockBarrierDataSource second,Callable<?> winner,Callable<?> loser) throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        try {
            Future<Object> one=pool.submit(()->outcome(winner));assertTrue(first.acquired.await(10,TimeUnit.SECONDS));
            Future<Object> two=pool.submit(()->outcome(loser));assertTrue(second.entered.await(10,TimeUnit.SECONDS));
            boolean blocked=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            try(var connection=f.ds().getConnection();var query=connection.prepareStatement("SELECT ? = ANY(pg_blocking_pids(?))")) {
                query.setInt(1,first.backendPid);query.setInt(2,second.backendPid);
                while(System.nanoTime()<deadline&&!blocked){try(var rows=query.executeQuery()){assertTrue(rows.next());blocked=rows.getBoolean(1);}if(!blocked)Thread.sleep(10);}
            }
            assertTrue(blocked,"Actual PostgreSQL lock contention must be observed");assertEquals(1,second.acquired.getCount());
            first.resume.countDown();return List.of(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS));
        } finally {first.resume.countDown();second.resume.countDown();pool.shutdownNow();}
    }
    private void approvalRace(boolean sameKey) throws Exception {
        Fixture f=quoted(true);var first=new RunLockBarrierDataSource(f.ds(),true);var second=new RunLockBarrierDataSource(f.ds(),false);
        var one=PaymentServices.local(first);var two=PaymentServices.local(second);String key=key(),other=sameKey?key:key();
        var results=ordered(f,first,second,()->one.approve(f.session(),f.intent(),key,f.command()),()->two.approve(f.session(),f.intent(),other,f.command()));
        Result winner=(Result)results.getFirst();assertFalse(winner.replayed());
        if(sameKey){Result replay=(Result)results.get(1);assertTrue(replay.replayed());assertEquals(winner.body().get("attemptId").toString(),replay.body().get("attemptId"));assertEquals(winner.body().get("correlationId"),replay.body().get("correlationId"));}
        else assertEquals("VERSION_CONFLICT",results.get(1));
        approvedOnce(f);
    }
    @Test void actualSameKeyRaceReturnsOneApprovalAndOneHistoricalReplay() throws Exception {approvalRace(true);}
    @Test void actualDifferentKeyRaceCannotReserveTwiceForOneCheckoutVersion() throws Exception {approvalRace(false);}
    private void revocationRace(boolean revokeFirst) throws Exception {
        Fixture f=quoted(true);var first=new RunLockBarrierDataSource(f.ds(),true);var second=new RunLockBarrierDataSource(f.ds(),false);
        var one=PaymentServices.local(first);var two=PaymentServices.local(second);
        var results=ordered(f,first,second,
            ()->revokeFirst?one.revoke(f.session(),"fixture-grant-1",key(),new RevokeCommand("user-request")):one.approve(f.session(),f.intent(),key(),f.command()),
            ()->revokeFirst?two.approve(f.session(),f.intent(),key(),f.command()):two.revoke(f.session(),"fixture-grant-1",key(),new RevokeCommand("user-request")));
        assertInstanceOf(Result.class,results.getFirst());
        if(revokeFirst){assertEquals("AUTHORITY_REVOKED",results.get(1));assertEquals(0,count(f,"payment_attempts"));}
        else {assertInstanceOf(Result.class,results.get(1));assertEquals("CANCELLED",f.service().status(f.session(),f.intent()).body().get("state"));assertEquals("RELEASED",f.service().status(f.session(),f.intent()).body().get("exposureState"));}
        assertEquals(0,held(f));assertEquals(0,count(f,"financial_operations"));assertEquals(0,count(f,"outbox"));assertEquals(0,count(f,"simulator_operation_journal"));
    }
    @Test void revocationWinningRunLockPreventsApprovalReservation() throws Exception {revocationRace(true);}
    @Test void approvalWinningRunLockRetainsExistingRevocationReleasePolicy() throws Exception {revocationRace(false);}
    @Test void replayOwnerFencePrecedesPreviouslyAcceptedApproval() throws Exception {
        Fixture f=quoted(false);String key=key();f.approve(key);UUID owner=UUID.randomUUID();
        try(var connection=f.ds().getConnection();var update=connection.prepareStatement("UPDATE simulation_runs SET execution_mode='REPLAY',replay_owner=? WHERE tenant_id=? AND run_id=?")) {
            update.setObject(1,owner);update.setString(2,f.session().tenantId());update.setObject(3,f.session().runId());assertEquals(1,update.executeUpdate());
        }
        assertEquals(403,HttpFailureMapper.map(assertThrows(PaymentException.class,()->f.approve(key))).status());
        var owned=new PaymentService(new JdbcPaymentStore(f.ds(),new JdbcSimulatorAdapter(f.ds()),owner));assertTrue(owned.approve(f.session(),f.intent(),key,f.command()).replayed());approvedOnce(f);
    }
    private static PaymentCommandTransactions transactions(JdbcPaymentStore store) throws Exception {
        Field execution=JdbcPaymentStore.class.getDeclaredField("execution");execution.setAccessible(true);
        Field transactions=ExecutePayment.class.getDeclaredField("transactions");transactions.setAccessible(true);
        return (PaymentCommandTransactions)transactions.get(execution.get(store));
    }
    @Test void fabricatedFactsOrWritesCannotSubstituteForNativeVerification() throws Exception {
        Fixture f=quoted(true);var transactions=transactions(new JdbcPaymentStore(f.ds(),new JdbcSimulatorAdapter(f.ds())));
        assertThrows(PaymentException.class,()->transactions.runApproval(f.session(),tx->{
            var intent=tx.intent(f.intent());var checkout=tx.checkout(intent);var grant=tx.grant("fixture-grant-1");tx.budget(grant.budgetId());
            UUID attempt=UUID.randomUUID();
            var write=new ApprovalWrite(f.intent(),intent.version(),attempt,checkout.revision(),grant.id(),grant.version(),f.session().principalId(),grant.budgetId(),checkout.minorUnits());
            var wrong=new ApprovalWrite(f.intent(),intent.version(),attempt,checkout.revision(),grant.id(),grant.version(),f.session().principalId(),grant.budgetId(),checkout.minorUnits()+1);
            assertThrows(IllegalArgumentException.class,()->tx.stageAttempt(wrong));
            assertThrows(IllegalArgumentException.class,()->tx.reserveAndApprove(write));
            tx.stageAttempt(write);
            var copy=new ApprovalWrite(write.intentId(),write.expectedVersion(),write.attemptId(),write.checkoutRevision(),write.grantId(),write.grantVersion(),write.subjectId(),write.budgetId(),write.minorUnits());
            assertThrows(IllegalArgumentException.class,()->tx.reserveAndApprove(copy));
            var forged=new ApprovalIntent(intent.id(),intent.merchantId(),PaymentRules.PROFILE,intent.state(),intent.version(),intent.checkoutRevision());
            assertThrows(IllegalArgumentException.class,()->tx.issueAndVerifyNative(forged,checkout,grant,write));
            assertThrows(IllegalArgumentException.class,()->tx.reserveAndApprove(write),"Native verification cannot be skipped");
            assertThrows(IllegalStateException.class,()->tx.intent(f.intent()),"Staged facts cannot be replaced");
            throw new PaymentException(ApplicationFailure.INVALID_STATE);
        }));
        assertEquals(0,count(f,"payment_attempts"));assertEquals(0,count(f,"native_authorities"));assertEquals(0,count(f,"reservations"));assertEquals(0,held(f));
    }
    @Test void realApprovalScopeRejectsExpiredCrossThreadAndCrossViewUse() throws Exception {
        Fixture f=quoted(false);var transactions=transactions(new JdbcPaymentStore(f.ds(),new JdbcSimulatorAdapter(f.ds())));
        for(boolean failing:List.of(false,true)) {
            var escaped=new AtomicReference<ApprovalTransaction>();
            ApprovalWork work=tx->{
                escaped.set(tx);assertEquals(CLOCK,tx.now());assertThrows(IllegalArgumentException.class,()->tx.replay(new AcceptedCommand("fabricated")));
                var pool=Executors.newSingleThreadExecutor();
                try{assertInstanceOf(IllegalStateException.class,pool.submit(()->{try{tx.now();return null;}catch(RuntimeException error){return error;}}).get(5,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
                assertThrows(IllegalStateException.class,()->transactions.runApproval(f.session(),nested->new Result(200,Map.of(),false)));
                assertThrows(IllegalStateException.class,()->transactions.run(f.session(),nested->new Result(200,Map.of(),false)));
                if(failing)throw new PaymentException(ApplicationFailure.INVALID_STATE);return new Result(200,Map.of(),false);
            };
            if(failing)assertThrows(PaymentException.class,()->transactions.runApproval(f.session(),work));else transactions.runApproval(f.session(),work);
            var tx=escaped.get();assertThrows(IllegalStateException.class,tx::now);assertThrows(IllegalStateException.class,()->tx.intent(f.intent()));
            assertThrows(IllegalStateException.class,()->tx.checkout(null));assertThrows(IllegalStateException.class,()->tx.grant("fixture-grant-1"));assertThrows(IllegalStateException.class,()->tx.budget("budget-eur"));
            assertThrows(IllegalStateException.class,()->tx.accepted(null));assertThrows(IllegalStateException.class,()->tx.replay(null));assertThrows(IllegalStateException.class,()->tx.stageAttempt(null));
            assertThrows(IllegalStateException.class,()->tx.issueAndVerifyNative(null,null,null,null));assertThrows(IllegalStateException.class,()->tx.reserveAndApprove(null));assertThrows(IllegalStateException.class,()->tx.accept(null,null,null));
        }
        transactions.run(f.session(),tx->{assertThrows(IllegalStateException.class,()->transactions.runApproval(f.session(),nested->new Result(200,Map.of(),false)));return new Result(200,Map.of(),false);});
        assertEquals(0,count(f,"payment_attempts"));assertEquals(0,held(f));
    }
}
