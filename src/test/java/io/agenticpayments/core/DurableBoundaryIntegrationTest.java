package io.agenticpayments.core;

import io.agenticpayments.http.HttpFailureMapper;

import io.agenticpayments.application.PaymentException;
import io.agenticpayments.application.PaymentService;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.infrastructure.*;
import io.agenticpayments.sdk.*;
import org.junit.jupiter.api.*;
import javax.sql.DataSource;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static io.agenticpayments.core.CoreTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Cross-process commit-gap and explicit PostgreSQL lock-order evidence. */
class DurableBoundaryIntegrationTest {
    private DataSource ds;
    private Session session;
    private PaymentService service;
    @BeforeEach void isolate() { ds=database();session=isolated(ds);service=PaymentServices.local(ds); }
    private String state(UUID intent) { return service.status(session,intent).body().get("state").toString(); }
    private String exposure(UUID intent) { return service.status(session,intent).body().get("exposureState").toString(); }
    private long count(String table) throws Exception { return scalar(ds,"SELECT count(*) FROM "+table+" WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId()); }
    private long held() throws Exception { return scalar(ds,"SELECT held FROM budgets WHERE tenant_id=? AND run_id=? AND budget_id='budget-eur'",session.tenantId(),session.runId()); }
    private long consumed() throws Exception { return scalar(ds,"SELECT consumed FROM budgets WHERE tenant_id=? AND run_id=? AND budget_id='budget-eur'",session.tenantId(),session.runId()); }

    @Test void abruptFreshJvmLossAfterJournalCommitRecoversSameChargeExactlyOnce() throws Exception {
        UUID intent=preparing(service,session,"success");service.runNext(session);assertEquals("READY",state(intent));
        UUID operation=uuid(service.status(session,intent).body().get("operationId"));
        assertEquals(73,child("crash-after-journal-commit",intent,operation));
        // These witnesses are read only after the entire child JVM has exited without cleanup.
        assertEquals("ADMITTED",state(intent));assertEquals("HELD",exposure(intent));assertEquals(1250,held());assertEquals(0,consumed());
        assertEquals(0,count("operation_evidence"));assertEquals(1,count("financial_operations"));assertEquals(1,count("simulator_operation_journal"));
        assertEquals("SUCCEEDED",row(ds,"SELECT outcome FROM simulator_operation_journal WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId()).get("outcome"));
        assertEquals(0,child("recover",intent,operation));assertEquals(0,child("recover-replay",intent,operation));
        assertEquals("SUCCEEDED",state(intent));assertEquals("CONSUMED",exposure(intent));assertEquals(0,held());assertEquals(1250,consumed());
        assertEquals(operation,uuid(service.status(session,intent).body().get("operationId")));
        assertEquals(1,count("financial_operations"));assertEquals(1,count("simulator_operation_journal"));assertEquals(1,count("operation_evidence"));
        assertEquals(1,scalar(ds,"SELECT count(*) FROM simulator_journal_events WHERE tenant_id=? AND run_id=? AND outcome='SUCCEEDED'",session.tenantId(),session.runId()));
    }
    private int child(String mode,UUID intent,UUID operation) throws Exception {
        Path executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        Process process=new ProcessBuilder(executable.toString(),"-cp",classpath,CrashWindowProcess.class.getName(),mode,session.tenantId(),session.runId().toString(),intent.toString(),operation.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30,TimeUnit.SECONDS),"Fresh JVM did not finish within bounded deadline");
            String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            if(mode.startsWith("recover"))assertEquals("RECOVERY_OK",output.strip(),"Recovery helper emitted only a safe outcome marker");
            else assertTrue(output.isBlank(),"Crash helper should not print configuration or payloads");
            return process.exitValue();
        } finally { if(process.isAlive())process.destroyForcibly().waitFor(5,TimeUnit.SECONDS); }
    }

    private List<String> ordered(RunLockBarrierDataSource winner,RunLockBarrierDataSource loser,Callable<String> first,Callable<String> second) throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        try {
            Future<String> one=pool.submit(first);assertTrue(winner.acquired.await(10,TimeUnit.SECONDS),"First command must hold actual PostgreSQL run lock");
            Future<String> two=pool.submit(second);assertTrue(loser.entered.await(10,TimeUnit.SECONDS),"Contending command must attempt same lock before release");
            assertEquals(1,loser.acquired.getCount(),"Loser cannot obtain run lock before winner commits");
            winner.resume.countDown();return List.of(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS));
        } finally { winner.resume.countDown();loser.resume.countDown();pool.shutdownNow(); }
    }
    private String outcome(Callable<?> command) throws Exception { try {command.call();return "OK";}catch(PaymentException e){return e.code();} }
    private void approvalClock(boolean clockFirst) throws Exception {
        Result quote=quoted(service,session,"success");UUID intent=uuid(quote.body().get("intentId"));
        var winner=new RunLockBarrierDataSource(ds,true);var loser=new RunLockBarrierDataSource(ds,false);
        PaymentService first=PaymentServices.local(winner),second=PaymentServices.local(loser);
        List<String> results=ordered(winner,loser,
                ()->outcome(()->{if(clockFirst){first.advanceClock(session,CLOCK.plusSeconds(900));return null;}return first.approve(session,intent,key(),approval(quote));}),
                ()->outcome(()->{if(clockFirst)return second.approve(session,intent,key(),approval(quote));second.advanceClock(session,CLOCK.plusSeconds(900));return null;}));
        assertEquals("OK",results.getFirst());
        if(clockFirst){assertEquals("AUTHORITY_EXPIRED",results.get(1));assertEquals(0,held());assertEquals(0,count("payment_attempts"));}
        else {assertEquals("OK",results.get(1));assertEquals("APPROVED",state(intent));assertEquals(1250,held());assertEquals(1,count("payment_attempts"));}
    }
    @Test void clockCommitBeforeApprovalRejectsQuoteAtExactExpiry() throws Exception { approvalClock(true); }
    @Test void approvalCommitBeforeClockAdvanceReservesAtItsValidSnapshot() throws Exception { approvalClock(false); }

