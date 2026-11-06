package io.agenticpayments.core;

import io.agenticpayments.application.*;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.infrastructure.*;
import io.agenticpayments.sdk.*;
import io.agenticpayments.domain.*;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Mandatory real-PostgreSQL invariants; no embedded substitute or skipped database assertions. */
class PaymentCoreIntegrationTest {
    private static DataSource ds;
    private static final Instant CLOCK=Instant.parse("2026-11-06T10:00:00Z");
    private static final Set<String> ROLES=Set.of("runner:write","intent:write","intent:read","approval:write","execution:write","authority:revoke","action:complete");
    private Session session;
    private PaymentService service;
    @BeforeAll static void database() {
        String url=System.getenv("PAYMENTS_DB_URL");
        if(url==null) throw new IllegalStateException("PAYMENTS_DB_URL, PAYMENTS_DB_USER and PAYMENTS_DB_PASSWORD are required for PostgreSQL tests");
        var pg=new PGSimpleDataSource(); pg.setURL(url); pg.setUser(System.getenv("PAYMENTS_DB_USER")); pg.setPassword(System.getenv("PAYMENTS_DB_PASSWORD")); ds=pg;
        DatabaseMigrator.migrate(ds);
    }
    @BeforeEach void isolatedRun() {
        session=new Session("test-tenant",UUID.randomUUID(),"customer-demo",ROLES,Set.of("merchant-demo"),Set.of("fixture-grant-1","fixture-grant-usd"));
        service=PaymentServices.local(ds); service.seedRun(session,CLOCK,1250);
    }
    private String key() { return UUID.randomUUID().toString(); }
    private UUID id(Object value) { return UUID.fromString(value.toString()); }
    private int version(Result r) { return ((Number)r.body().get("version")).intValue(); }
    private Result created(String scenario) { return service.create(session,key(),new CreateCommand("merchant-demo",scenario,PaymentRules.PROFILE,List.of("single-tender","exact-money"))); }
    private Result quoted(UUID id,int version) { return service.quote(session,id,key(),new QuoteCommand(version,"cart-1250-eur",List.of(new Tender("simulator","synthetic-card","fixture-credential-1")))); }
    private Result approved(UUID id,Result q) { return service.approve(session,id,key(),new ApproveCommand(version(q),((Number)q.body().get("checkoutRevision")).intValue(),q.body().get("checkoutDigest").toString(),"fixture-grant-1")); }
    private UUID ready(String scenario) { var c=created(scenario); var id=id(c.body().get("intentId")); var q=quoted(id,version(c)); var a=approved(id,q); service.execute(session,id,key(),new ExecuteCommand(version(a),id(a.body().get("attemptId")))); return id; }
    private String state(UUID id) { return service.status(session,id).body().get("state").toString(); }
    private String exposure(UUID id) { return service.status(session,id).body().get("exposureState").toString(); }
    private void drain(int limit) { for(int i=0;i<limit&&service.runNext(session);i++) { /* explicit deterministic runner steps */ } }
    private long scalar(String sql,Object... args) throws Exception {
        try(var c=ds.getConnection(); var p=c.prepareStatement(sql)) { for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]); try(var r=p.executeQuery()) { assertTrue(r.next()); return r.getLong(1); } }
    }
    @Test void exactCanonicalCheckoutGoldenBytes() {
        String bytes=PaymentRules.checkout("merchant-demo",1,"cart-1250-eur","EUR","fixture-credential-1");
        assertEquals(402,bytes.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertEquals("sha256:c1f63c9e22cb8953af008e171b42d17fa188e027651ff07fc8620fceb6c238ad",PaymentRules.sha256(bytes));
    }
    @Test void completeChargePersistsAcrossServiceReconstruction() throws Exception {
        UUID intent=ready("success"); drain(5); assertEquals("SUCCEEDED",state(intent)); assertEquals("CONSUMED",exposure(intent));
        service=PaymentServices.local(ds); assertEquals("SUCCEEDED",state(intent));
        assertEquals(1,scalar("SELECT count(*) FROM simulator_operation_journal WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId()));
        assertEquals(1250,scalar("SELECT consumed FROM budgets WHERE tenant_id=? AND run_id=? AND budget_id='budget-eur'",session.tenantId(),session.runId()));
        assertEquals(0,scalar("SELECT held FROM budgets WHERE tenant_id=? AND run_id=? AND budget_id='budget-eur'",session.tenantId(),session.runId()));
    }
    @Test void sameCommandReplayAndConflictHaveNoDuplicateIntent() throws Exception {
        String key=key(); var command=new CreateCommand("merchant-demo","success",PaymentRules.PROFILE,List.of());
        var first=service.create(session,key,command); var replay=service.create(session,key,command);
        assertTrue(replay.replayed()); assertEquals(first.body().get("intentId").toString(),replay.body().get("intentId").toString());
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(PaymentException.class,()->service.create(session,key,new CreateCommand("merchant-demo","delayed-success",PaymentRules.PROFILE,List.of()))).code());
        assertEquals(1,scalar("SELECT count(*) FROM payment_intents WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId()));
    }
    @Test void concurrentReservationsCannotOverspend() throws Exception {
        var c1=created("success");var i1=id(c1.body().get("intentId"));var q1=quoted(i1,1);
        var c2=created("success");var i2=id(c2.body().get("intentId"));var q2=quoted(i2,1);
        var start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> futures=List.of(pool.submit(()->{start.await();try{approved(i1,q1);return "OK";}catch(PaymentException e){return e.code();}}),pool.submit(()->{start.await();try{approved(i2,q2);return "OK";}catch(PaymentException e){return e.code();}}));
            start.countDown(); var outcomes=List.of(futures.get(0).get(10,TimeUnit.SECONDS),futures.get(1).get(10,TimeUnit.SECONDS));
            assertEquals(1,outcomes.stream().filter("OK"::equals).count()); assertTrue(outcomes.contains("BUDGET_EXCEEDED"));
        } finally { pool.shutdownNow(); }
        assertEquals(1250,scalar("SELECT held FROM budgets WHERE tenant_id=? AND run_id=? AND budget_id='budget-eur'",session.tenantId(),session.runId()));
    }
    @Test void responseLossRetainsExposureThenSameJournalResolves() {
        UUID intent=ready("response-loss-after-acceptance"); assertTrue(service.runNext(session)); assertTrue(service.runNext(session));
        assertEquals("UNKNOWN",state(intent)); assertEquals("HELD",exposure(intent));
        service=PaymentServices.local(ds); service.reconcile(session,intent,false); assertEquals("SUCCEEDED",state(intent)); assertEquals("CONSUMED",exposure(intent));
    }
    @Test void challengeRequiresTrustedSubjectAndIsSingleUse() {
        UUID intent=ready("challenge-then-success"); service.runNext(session); assertEquals("WAITING_ACTION",state(intent));
        UUID action=id(service.status(session,intent).body().get("actionId"));
        var wrong=new Session(session.tenantId(),session.runId(),"other-user",ROLES,session.allowedMerchants(),session.allowedGrants());
        assertEquals(404,assertThrows(PaymentException.class,()->service.action(wrong,action)).status());
        String key=key(); var result=service.completeAction(session,action,key,new CompleteActionCommand(1,"approve")); assertEquals("READY",result.body().get("intentState"));
        assertTrue(service.completeAction(session,action,key,new CompleteActionCommand(1,"approve")).replayed());
        assertEquals("ACTION_ALREADY_CONSUMED",assertThrows(PaymentException.class,()->service.completeAction(session,action,key(),new CompleteActionCommand(2,"approve"))).code());
        drain(5); assertEquals("SUCCEEDED",state(intent));
    }
    @Test void challengeDenialReleasesWithoutDispatch() throws Exception {
        UUID intent=ready("challenge-then-success");service.runNext(session); UUID action=id(service.status(session,intent).body().get("actionId"));
        service.completeAction(session,action,key(),new CompleteActionCommand(1,"deny")); assertEquals("FAILED",state(intent)); assertEquals("RELEASED",exposure(intent));
        assertEquals(0,scalar("SELECT count(*) FROM simulator_operation_journal WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId()));
    }
    @Test void actionExpiryAtExactClockBoundaryCannotAdmit() {
        UUID intent=ready("challenge-then-success"); service.runNext(session); UUID action=id(service.status(session,intent).body().get("actionId"));
        service.advanceClock(session,CLOCK.plusSeconds(600));
        assertEquals("ACTION_EXPIRED",assertThrows(PaymentException.class,()->service.completeAction(session,action,key(),new CompleteActionCommand(1,"approve"))).code());
        service.runNext(session); assertEquals("FAILED",state(intent)); assertEquals("RELEASED",exposure(intent));
    }
    @Test void revokeBeforeAdmissionBlocksChargeAndReleasesOnce() throws Exception {
        UUID intent=ready("success"); service.runNext(session); assertEquals("READY",state(intent));
        service.revoke(session,"fixture-grant-1",key(),new RevokeCommand("user-request"));
        service.revoke(session,"fixture-grant-1",key(),new RevokeCommand("user-request")); drain(5);
        assertEquals("CANCELLED",state(intent)); assertEquals("RELEASED",exposure(intent));
        assertEquals(0,scalar("SELECT count(*) FROM simulator_operation_journal WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId()));
    }
    @Test void admittedPendingSuccessSurvivesRevocation() {
        UUID intent=ready("delayed-success"); service.runNext(session); service.runNext(session); assertEquals("PENDING",state(intent));
        service.revoke(session,"fixture-grant-1",key(),new RevokeCommand("user-request")); assertEquals("HELD",exposure(intent));
        service=PaymentServices.local(ds); service.runNext(session); assertEquals("SUCCEEDED",state(intent));
    }
    @Test void differentRunOrTenantCannotReadObjectsOrReplayKeys() {
        var c=created("success");UUID intent=id(c.body().get("intentId"));
        var other=new Session(session.tenantId(),UUID.randomUUID(),session.principalId(),ROLES,session.allowedMerchants(),session.allowedGrants()); service.seedRun(other,CLOCK,1250);
        assertEquals(404,assertThrows(PaymentException.class,()->service.status(other,intent)).status());
        var tenant=new Session("other-tenant",session.runId(),session.principalId(),ROLES,session.allowedMerchants(),session.allowedGrants());service.seedRun(tenant,CLOCK,1250);
        assertEquals(404,assertThrows(PaymentException.class,()->service.status(tenant,intent)).status());
    }
    @Test void splitTenderAndUnknownCapabilitiesRefuseBeforeReservation() throws Exception {
        assertEquals("UNSUPPORTED_CAPABILITY",assertThrows(PaymentException.class,()->service.create(session,key(),new CreateCommand("merchant-demo","success",PaymentRules.PROFILE,List.of("split-tender")))).code());
        UUID intent=id(created("success").body().get("intentId"));var t=new Tender("simulator","synthetic-card","fixture-credential-1");
        assertEquals("UNSUPPORTED_TENDER",assertThrows(PaymentException.class,()->service.quote(session,intent,key(),new QuoteCommand(1,"cart-1250-eur",List.of(t,t)))).code());
        assertEquals(0,scalar("SELECT held FROM budgets WHERE tenant_id=? AND run_id=? AND budget_id='budget-eur'",session.tenantId(),session.runId()));
    }
    @Test void changedCheckoutCannotAuthorizeAndTrustedCheckoutOmitsCredential() {
        UUID intent=id(created("success").body().get("intentId"));var old=quoted(intent,1);var current=quoted(intent,2);
        assertEquals("CHECKOUT_CHANGED",assertThrows(PaymentException.class,()->service.approve(session,intent,key(),new ApproveCommand(3,1,old.body().get("checkoutDigest").toString(),"fixture-grant-1"))).code());
        var review=service.checkout(session,intent);assertEquals(current.body().get("checkoutDigest"),review.body().get("checkoutDigest"));assertFalse(review.body().toString().contains("fixture-credential"));
    }
    @Test void journalSealFencesStaleSendAndChangedScenarioRefuses() {
        var adapter=new JdbcSimulatorAdapter(ds);var scope=new ExecutionScope(session.tenantId(),session.runId(),"merchant-demo");UUID op=UUID.randomUUID();
        var identity=new OperationIdentity(scope,UUID.randomUUID(),UUID.randomUUID(),op,op,PaymentRules.sha256("fixture-binding"));
        var operation=new ChargeOperation(identity,new Money("EUR",1250),"success");
        assertEquals(Outcome.NO_EFFECT,adapter.sealNoEffect(operation).outcome()); assertEquals(Outcome.NO_EFFECT,adapter.execute(operation).outcome());
        assertThrows(IllegalArgumentException.class,()->adapter.execute(new ChargeOperation(identity,new Money("EUR",1250),"definitive-no-effect")));
    }
    private static final class ControlledAdapter implements PaymentAdapter {
        final JdbcSimulatorAdapter delegate;
        volatile CapabilityOffer offer=CapabilityOffer.localFake();
        volatile Preparation forcedPreparation;
        volatile Fact override;
        volatile boolean failBeforeAccept;
        volatile ChargeOperation lastOperation;
        CountDownLatch prepareEntered,prepareContinue,dispatchEntered,dispatchContinue;
        ControlledAdapter(DataSource ds) { delegate=new JdbcSimulatorAdapter(ds); }
        public CapabilityOffer capabilities() { return offer; }
        public Preparation prepare(ChargeOperation op) { gate(prepareEntered,prepareContinue);return forcedPreparation==null?delegate.prepare(op):forcedPreparation; }
        public Fact execute(ChargeOperation op) { lastOperation=op;gate(dispatchEntered,dispatchContinue);if(failBeforeAccept)throw new AdapterUnavailableException();return delegate.execute(op); }
        public LookupResult lookup(OperationIdentity identity) { return override==null?delegate.lookup(identity):LookupResult.found(override); }
        public Fact sealNoEffect(ChargeOperation op) { return delegate.sealNoEffect(op); }
        private static void gate(CountDownLatch entered,CountDownLatch resume) {
            if(entered==null)return;entered.countDown();try{if(!resume.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Test barrier timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Interrupted test barrier");}
        }
    }
    @Test void reducedCapabilitiesFailBeforeReservationAndBeforeAdmission() {
        var controlled=new ControlledAdapter(ds); service=PaymentServices.local(ds,controlled);
        UUID intent=ready("success");service.runNext(session);assertEquals("READY",state(intent));
        controlled.offer=new CapabilityOffer(CapabilityOffer.EXTENSION_VERSION,PaymentRules.PROFILE,CapabilityOffer.CATALOG_VERSION,Set.of("single-tender","exact-money"),CapabilityOffer.localFake().combinations());
        assertEquals("UNSUPPORTED_CAPABILITY",assertThrows(PaymentException.class,()->service.runNext(session)).code());assertEquals("HELD",exposure(intent));assertEquals("READY",state(intent));
        assertEquals("UNSUPPORTED_CAPABILITY",assertThrows(PaymentException.class,()->created("success")).code());
    }
    @Test void faultyPrepareCannotBypassScenarioChallenge() throws Exception {
        var controlled=new ControlledAdapter(ds);controlled.forcedPreparation=new Preparation(ActionType.NONE);service=PaymentServices.local(ds,controlled);
        UUID intent=ready("challenge-then-success");service.runNext(session);assertEquals("FAILED",state(intent));assertEquals("RELEASED",exposure(intent));
        assertEquals(0,scalar("SELECT count(*) FROM simulator_operation_journal WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId()));
    }
    @Test void bareNotFoundRetainsHoldUntilAtomicSealFencesStaleSend() {
        var controlled=new ControlledAdapter(ds);controlled.failBeforeAccept=true;service=PaymentServices.local(ds,controlled);
        UUID intent=ready("success");service.runNext(session);service.runNext(session);assertEquals("UNKNOWN",state(intent));
        service=PaymentServices.local(ds,controlled);service.reconcile(session,intent,false);assertEquals("UNKNOWN",state(intent));assertEquals("HELD",exposure(intent));
        service.reconcile(session,intent,true);assertEquals("FAILED",state(intent));assertEquals("RELEASED",exposure(intent));
        assertEquals(Outcome.NO_EFFECT,controlled.delegate.execute(controlled.lastOperation).outcome());
    }
    @Test void acceptanceBeforeSealCannotBeCancelled() {
        var local=new JdbcSimulatorAdapter(ds);UUID key=UUID.randomUUID();var identity=new OperationIdentity(new ExecutionScope(session.tenantId(),session.runId(),"merchant-demo"),UUID.randomUUID(),UUID.randomUUID(),key,key,PaymentRules.sha256("accept-first"));var op=new ChargeOperation(identity,new Money("EUR",1250),"delayed-success");
        assertEquals(Outcome.PENDING,local.execute(op).outcome());assertEquals(Outcome.PENDING,local.sealNoEffect(op).outcome());
        assertEquals(Outcome.SUCCEEDED,local.finishPending(identity).outcome());assertEquals(Outcome.SUCCEEDED,local.sealNoEffect(op).outcome());
    }
    @Test void revokeBetweenAdmissionAndPhysicalSendAllowsOnlyTheAdmittedOperation() throws Exception {
        var controlled=new ControlledAdapter(ds);controlled.dispatchEntered=new CountDownLatch(1);controlled.dispatchContinue=new CountDownLatch(1);service=PaymentServices.local(ds,controlled);
        UUID intent=ready("success");service.runNext(session);var pool=Executors.newSingleThreadExecutor();
        try {
            var send=pool.submit(()->service.runNext(session));assertTrue(controlled.dispatchEntered.await(10,TimeUnit.SECONDS));assertEquals("ADMITTED",state(intent));
            service.revoke(session,"fixture-grant-1",key(),new RevokeCommand("user-request"));assertEquals("HELD",exposure(intent));
            controlled.dispatchContinue.countDown();assertTrue(send.get(10,TimeUnit.SECONDS));assertEquals("SUCCEEDED",state(intent));assertEquals("CONSUMED",exposure(intent));
        } finally { controlled.dispatchContinue.countDown();pool.shutdownNow(); }
        assertEquals(1,scalar("SELECT count(*) FROM financial_operations WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId()));
    }
    @Test void stalePrepareCannotResurrectRevokedAttempt() throws Exception {
        var controlled=new ControlledAdapter(ds);controlled.prepareEntered=new CountDownLatch(1);controlled.prepareContinue=new CountDownLatch(1);service=PaymentServices.local(ds,controlled);
        UUID intent=ready("challenge-then-success");var pool=Executors.newSingleThreadExecutor();
        try {
            var prepare=pool.submit(()->service.runNext(session));assertTrue(controlled.prepareEntered.await(10,TimeUnit.SECONDS));service.revoke(session,"fixture-grant-1",key(),new RevokeCommand("user-request"));
            controlled.prepareContinue.countDown();assertTrue(prepare.get(10,TimeUnit.SECONDS));assertEquals("CANCELLED",state(intent));assertEquals("RELEASED",exposure(intent));
        } finally { controlled.prepareContinue.countDown();pool.shutdownNow(); }
        assertEquals(0,scalar("SELECT count(*) FROM customer_actions WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId()));
    }
    @Test void conflictingAuthenticatedEventIsRetainedAndCannotReplaceSuccessEvidence() throws Exception {
        var controlled=new ControlledAdapter(ds);service=PaymentServices.local(ds,controlled);UUID intent=ready("success");drain(5);var before=service.status(session,intent);Object evidence=before.body().get("outcomeEvidenceId");
        Fact original=controlled.delegate.lookup(controlled.lastOperation.identity()).fact().orElseThrow();
        controlled.override=new Fact(original.eventId(),original.identity(),original.money(),original.sequence(),Outcome.NO_EFFECT);
        service.reconcile(session,intent,false);assertEquals("SUCCEEDED",state(intent));assertEquals("CONSUMED",exposure(intent));assertEquals(evidence,service.status(session,intent).body().get("outcomeEvidenceId"));
        assertTrue((Boolean)service.status(session,intent).body().get("reconciliationRequired"));
        assertEquals(1,scalar("SELECT count(*) FROM conflict_evidence WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId()));
        assertEquals(1,scalar("SELECT count(*) FROM operation_evidence WHERE tenant_id=? AND run_id=? AND accepted",session.tenantId(),session.runId()));
    }
    @Test void frozenUnknownExposureCannotBeReleasedByLaterNoEffectOrExpiry() throws Exception {
        var controlled=new ControlledAdapter(ds);service=PaymentServices.local(ds,controlled);UUID intent=ready("delayed-success");service.runNext(session);service.runNext(session);assertEquals("PENDING",state(intent));
        Fact original=controlled.delegate.lookup(controlled.lastOperation.identity()).fact().orElseThrow();
        controlled.override=new Fact(UUID.randomUUID(),original.identity(),new Money("EUR",1251),2,Outcome.PENDING);service.reconcile(session,intent,false);
        assertTrue((Boolean)service.status(session,intent).body().get("reconciliationRequired"));
        controlled.override=new Fact(UUID.randomUUID(),original.identity(),original.money(),3,Outcome.NO_EFFECT);service.reconcile(session,intent,false);
        assertEquals("HELD",exposure(intent));assertEquals("PENDING",state(intent));
        service.advanceClock(session,CLOCK.plusSeconds(7200));service.revoke(session,"fixture-grant-1",key(),new RevokeCommand("user-request"));assertEquals("HELD",exposure(intent));
        assertEquals(1250,scalar("SELECT held FROM budgets WHERE tenant_id=? AND run_id=? AND budget_id='budget-eur'",session.tenantId(),session.runId()));
    }
}
