package io.agenticpayments.core;

import io.agenticpayments.application.*;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.infrastructure.*;
import io.agenticpayments.composition.NativeComposition;
import io.agenticpayments.sdk.*;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static io.agenticpayments.core.CoreTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL ownership races; barriers stop adapter I/O after the core commit. */
class WorkerOwnershipIntegrationTest {
    static class Adapter implements PaymentAdapter {
        final JdbcSimulatorAdapter delegate;
        Adapter(DataSource ds){delegate=new JdbcSimulatorAdapter(ds);}
        public CapabilityOffer capabilities(){return delegate.capabilities();}
        public Preparation prepare(ChargeOperation op){return delegate.prepare(op);}
        public Fact execute(ChargeOperation op){return delegate.execute(op);}
        public LookupResult lookup(OperationIdentity id){return delegate.lookup(id);}
        public Fact sealNoEffect(ChargeOperation op){return delegate.sealNoEffect(op);}
    }
    static void await(CountDownLatch latch) {
        try{assertTrue(latch.await(15,TimeUnit.SECONDS),"Adapter barrier deadline");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
    record Fixture(DataSource ds,Session session,PaymentService service,UUID intent) {
        String state(){return service.status(session,intent).body().get("state").toString();}
        String exposure(){return service.status(session,intent).body().get("exposureState").toString();}
        long count(String table)throws Exception{return scalar(ds,"SELECT count(*) FROM "+table+" WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId());}
        Map<String,Object> work(String kind)throws Exception{return row(ds,"SELECT * FROM outbox WHERE tenant_id=? AND run_id=? AND kind=?",session.tenantId(),session.runId(),kind);}
        PaymentService worker(PaymentAdapter adapter){return PaymentServices.local(ds,adapter);}
    }
    static Fixture fixture(String scenario) {
        DataSource ds=database();Session s=isolated(ds);PaymentService service=PaymentServices.local(ds);
        return new Fixture(ds,s,service,preparing(service,s,scenario));
    }

    @Test void supersededPreparationCannotWriteEvenWhileIntentRemainsPreparing() throws Exception {
        var f=fixture("success");var firstEntered=new CountDownLatch(1);var secondEntered=new CountDownLatch(1);
        var firstRelease=new CountDownLatch(1);var secondRelease=new CountDownLatch(1);var calls=new AtomicInteger();
        var adapter=new Adapter(f.ds){public Preparation prepare(ChargeOperation op){
            if(calls.incrementAndGet()==1){firstEntered.countDown();await(firstRelease);return new Preparation(ActionType.SYNTHETIC_CHALLENGE);}
            secondEntered.countDown();await(secondRelease);return new Preparation(ActionType.NONE);
        }};
        var worker=f.worker(adapter);var pool=Executors.newFixedThreadPool(2);
        try{
            var first=pool.submit(()->worker.runNextRecorded(f.session));await(firstEntered);
            var second=pool.submit(()->worker.runNextRecorded(f.session));await(secondEntered);
            assertEquals(2,((Number)f.work("PREPARE").get("fence")).longValue());
            firstRelease.countDown();var stale=first.get(15,TimeUnit.SECONDS);
            assertEquals("PREPARING",f.state());assertEquals("HELD",f.exposure());assertEquals(0,f.count("customer_actions"));
            assertEquals("PREPARE",stale.workKind());assertEquals(1,stale.fence());assertFalse(stale.progressed());
            secondRelease.countDown();var current=second.get(15,TimeUnit.SECONDS);
            assertEquals("READY",f.state());assertEquals(2,current.fence());assertTrue(current.progressed());
            assertEquals(0,f.count("simulator_operation_journal"));assertEquals(1,f.count("financial_operations"));
        }finally{firstRelease.countDown();secondRelease.countDown();pool.shutdownNow();}
    }

    @Test void admittedSendGapReconcilesWithoutResendAndSealFencesDelayedOriginalSend() throws Exception {
        var f=fixture("success");f.service.runNext(f.session);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var sends=new AtomicInteger();
        var adapter=new Adapter(f.ds){public Fact execute(ChargeOperation op){sends.incrementAndGet();entered.countDown();await(release);return super.execute(op);}};
        var worker=f.worker(adapter);var pool=Executors.newSingleThreadExecutor();
        try{
            var send=pool.submit(()->worker.runNextRecorded(f.session));await(entered);
            assertEquals("ADMITTED",f.state());assertEquals(true,f.work("DISPATCH").get("done"));assertEquals(false,f.work("RECONCILE").get("done"));
            var observation=worker.runNextRecorded(f.session);
            assertEquals("RECONCILE",observation.workKind());assertEquals("UNKNOWN",f.state());assertEquals("HELD",f.exposure());
            assertEquals(1,sends.get());assertEquals(0,f.count("simulator_operation_journal"));
            worker.reconcile(f.session,f.intent,true);assertEquals("FAILED",f.state());assertEquals("RELEASED",f.exposure());
            release.countDown();send.get(15,TimeUnit.SECONDS);
            assertEquals("FAILED",f.state());assertEquals(1,sends.get());assertEquals(1,f.count("financial_operations"));
            assertEquals(1,f.count("simulator_operation_journal"));assertEquals(1,f.count("operation_evidence"));
            assertEquals("NO_EFFECT",row(f.ds,"SELECT outcome FROM simulator_operation_journal WHERE tenant_id=? AND run_id=?",f.session.tenantId(),f.session.runId()).get("outcome"));
        }finally{release.countDown();pool.shutdownNow();}
    }

    @Test void receiptRetainsIdleExpiryAndTerminalSelectionSemantics() throws Exception {
        DataSource ds=database();Session s=isolated(ds);PaymentService service=PaymentServices.local(ds);
        var idle=service.runNextRecorded(s);assertNull(idle.operationId());assertNull(idle.workKind());assertEquals(0,idle.fence());assertFalse(idle.progressed());
        var q=quoted(service,s,"success");service.advanceClock(s,CLOCK.plusSeconds(900));
        var expiry=service.runNextRecorded(s);assertNull(expiry.operationId());assertTrue(expiry.progressed());assertTrue(expiry.lastAuditSequence()>=expiry.firstAuditSequence());
        assertEquals("EXPIRED",service.status(s,uuid(q.body().get("intentId"))).body().get("state"));
        var f=fixture("success");f.service.runNext(f.session);f.service.runNext(f.session);
        // Retained obsolete work is a selectable observation, not a new financial operation.
        try(var c=f.ds.getConnection();var p=c.prepareStatement("UPDATE outbox SET done=false WHERE tenant_id=? AND run_id=? AND kind='PREPARE'")){
            p.setString(1,f.session.tenantId());p.setObject(2,f.session.runId());p.executeUpdate();
        }
        long fence=((Number)f.work("PREPARE").get("fence")).longValue();
        var terminal=f.service.runNextRecorded(f.session);assertEquals("PREPARE",terminal.workKind());assertEquals(fence,terminal.fence());assertTrue(terminal.progressed());
        assertEquals("SUCCEEDED",f.state());assertEquals(1,f.count("simulator_operation_journal"));
    }

    @Test void delayedDispatchUnavailabilityCannotEraseNewerPendingOrFinalEvidence() throws Exception {
        for(boolean finish:List.of(false,true)){
            var f=fixture("delayed-success");f.service.runNext(f.session);
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var sends=new AtomicInteger();
            var adapter=new Adapter(f.ds){public Fact execute(ChargeOperation op){
                sends.incrementAndGet();super.execute(op);entered.countDown();await(release);throw new AdapterUnavailableException();
            }};
            var worker=f.worker(adapter);var pool=Executors.newSingleThreadExecutor();
            try{
                var delayed=pool.submit(()->worker.runNextRecorded(f.session));await(entered);
                assertEquals("ADMITTED",f.state());
                if(finish)f.service.runNext(f.session);else worker.runNext(f.session);
                String expected=finish?"SUCCEEDED":"PENDING";assertEquals(expected,f.state());
                long audit=f.count("audit_events");release.countDown();delayed.get(15,TimeUnit.SECONDS);
                assertEquals(expected,f.state());assertEquals(audit,f.count("audit_events"));assertEquals(1,sends.get());
                assertEquals(finish?"CONSUMED":"HELD",f.exposure());assertEquals(1,f.count("financial_operations"));
                assertEquals(finish,f.work("RECONCILE").get("done"));
            }finally{release.countDown();pool.shutdownNow();}
        }
    }

    @Test void scheduledAndManualStaleAbsenceCannotEraseNewerAcceptance() throws Exception {
        for(boolean manual:List.of(false,true))for(boolean unavailable:List.of(false,true)){
            var f=fixture("delayed-success");f.service.runNext(f.session);
            var sendEntered=new CountDownLatch(1);var sendRelease=new CountDownLatch(1);
            var lookupEntered=new CountDownLatch(1);var lookupRelease=new CountDownLatch(1);
            var sender=f.worker(new Adapter(f.ds){public Fact execute(ChargeOperation op){sendEntered.countDown();await(sendRelease);return super.execute(op);}});
            var observer=f.worker(new Adapter(f.ds){public LookupResult lookup(OperationIdentity id){
                lookupEntered.countDown();await(lookupRelease);if(unavailable)throw new AdapterUnavailableException();
                return new LookupResult(LookupResult.Status.NOT_FOUND,Optional.empty());
            }});
            var pool=Executors.newFixedThreadPool(2);
            try{
                var send=pool.submit(()->sender.runNext(f.session));await(sendEntered);
                var lookup=pool.submit(()->{if(manual)observer.reconcile(f.session,f.intent,false);else observer.runNext(f.session);});await(lookupEntered);
                sendRelease.countDown();send.get(15,TimeUnit.SECONDS);assertEquals("PENDING",f.state());
                long audit=f.count("audit_events");lookupRelease.countDown();lookup.get(15,TimeUnit.SECONDS);
                assertEquals("PENDING",f.state());assertEquals("HELD",f.exposure());assertEquals(audit,f.count("audit_events"));
                assertEquals(1,f.count("simulator_operation_journal"));assertEquals(false,f.work("RECONCILE").get("done"));
            }finally{sendRelease.countDown();lookupRelease.countDown();pool.shutdownNow();}
        }
    }

    @Test void supersededReconciliationCannotWriteUnknownEvenBeforeAnyFinancialFact() throws Exception {
        var f=fixture("success");f.service.runNext(f.session);
        var sendEntered=new CountDownLatch(1);var sendRelease=new CountDownLatch(1);
        var lookupEntered=new CountDownLatch(1);var lookupRelease=new CountDownLatch(1);
        var sender=f.worker(new Adapter(f.ds){public Fact execute(ChargeOperation op){sendEntered.countDown();await(sendRelease);return super.execute(op);}});
        var old=f.worker(new Adapter(f.ds){public LookupResult lookup(OperationIdentity id){lookupEntered.countDown();await(lookupRelease);return new LookupResult(LookupResult.Status.NOT_FOUND,Optional.empty());}});
        var fault=new IllegalArgumentException("Synthetic read failure");
        var newer=f.worker(new Adapter(f.ds){public LookupResult lookup(OperationIdentity id){throw fault;}});
        var pool=Executors.newFixedThreadPool(2);
        try{
            var send=pool.submit(()->sender.runNext(f.session));await(sendEntered);
            var stale=pool.submit(()->old.runNext(f.session));await(lookupEntered);
            assertSame(fault,assertThrows(IllegalArgumentException.class,()->newer.reconcile(f.session,f.intent,false)));
            assertEquals(2,((Number)f.work("RECONCILE").get("fence")).longValue());
            long audit=f.count("audit_events");lookupRelease.countDown();stale.get(15,TimeUnit.SECONDS);
            assertEquals("ADMITTED",f.state());assertEquals(audit,f.count("audit_events"));assertEquals("HELD",f.exposure());
            sendRelease.countDown();send.get(15,TimeUnit.SECONDS);assertEquals("SUCCEEDED",f.state());assertEquals(1,f.count("simulator_operation_journal"));
        }finally{sendRelease.countDown();lookupRelease.countDown();pool.shutdownNow();}
    }

    @Test void admissionCommitFailureRollsBackWholeUnitBeforeAnyProviderCall() throws Exception {
        var f=fixture("success");f.service.runNext(f.session);var sends=new AtomicInteger();
        var adapter=new Adapter(f.ds){public Fact execute(ChargeOperation op){sends.incrementAndGet();return super.execute(op);}};
        var fault=new WorkerAdmissionFaultDataSource(f.ds,f.session,false,"XX000");
        long audit=f.count("audit_events");var before=f.work("DISPATCH");
        var worker=PaymentServices.local(fault,adapter);
        assertEquals("TEMPORARILY_UNAVAILABLE",assertThrows(ApplicationException.class,()->worker.runNext(f.session)).code());
        assertNotNull(fault.witness);assertEquals("ADMITTED",fault.witness.state());assertTrue(fault.witness.dispatchDone());assertFalse(fault.witness.reconcileDone());assertEquals(1250,fault.witness.held());
        assertEquals("READY",f.state());assertEquals(before,f.work("DISPATCH"));assertEquals(audit,f.count("audit_events"));
        assertEquals(0,scalar(f.ds,"SELECT count(*) FROM outbox WHERE tenant_id=? AND run_id=? AND kind='RECONCILE'",f.session.tenantId(),f.session.runId()));
        assertEquals(0,scalar(f.ds,"SELECT admission_sequence FROM simulation_runs WHERE tenant_id=? AND run_id=?",f.session.tenantId(),f.session.runId()));
        assertEquals(0,sends.get());assertEquals(0,f.count("simulator_operation_journal"));assertEquals("HELD",f.exposure());
        worker.runNext(f.session);assertEquals("SUCCEEDED",f.state());assertEquals(1,sends.get());
    }

    @Test void serializationRetryDoesNotRepeatProviderSendOrAdmissionIdentity() throws Exception {
        var f=fixture("success");f.service.runNext(f.session);var sends=new AtomicInteger();
        var adapter=new Adapter(f.ds){public Fact execute(ChargeOperation op){sends.incrementAndGet();return super.execute(op);}};
        var fault=new WorkerAdmissionFaultDataSource(f.ds,f.session,false,"40001");
        var receipt=PaymentServices.local(fault,adapter).runNextRecorded(f.session);
        assertNotNull(fault.witness);assertEquals("SUCCEEDED",f.state());assertEquals("DISPATCH",receipt.workKind());assertEquals(1,receipt.fence());
        assertEquals(1,sends.get());assertEquals(1,f.count("financial_operations"));assertEquals(1,f.count("simulator_operation_journal"));
        assertEquals(1,scalar(f.ds,"SELECT admission_sequence FROM simulation_runs WHERE tenant_id=? AND run_id=?",f.session.tenantId(),f.session.runId()));
    }

    @Test void lostAdmissionCommitAcknowledgementNeverSendsAndRecoveryRetainsOriginalExposure() throws Exception {
        var f=fixture("success");f.service.runNext(f.session);var sends=new AtomicInteger();
        var adapter=new Adapter(f.ds){public Fact execute(ChargeOperation op){sends.incrementAndGet();return super.execute(op);}};
        var fault=new WorkerAdmissionFaultDataSource(f.ds,f.session,true,"08006");
        assertEquals("TEMPORARILY_UNAVAILABLE",assertThrows(ApplicationException.class,()->PaymentServices.local(fault,adapter).runNext(f.session)).code());
        assertNotNull(fault.witness);assertEquals("ADMITTED",f.state());assertEquals(true,f.work("DISPATCH").get("done"));assertEquals(0,sends.get());
        var restarted=f.worker(adapter);restarted.runNext(f.session);assertEquals("UNKNOWN",f.state());assertEquals("HELD",f.exposure());
        assertEquals(0,sends.get());assertEquals(0,f.count("simulator_operation_journal"));
        restarted.reconcile(f.session,f.intent,true);assertEquals("FAILED",f.state());assertEquals("RELEASED",f.exposure());
        assertEquals(1,f.count("financial_operations"));assertEquals(1,f.count("simulator_operation_journal"));assertEquals(0,sends.get());
    }

    private static Object field(Object target,String name)throws Exception{
        var field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    private static PaymentWorkerTransactions transactions(JdbcPaymentStore store)throws Exception{
        return (PaymentWorkerTransactions)field(field(store,"worker"),"transactions");
    }
    @Test void workerScopeExpiresOnSuccessAndFailureAndSharesCommandNestingGuard() throws Exception {
        var f=fixture("success");var store=new JdbcPaymentStore(f.ds,new JdbcSimulatorAdapter(f.ds));
        var workers=transactions(store);var commands=(PaymentCommandTransactions)field(field(store,"execution"),"transactions");
        for(boolean failing:List.of(false,true)){
            var escaped=new AtomicReference<PaymentWorkerTransactions.Transaction>();
            PaymentWorkerTransactions.Work<Void> work=tx->{
                escaped.set(tx);assertEquals(CLOCK,tx.now());
                var pool=Executors.newSingleThreadExecutor();
                try{assertInstanceOf(IllegalStateException.class,pool.submit(()->{try{tx.now();return null;}catch(RuntimeException e){return e;}}).get(5,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
                assertThrows(IllegalStateException.class,()->workers.run(f.session,nested->null));
                assertThrows(IllegalStateException.class,()->commands.run(f.session,nested->null));
                assertThrows(IllegalStateException.class,()->commands.runApproval(f.session,nested->null));
                if(failing)throw new PaymentException(ApplicationFailure.INVALID_STATE);return null;
            };
            if(failing)assertThrows(ApplicationException.class,()->workers.run(f.session,work));else workers.run(f.session,work);
            var tx=escaped.get();
            assertThrows(IllegalStateException.class,tx::now);assertThrows(IllegalStateException.class,tx::activity);assertThrows(IllegalStateException.class,tx::expireEligible);
            assertThrows(IllegalStateException.class,tx::next);assertThrows(IllegalStateException.class,()->tx.load(null));assertThrows(IllegalStateException.class,()->tx.findAdmitted(null));
            assertThrows(IllegalStateException.class,()->tx.claim(null));assertThrows(IllegalStateException.class,()->tx.reconciliationClaim(null));assertThrows(IllegalStateException.class,()->tx.isCurrent(null));
            assertThrows(IllegalStateException.class,()->tx.nativeGate(null));assertThrows(IllegalStateException.class,()->tx.terminateBeforeAdmission(null,null));assertThrows(IllegalStateException.class,()->tx.completeObsolete(null));
            assertThrows(IllegalStateException.class,()->tx.admit(null,null));assertThrows(IllegalStateException.class,()->tx.completePreparation(null,null,null));assertThrows(IllegalStateException.class,()->tx.markUnknown(null,null));
            assertThrows(IllegalStateException.class,()->tx.applyVerifiedFact(null,null));assertThrows(IllegalStateException.class,()->tx.receipt(null,null));
        }
        commands.run(f.session,tx->{assertThrows(IllegalStateException.class,()->workers.run(f.session,nested->null));return null;});
        commands.runApproval(f.session,tx->{assertThrows(IllegalStateException.class,()->workers.run(f.session,nested->null));return null;});
        assertEquals("PREPARING",f.state());assertEquals(0,f.count("simulator_operation_journal"));
    }

    @Test void nativeAdmissionCannotUseForgedFactsOrSkipCurrentNativeGate() throws Exception {
        DataSource ds=database();Session s=isolated(ds);PaymentService service=PaymentServices.local(ds);
        var c=service.create(s,key(),new CreateCommand("merchant-demo","success",JdbcPaymentStore.NATIVE_PROFILE,List.of("single-tender","exact-money")));
        UUID id=uuid(c.body().get("intentId"));var q=service.quote(s,id,key(),new QuoteCommand(version(c),"cart-1250-eur",List.of(new Tender("simulator","synthetic-card","fixture-credential-1"))));
        var a=service.approve(s,id,key(),approval(q));service.execute(s,id,key(),new ExecuteCommand(version(a),uuid(a.body().get("attemptId"))));service.runNext(s);
        var store=new JdbcPaymentStore(ds,new JdbcSimulatorAdapter(ds),null,NativeComposition.ports(new JdbcSignedArtifactStore(ds)));
        var workers=transactions(store);var escaped=new AtomicReference<PaymentWorkerTransactions.Snapshot>();
        workers.run(s,tx->{var item=tx.next().orElseThrow();escaped.set(tx.load(item.operationId()));return null;});
        assertThrows(ApplicationException.class,()->workers.run(s,tx->{
            var item=tx.next().orElseThrow();var snapshot=tx.load(item.operationId());var claim=tx.claim(item);
            assertThrows(IllegalArgumentException.class,()->tx.nativeGate(escaped.get()));
            var forged=new PaymentWorkerTransactions.Snapshot(snapshot.operation(),"local-fake-single-tender-0.1",snapshot.state(),snapshot.grantVersion(),snapshot.subjectId(),snapshot.checkoutDigest(),snapshot.grant(),snapshot.reservationState(),false,snapshot.admitted(),snapshot.action());
            assertThrows(IllegalArgumentException.class,()->tx.admit(claim,forged));
            assertThrows(IllegalArgumentException.class,()->tx.admit(claim,snapshot));
            assertTrue(tx.nativeGate(snapshot));tx.admit(claim,snapshot);
            assertThrows(IllegalArgumentException.class,()->tx.admit(claim,snapshot));
            throw new PaymentException(ApplicationFailure.INVALID_STATE);
        }));
        assertEquals("READY",service.status(s,id).body().get("state"));assertEquals(0,scalar(ds,"SELECT admission_sequence FROM simulation_runs WHERE tenant_id=? AND run_id=?",s.tenantId(),s.runId()));
        assertEquals(0,scalar(ds,"SELECT count(*) FROM simulator_operation_journal WHERE tenant_id=? AND run_id=?",s.tenantId(),s.runId()));
        service.runNext(s);assertEquals("SUCCEEDED",service.status(s,id).body().get("state"));
    }

    @Test void explicitCollaboratorsAllowWrappedSimulatorAndPreserveLateAuthenticEvidence() throws Exception {
        var f=fixture("delayed-success");f.service.runNext(f.session);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var verified=new AtomicInteger();
        var adapter=new Adapter(f.ds){public Fact execute(ChargeOperation op){var fact=super.execute(op);entered.countDown();await(release);return fact;}};
        var store=new JdbcPaymentStore(f.ds,adapter,null,null,null,null,null,
            (expected,delivered)->{verified.incrementAndGet();return adapter.delegate.verifyEvent(expected.identity(),delivered.eventId());},
            (expected,pending)->pending);
        var worker=new PaymentService(store);var pool=Executors.newSingleThreadExecutor();
        try{
            var late=pool.submit(()->worker.runNext(f.session));await(entered);
            f.service.runNext(f.session);assertEquals("SUCCEEDED",f.state());assertEquals(1,f.count("operation_evidence"));
            release.countDown();late.get(15,TimeUnit.SECONDS);
            assertEquals(1,verified.get());assertEquals("SUCCEEDED",f.state());assertEquals("CONSUMED",f.exposure());
            assertEquals(2,f.count("operation_evidence"));assertEquals(1,f.count("simulator_operation_journal"));assertEquals(0,f.count("conflict_evidence"));
        }finally{release.countDown();pool.shutdownNow();}
    }

    @Test void injectedSyntheticProgressionRunsOnlyForScheduledWorkThroughProviderWrapper() throws Exception {
        var f=fixture("delayed-success");var adapter=new Adapter(f.ds);var progressed=new AtomicInteger();
        var worker=new PaymentService(new JdbcPaymentStore(f.ds,adapter,null,null,null,null,null,
            (expected,delivered)->adapter.delegate.verifyEvent(expected.identity(),delivered.eventId()),
            (expected,pending)->{progressed.incrementAndGet();return adapter.delegate.finishPending(expected.identity());}));
        worker.runNext(f.session);worker.runNext(f.session);assertEquals("PENDING",f.state());
        worker.reconcile(f.session,f.intent,false);assertEquals("PENDING",f.state());assertEquals(0,progressed.get());
        worker.runNext(f.session);assertEquals("SUCCEEDED",f.state());assertEquals(1,progressed.get());assertEquals(1,f.count("simulator_operation_journal"));
    }
}