    private void admissionClock(boolean clockFirst) throws Exception {
        UUID intent=preparing(service,session,"success");service.runNext(session);assertEquals("READY",state(intent));
        var winner=new RunLockBarrierDataSource(ds,true);var loser=new RunLockBarrierDataSource(ds,false);
        PaymentService first=PaymentServices.local(winner),second=PaymentServices.local(loser);
        var results=ordered(winner,loser,
                ()->outcome(()->{if(clockFirst){first.advanceClock(session,CLOCK.plusSeconds(3600));return null;}return first.runNext(session);}),
                ()->outcome(()->{if(clockFirst)return second.runNext(session);second.advanceClock(session,CLOCK.plusSeconds(3600));return null;}));
        assertEquals(List.of("OK","OK"),results);
        if(clockFirst){assertEquals("EXPIRED",state(intent));assertEquals("RELEASED",exposure(intent));assertEquals(0,count("simulator_operation_journal"));}
        else {assertEquals("SUCCEEDED",state(intent));assertEquals("CONSUMED",exposure(intent));assertEquals(1,count("simulator_operation_journal"));assertEquals(1250,consumed());}
    }
    @Test void clockCommitBeforeAdmissionBlocksAtExactGrantExpiry() throws Exception { admissionClock(true); }
    @Test void admissionCommitBeforeClockAdvanceKeepsItsAuthorizedOutcome() throws Exception { admissionClock(false); }

