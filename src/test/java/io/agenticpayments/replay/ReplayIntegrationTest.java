package io.agenticpayments.replay;

import io.agenticpayments.http.HttpFailureMapper;

import io.agenticpayments.application.PaymentException;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.infrastructure.PaymentServices;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static io.agenticpayments.replay.ReplayPlan.*;
import static io.agenticpayments.replay.ReplaySql.*;
import static org.junit.jupiter.api.Assertions.*;

class ReplayIntegrationTest {
    private DataSource ds;
    @BeforeEach void database(){var pg=new PGSimpleDataSource();String url=System.getenv("PAYMENTS_DB_URL");if(url==null)throw new IllegalStateException("PostgreSQL test environment is required");pg.setURL(url);pg.setUser(System.getenv("PAYMENTS_DB_USER"));pg.setPassword(System.getenv("PAYMENTS_DB_PASSWORD"));pg.setLoginTimeout(10);pg.setConnectTimeout(10);pg.setSocketTimeout(20);ds=pg;}
    @Test void sameExpandedMultiIntentPlanHasEqualDigestsInFreshPhysicalRuns(){
        ReplayPlan plan=ReplayExamples.twoIntents();ReplayRunner runner=new ReplayRunner(ds);ReplayReport first=runner.execute(plan,"replay-test-a"),second=runner.execute(plan,"replay-test-b");
        assertEquals("PASS",first.outcome(),first.json());assertEquals("PASS",second.outcome(),second.json());assertNotEquals(first.runId(),second.runId());
        assertEquals(first.coreDigest(),second.coreDigest());assertEquals(first.journalDigest(),second.journalDigest());assertEquals(first.auditDigest(),second.auditDigest());assertEquals(first.combinedDigest(),second.combinedDigest());
        assertFalse(first.normalizedSnapshot().toString().contains(first.runId().toString()));assertFalse(first.normalizedSnapshot().toString().contains("fixture-credential-1"));
        assertEquals("alpha-operation",workerOperation(first,"prepare-alpha"));assertEquals("beta-operation",workerOperation(first,"prepare-beta"));assertEquals("beta-operation",workerOperation(first,"dispatch-beta"));assertEquals("alpha-operation",workerOperation(first,"dispatch-alpha"));
    }
    @Test void sourceReconstructionPreservesMultiPendingWorkEnqueueOrder(){
        ReplayPlan plan=ReplayExamples.twoIntents();ReplayRunner runner=new ReplayRunner(ds);var execution=runner.start(plan,"replay-step-test");
        for(int n=0;n<9;n++){ReplayReport report=new ReplayRunner(ds).advance(execution);assertEquals("RUNNING",report.outcome(),report.json());}
        ReplayReport report=new ReplayRunner(ds).continueExecution(execution);assertEquals("PASS",report.outcome(),report.json());
    }
    @Test void ordinaryGraphUiAndBackgroundStoresCannotMutateReplayOwnedRun(){
        ReplayRunner runner=new ReplayRunner(ds);var execution=runner.start(ReplayExamples.twoIntents(),"replay-owner-test");var ordinary=PaymentServices.local(ds);
        var graph=ReplayActors.resolve("replay-owner-test",execution.runId(),"agent");var customer=ReplayActors.resolve("replay-owner-test",execution.runId(),"customer");var background=ReplayActors.resolve("replay-owner-test",execution.runId(),"runner");
        assertEquals(403,HttpFailureMapper.map(assertThrows(PaymentException.class,()->ordinary.create(graph,UUID.randomUUID().toString(),new CreateCommand("merchant-demo","success",io.agenticpayments.domain.PaymentRules.PROFILE,List.of())))).status());
        assertEquals(403,HttpFailureMapper.map(assertThrows(PaymentException.class,()->ordinary.revoke(customer,"fixture-grant-1",UUID.randomUUID().toString(),new RevokeCommand("user-request")))).status());
        assertEquals(403,HttpFailureMapper.map(assertThrows(PaymentException.class,()->ordinary.runNext(background))).status());assertEquals(403,HttpFailureMapper.map(assertThrows(PaymentException.class,()->ordinary.advanceClock(background,Instant.parse("2026-11-06T11:00:00Z")))).status());
        assertEquals(403,HttpFailureMapper.map(assertThrows(PaymentException.class,()->ordinary.seedRun(background,Instant.parse("2026-11-06T10:00:00Z"),1250))).status());
        assertEquals("PASS",runner.continueExecution(execution).outcome());
    }
    @Test void orphanStartedStepIsIncompleteAndDoesNotBlindlySelectAnotherWorkItem()throws Exception{
        ReplayRunner runner=new ReplayRunner(ds);ReplayPlan plan=ReplayExamples.twoIntents();var execution=runner.start(plan,"replay-orphan-test");
        for(int n=0;n<12;n++)assertEquals("RUNNING",runner.advance(execution).outcome());
        var repository=new ReplayRepository(ds);var data=repository.load(execution);repository.begin(data,plan.steps().get(12));
        // Simulate process loss after a worker committed but before the runner persisted DONE.
        var owned=new io.agenticpayments.application.PaymentService(new io.agenticpayments.infrastructure.JdbcPaymentStore(ds,new io.agenticpayments.infrastructure.JdbcSimulatorAdapter(ds),data.owner()));owned.runNext(ReplayActors.resolve(data.tenant(),data.run(),"runner"));repository.discover(data);
        UUID beta=repository.resolve(data,"INTENT","beta");assertEquals("PENDING",owned.status(ReplayActors.resolve(data.tenant(),data.run(),"runner"),beta).body().get("state"));
        long auditBefore=repository.transaction(data,c->number(one(c,"SELECT max(step_sequence) AS value FROM audit_events WHERE tenant_id=? AND run_id=?",data.tenant(),data.run()),"value"));
        ReplayReport report=new ReplayRunner(ds).continueExecution(execution);assertEquals("INCOMPLETE",report.outcome());assertEquals("ORPHAN_STARTED_STEP",report.reason());
        long auditAfter=repository.transaction(data,c->number(one(c,"SELECT max(step_sequence) AS value FROM audit_events WHERE tenant_id=? AND run_id=?",data.tenant(),data.run()),"value"));assertEquals(auditBefore,auditAfter);
        assertEquals("PASS",new ReplayRunner(ds).execute(plan,"replay-orphan-fresh").outcome());assertEquals("INCOMPLETE",new ReplayRunner(ds).inspect(execution).outcome());
        assertEquals("PENDING",owned.status(ReplayActors.resolve(data.tenant(),data.run(),"runner"),beta).body().get("state"));assertEquals("HELD",owned.status(ReplayActors.resolve(data.tenant(),data.run(),"runner"),beta).body().get("exposureState"));
    }
    private ReplayPlan recipe(Recipe recipe){
        var steps=new ArrayList<Step>();ReplayExamples.add(steps,"create-one","agent",Action.CREATE,new Create("one","success",List.of()));ReplayExamples.add(steps,"quote-one","agent",Action.QUOTE,new Quote("one",VersionRef.result("create-one"),"cart-eur"));ReplayExamples.add(steps,"approve-one","customer",Action.APPROVE,new Approve("one",VersionRef.result("quote-one"),"quote-one","grant-eur"));ReplayExamples.add(steps,"execute-one","agent",Action.EXECUTE,new Execute("one",VersionRef.result("approve-one")));ReplayExamples.add(steps,"prepare-one","runner",Action.RUN_NEXT,new RunNext());ReplayExamples.add(steps,"interleave-one","runner",Action.INTERLEAVING,new Interleaving(recipe,"one"));ReplayExamples.add(steps,"assert-one","runner",Action.ASSERT,new AssertState("intent-state-0.1","one",recipe==Recipe.SEAL_SEND_0_1?"FAILED":"SUCCEEDED",recipe==Recipe.SEAL_SEND_0_1?"RELEASED":"CONSUMED"));return ReplayPlan.local("fixed-recipe","-7","2026-11-06T10:00:00Z",1250,steps);
    }
    @Test void bothFiniteBarrierRecipesAreRecordedAndReproducible(){for(Recipe recipe:Recipe.values()){ReplayPlan plan=recipe(recipe);var first=new ReplayRunner(ds).execute(plan,"replay-recipe-a");var second=new ReplayRunner(ds).execute(plan,"replay-recipe-b");assertEquals("PASS",first.outcome(),first.json());assertEquals("PASS",second.outcome(),second.json());assertEquals(first.combinedDigest(),second.combinedDigest());assertTrue(first.normalizedSnapshot().toString().contains("SEND_RELEASED"));}}
    @Test void wrongRawJournalIdentityFailsBeforeNormalizationCanEraseIt()throws Exception{
        ReplayRunner runner=new ReplayRunner(ds);var report=runner.execute(ReplayExamples.twoIntents(),"replay-corrupt-test");assertEquals("PASS",report.outcome());
        try(var c=ds.getConnection()){update(c,"UPDATE simulator_operation_journal SET identity_json=replace(identity_json,?,?) WHERE tenant_id=? AND run_id=?",report.runId().toString(),UUID.randomUUID().toString(),"replay-corrupt-test",report.runId());}
        ReplayReport corrupt=runner.inspect(new ReplayRunner.Execution(report.runId(),report.executionId()));assertEquals("FAIL",corrupt.outcome());assertNull(corrupt.combinedDigest());assertTrue(corrupt.rawInvariantChecks().stream().anyMatch(v->v.contains("journal-core-identity")&&v.endsWith("FAIL")));
    }
    @Test void changedMoneyAndMissingFinalEvidenceCannotNormalizeToAnEqualPass()throws Exception{
        ReplayRunner runner=new ReplayRunner(ds);var money=runner.execute(ReplayExamples.twoIntents(),"replay-money-test");
        try(var c=ds.getConnection()){update(c,"UPDATE simulator_operation_journal SET minor_units=minor_units+1 WHERE tenant_id=? AND run_id=?","replay-money-test",money.runId());}
        var wrong=runner.inspect(new ReplayRunner.Execution(money.runId(),money.executionId()));assertEquals("FAIL",wrong.outcome());assertNull(wrong.combinedDigest());
        var missing=runner.execute(ReplayExamples.twoIntents(),"replay-missing-test");try(var c=ds.getConnection()){update(c,"DELETE FROM operation_evidence WHERE tenant_id=? AND run_id=?","replay-missing-test",missing.runId());}
        var absent=runner.inspect(new ReplayRunner.Execution(missing.runId(),missing.executionId()));assertEquals("FAIL",absent.outcome());assertNull(absent.combinedDigest());
    }
    private String workerOperation(ReplayReport report,String label){
        var audit=(Map<?,?>)report.normalizedSnapshot().get("audit");var schedule=(List<?>)audit.get("schedule");var step=(Map<?,?>)schedule.stream().filter(value->label.equals(((Map<?,?>)value).get("label"))).findFirst().orElseThrow();var receipt=(Map<?,?>)step.get("receipt");return ((Map<?,?>)receipt.get("result")).get("operation").toString();
    }
    @Test void expiryAndRevocationAuditScansUseCreationOrderAcrossFreshRuns(){
        for(boolean expiry:List.of(false,true)){
            var steps=new ArrayList<>(ReplayExamples.twoIntents().steps().subList(0,6));
            if(expiry){ReplayExamples.add(steps,"advance-expiry","runner",Action.ADVANCE_CLOCK,new AdvanceClock("2026-11-06T11:00:00Z"));ReplayExamples.add(steps,"expire-all","runner",Action.RUN_NEXT,new RunNext());}
            else ReplayExamples.add(steps,"revoke-all","customer",Action.REVOKE,new Revoke("grant-eur","user-request"));
            var plan=ReplayPlan.local(expiry?"expiry-order":"revocation-order","0","2026-11-06T10:00:00Z",5000,steps);
            var first=new ReplayRunner(ds).execute(plan,"replay-audit-a");var second=new ReplayRunner(ds).execute(plan,"replay-audit-b");assertEquals("PASS",first.outcome(),first.json());assertEquals("PASS",second.outcome(),second.json());assertEquals(first.auditDigest(),second.auditDigest());
            var audit=(Map<?,?>)first.normalizedSnapshot().get("audit");var rows=(List<?>)audit.get("audit");var terminal=rows.stream().map(value->(Map<?,?>)value).filter(row->(expiry?"EXPIRED":"CANCELLED").equals(row.get("state"))).map(row->row.get("intent").toString()).toList();assertEquals(List.of("alpha","beta"),terminal);
        }
    }
    @Test void executionStateIsReloadedAfterLeaseForContinueAdvanceAndInspect()throws Exception{
        for(String operation:List.of("continue","advance","inspect")){
            var runner=new ReplayRunner(ds);var execution=runner.start(ReplayExamples.twoIntents(),"replay-lease-test");var repository=new ReplayRepository(ds);var data=repository.load(execution);var gated=new ReplayLoadBarrierDataSource(ds);var stale=new ReplayRunner(gated);var pool=Executors.newSingleThreadExecutor();
            try{
                var result=pool.submit(()->switch(operation){case "continue"->stale.continueExecution(execution);case "advance"->stale.advance(execution);default->stale.inspect(execution);});
                assertTrue(gated.loaded.await(10,TimeUnit.SECONDS));repository.state(data,"FAIL",null);gated.resume.countDown();
                assertEquals("FAIL",result.get(15,TimeUnit.SECONDS).outcome());assertTrue(repository.steps(data).isEmpty());
                long count=repository.transaction(data,c->number(one(c,"SELECT count(*) AS value FROM payment_intents WHERE tenant_id=? AND run_id=?",data.tenant(),data.run()),"value"));assertEquals(0L,count);
            }
            finally{gated.resume.countDown();pool.shutdownNow();}
        }
    }
    @Test void unsafeIncompleteDiagnosticRetainsItsReasonAndNeverGainsANewDigest(){
        var plan=ReplayExamples.twoIntents();var runner=new ReplayRunner(ds);var execution=runner.start(plan,"replay-unsafe-test");var repository=new ReplayRepository(ds);var data=repository.load(execution);
        var original=new ReplayReport("simulator-replay-report-0.1","INCOMPLETE",execution.executionId(),execution.runId(),plan.digest(),plan.configurationDigest(),plan.artifacts(),null,null,null,null,List.of("no-active-financial-call-0.1:FAIL"),Map.of("diagnosticOnly",true),"ACTIVE_WORKER_NOT_SNAPSHOT_SAFE");repository.state(data,"INCOMPLETE",original.json());
        assertEquals(original,new ReplayRunner(ds).inspect(execution));assertEquals(original,new ReplayRunner(ds).continueExecution(execution));assertEquals(original,new ReplayRunner(ds).advance(execution));assertTrue(repository.steps(data).isEmpty());
    }
    @Test void rawActionGrantFactAndRecordedRequestBindingsRejectTampering()throws Exception{
        var runner=new ReplayRunner(ds);var plan=ReplayExamples.twoIntents();
        var action=runner.start(plan,"replay-action-negative");for(int n=0;n<9;n++)runner.advance(action);try(var c=ds.getConnection()){update(c,"UPDATE customer_actions SET checkout_digest=? WHERE tenant_id=? AND run_id=?","sha256:"+"0".repeat(64),"replay-action-negative",action.runId());}assertRawFailure(runner.inspect(action),"all-action-immutable-bindings");
        var grant=runner.start(plan,"replay-grant-negative");try(var c=ds.getConnection()){update(c,"UPDATE authority_grants SET budget_id='budget-usd' WHERE tenant_id=? AND run_id=? AND grant_id='fixture-grant-1'","replay-grant-negative",grant.runId());}assertRawFailure(runner.inspect(grant),"trusted-grant-catalog");
        var request=runner.start(plan,"replay-request-negative");runner.advance(request);try(var c=ds.getConnection()){update(c,"UPDATE replay_steps SET request_digest=? WHERE tenant_id=? AND run_id=?","sha256:"+"0".repeat(64),"replay-request-negative",request.runId());}assertRawFailure(runner.inspect(request),"step-plan-request-binding");
        var fact=runner.execute(plan,"replay-fact-negative");assertEquals("PASS",fact.outcome());try(var c=ds.getConnection()){update(c,"UPDATE financial_operations SET fact_sequence=fact_sequence+7 WHERE tenant_id=? AND run_id=?","replay-fact-negative",fact.runId());}assertRawFailure(runner.inspect(new ReplayRunner.Execution(fact.runId(),fact.executionId())),"accepted-fact-sequence");
    }
    private void assertRawFailure(ReplayReport report,String name){assertEquals("FAIL",report.outcome(),report.json());assertNull(report.combinedDigest());assertTrue(report.rawInvariantChecks().stream().anyMatch(check->check.contains(name)&&check.endsWith("FAIL")),report.rawInvariantChecks().toString());}
    @Test void alteredQuoteExpiryAndDeletedAuditRowsCannotProduceReplayEquality()throws Exception{
        var runner=new ReplayRunner(ds);var plan=ReplayExamples.twoIntents();var expiry=runner.start(plan,"replay-expiry-negative");for(int n=0;n<4;n++)runner.advance(expiry);
        try(var c=ds.getConnection()){update(c,"UPDATE checkout_snapshots SET expires_at=expires_at+interval '1 second' WHERE tenant_id=? AND run_id=?","replay-expiry-negative",expiry.runId());}assertRawFailure(runner.inspect(expiry),"checkout-expiry-binding");
        var audit=runner.start(plan,"replay-audit-negative");for(int n=0;n<4;n++)runner.advance(audit);try(var c=ds.getConnection()){update(c,"DELETE FROM audit_events WHERE tenant_id=? AND run_id=? AND step_sequence=1","replay-audit-negative",audit.runId());}assertRawFailure(runner.inspect(audit),"complete-audit-sequence");
    }
    @Test void ledgerConsistentTerminalStatesStillRequireMatchingFinalJournalAndAcceptedProof()throws Exception{
        for(String fabricated:List.of("SUCCEEDED","FAILED")){
            var runner=new ReplayRunner(ds);var plan=ReplayExamples.twoIntents();String tenant=fabricated.equals("SUCCEEDED")?"replay-false-success":"replay-false-no-effect";var execution=runner.start(plan,tenant);for(int n=0;n<13;n++)runner.advance(execution);
            var repository=new ReplayRepository(ds);var data=repository.load(execution);UUID beta=repository.resolve(data,"INTENT","beta");
            try(var c=ds.getConnection()){c.setAutoCommit(false);update(c,"UPDATE payment_intents SET state=? WHERE tenant_id=? AND run_id=? AND intent_id=?",fabricated,tenant,execution.runId(),beta);update(c,"UPDATE reservations SET state=? WHERE tenant_id=? AND run_id=? AND attempt_id=(SELECT attempt_id FROM payment_attempts WHERE tenant_id=? AND run_id=? AND intent_id=?)",fabricated.equals("SUCCEEDED")?"CONSUMED":"RELEASED",tenant,execution.runId(),tenant,execution.runId(),beta);update(c,"UPDATE budgets SET held=held-1250,consumed=consumed+? WHERE tenant_id=? AND run_id=? AND budget_id='budget-eur'",fabricated.equals("SUCCEEDED")?1250:0,tenant,execution.runId());c.commit();}
            assertRawFailure(runner.inspect(execution),fabricated.equals("SUCCEEDED")?"success-final-proof-agreement":"admitted-no-effect-proof-agreement");
        }
    }
    @Test void advisoryLeaseIsExplicitlyUnlockedWhenPoolCloseDoesNotClosePhysicalConnection()throws Exception{
        var retained=new java.util.concurrent.atomic.AtomicReference<Connection>();
        DataSource pooled=(DataSource)java.lang.reflect.Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(proxy,method,args)->{
            Object value=reflect(ds,method,args);if(!(value instanceof Connection physical)||!method.getName().equals("getConnection"))return value;
            var lease=new java.util.concurrent.atomic.AtomicBoolean();
            return java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                if(m.getName().equals("prepareStatement")&&a!=null&&a[0].equals("SELECT pg_try_advisory_lock(?)")){lease.set(true);retained.set(physical);}
                if(m.getName().equals("close")&&lease.get())return null;
                return reflect(physical,m,a);
            });
        });
        try{
            var step=new Step(1,"create-only","agent",Action.CREATE,new Create("only","success",List.of()),"OK");var plan=ReplayPlan.local("pooled-lease","0","2026-11-06T10:00:00Z",1250,List.of(step));assertEquals("PASS",new ReplayRunner(pooled).execute(plan,"replay-pool-test").outcome());assertNotNull(retained.get());assertEquals(0,number(one(retained.get(),"SELECT count(*) AS value FROM pg_locks WHERE pid=pg_backend_pid() AND locktype='advisory'"),"value"));
        }finally{if(retained.get()!=null)retained.get().close();}
    }
    private Object reflect(Object target,java.lang.reflect.Method method,Object[] arguments)throws Throwable{try{return method.invoke(target,arguments);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}}
}