    private void actionClock(boolean clockFirst) throws Exception {
        UUID intent=preparing(service,session,"challenge-then-success");service.runNext(session);UUID action=uuid(service.status(session,intent).body().get("actionId"));
        var winner=new RunLockBarrierDataSource(ds,true);var loser=new RunLockBarrierDataSource(ds,false);
        PaymentService first=PaymentServices.local(winner),second=PaymentServices.local(loser);
        var results=ordered(winner,loser,
                ()->outcome(()->{if(clockFirst){first.advanceClock(session,CLOCK.plusSeconds(600));return null;}return first.completeAction(session,action,key(),new CompleteActionCommand(1,"approve"));}),
                ()->outcome(()->{if(clockFirst)return second.completeAction(session,action,key(),new CompleteActionCommand(1,"approve"));second.advanceClock(session,CLOCK.plusSeconds(600));return null;}));
        assertEquals("OK",results.getFirst());
        if(clockFirst){assertEquals("ACTION_EXPIRED",results.get(1));assertEquals("WAITING_ACTION",state(intent));}
        else {assertEquals("OK",results.get(1));assertEquals("READY",state(intent));assertEquals("VERIFIED",service.action(session,action).body().get("state"));}
        assertEquals("HELD",exposure(intent));service.runNext(session);assertEquals("FAILED",state(intent));assertEquals("RELEASED",exposure(intent));assertEquals(0,count("simulator_operation_journal"));
    }
    @Test void clockCommitBeforeActionCompletionRejectsAtExactExpiry() throws Exception { actionClock(true); }
    @Test void actionCompletionBeforeClockAdvanceCannotAuthorizeLaterExpiredAdmission() throws Exception { actionClock(false); }

    @Test void trustedCheckoutIsolatesSubjectMerchantTenantRunGrantAndRole() throws Exception {
        Result quote=quoted(service,session,"success");UUID intent=uuid(quote.body().get("intentId"));
        var wrongSubject=new Session(session.tenantId(),session.runId(),"other-subject",ROLES,session.allowedMerchants(),session.allowedGrants());
        var wrongMerchant=new Session(session.tenantId(),session.runId(),session.principalId(),ROLES,Set.of("other-merchant"),session.allowedGrants());
        var wrongGrant=new Session(session.tenantId(),session.runId(),session.principalId(),ROLES,session.allowedMerchants(),Set.of());
        var wrongTenant=session("different-tenant",session.runId());service.seedRun(wrongTenant,CLOCK,1250);
        var wrongRun=session(session.tenantId(),UUID.randomUUID());service.seedRun(wrongRun,CLOCK,1250);
        for(Session denied:List.of(wrongSubject,wrongMerchant,wrongGrant,wrongTenant,wrongRun))assertEquals(404,HttpFailureMapper.map(assertThrows(PaymentException.class,()->service.checkout(denied,intent))).status());
        var graph=new Session(session.tenantId(),session.runId(),"agent-demo",Set.of("intent:read"),session.allowedMerchants(),Set.of());
        assertEquals(403,HttpFailureMapper.map(assertThrows(PaymentException.class,()->service.checkout(graph,intent))).status());
        var trusted=service.checkout(session,intent);assertEquals(quote.body().get("checkoutDigest"),trusted.body().get("checkoutDigest"));assertEquals(quote.body().get("money"),trusted.body().get("money"));assertFalse(trusted.body().toString().contains("fixture-credential"));
        assertEquals(0,count("payment_attempts"));assertEquals(0,held());
    }

    private static final class EvidenceAdapter implements PaymentAdapter {
        final JdbcSimulatorAdapter delegate;
        ChargeOperation operation;
        Fact override;
        EvidenceAdapter(DataSource ds){delegate=new JdbcSimulatorAdapter(ds);}
        public CapabilityOffer capabilities(){return delegate.capabilities();}
        public Preparation prepare(ChargeOperation op){return delegate.prepare(op);}
        public Fact execute(ChargeOperation op){operation=op;return delegate.execute(op);}
        public LookupResult lookup(OperationIdentity id){return override==null?delegate.lookup(id):LookupResult.found(override);}
        public Fact sealNoEffect(ChargeOperation op){return delegate.sealNoEffect(op);}
    }
    private EvidenceAdapter succeeded() {
        var adapter=new EvidenceAdapter(ds);service=PaymentServices.local(ds,adapter);UUID intent=preparing(service,session,"success");service.runNext(session);service.runNext(session);assertEquals("SUCCEEDED",state(intent));return adapter;
    }
    @Test void authenticatedWrongBindingRetainsSafeFactWithoutReplacingSuccessfulEvidence() throws Exception {
        var adapter=succeeded();var original=adapter.delegate.lookup(adapter.operation.identity()).fact().orElseThrow();UUID intent=original.identity().intentId();Object proof=service.status(session,intent).body().get("outcomeEvidenceId");
        OperationIdentity wrong=new OperationIdentity(original.identity().scope(),UUID.randomUUID(),original.identity().attemptId(),original.identity().operationId(),original.identity().adapterKey(),original.identity().requestDigest());
        adapter.override=new Fact(UUID.randomUUID(),wrong,original.money(),99,Outcome.SUCCEEDED);service.reconcile(session,intent,false);
        assertEquals("SUCCEEDED",state(intent));assertEquals("CONSUMED",exposure(intent));assertEquals(proof,service.status(session,intent).body().get("outcomeEvidenceId"));
        assertTrue((Boolean)service.status(session,intent).body().get("reconciliationRequired"));assertEquals(1,count("conflict_evidence"));
        assertTrue(row(ds,"SELECT safe_fact_json FROM conflict_evidence WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId()).get("safe_fact_json").toString().contains(wrong.intentId().toString()));
    }
    @Test void lowerSequenceContradictoryFinalFactIsRetainedAndFreezesAfterSuccess() throws Exception {
        var adapter=new EvidenceAdapter(ds);service=PaymentServices.local(ds,adapter);UUID intent=preparing(service,session,"delayed-success");service.runNext(session);service.runNext(session);
        Fact pending=adapter.delegate.lookup(adapter.operation.identity()).fact().orElseThrow();adapter.delegate.finishPending(pending.identity());service.reconcile(session,intent,false);assertEquals("SUCCEEDED",state(intent));
        Object proof=service.status(session,intent).body().get("outcomeEvidenceId");adapter.override=new Fact(UUID.randomUUID(),pending.identity(),pending.money(),pending.sequence(),Outcome.NO_EFFECT);service.reconcile(session,intent,false);
        assertEquals("SUCCEEDED",state(intent));assertEquals("CONSUMED",exposure(intent));assertEquals(1250,consumed());assertEquals(proof,service.status(session,intent).body().get("outcomeEvidenceId"));
        assertEquals(1,count("conflict_evidence"));assertTrue((Boolean)service.status(session,intent).body().get("reconciliationRequired"));
    }
    @Test void staleAuthenticPendingAfterSuccessCannotRegressOrReplaceOutcomeEvidence() throws Exception {
        var adapter=new EvidenceAdapter(ds);service=PaymentServices.local(ds,adapter);UUID intent=preparing(service,session,"delayed-success");service.runNext(session);service.runNext(session);
        Fact pending=adapter.delegate.lookup(adapter.operation.identity()).fact().orElseThrow();adapter.delegate.finishPending(pending.identity());service.reconcile(session,intent,false);Object proof=service.status(session,intent).body().get("outcomeEvidenceId");
        adapter.override=new Fact(UUID.randomUUID(),pending.identity(),pending.money(),pending.sequence(),Outcome.PENDING);service.reconcile(session,intent,false);assertEquals("SUCCEEDED",state(intent));assertEquals("CONSUMED",exposure(intent));assertEquals(proof,service.status(session,intent).body().get("outcomeEvidenceId"));
        assertEquals(0,count("conflict_evidence"));assertFalse((Boolean)service.status(session,intent).body().get("reconciliationRequired"));assertEquals(1250,consumed());
        assertEquals(3,count("operation_evidence"));assertEquals(2,scalar(ds,"SELECT count(*) FROM operation_evidence WHERE tenant_id=? AND run_id=? AND accepted",session.tenantId(),session.runId()));
    }
}
