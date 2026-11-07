package io.agenticpayments.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agenticpayments.application.PaymentException;
import io.agenticpayments.application.PaymentService;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.domain.PaymentRules;
import io.agenticpayments.infrastructure.JdbcSimulatorAdapter;
import io.agenticpayments.infrastructure.PaymentServices;
import io.agenticpayments.sdk.*;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.util.*;
import java.util.function.UnaryOperator;

import static io.agenticpayments.core.CoreTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Local durable-handle verification and, separately, privileged trusted-adapter conflict fixtures. */
class AdversarialEvidenceIntegrationTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final List<String> WITNESS_TABLES=List.of("simulation_runs","payment_intents","checkout_snapshots",
            "payment_attempts","reservations","financial_operations","customer_actions","budgets","authority_grants",
            "commands","outbox","event_inbox","operation_evidence","conflict_evidence","audit_events",
            "disputed_exposure","simulator_operation_journal","simulator_journal_events");
    private DataSource ds;
    private Session session;
    private PaymentService service;

    @BeforeEach void isolate() { ds=database();freshRun(); }
    private void freshRun() { session=isolated(ds);service=PaymentServices.local(ds); }
    private long count(String table) throws Exception { return scalar(ds,"SELECT count(*) FROM "+table+" WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId()); }
    private Map<String,Object> budget() throws Exception { return row(ds,"SELECT held,consumed,frozen FROM budgets WHERE tenant_id=? AND run_id=? AND budget_id='budget-eur'",session.tenantId(),session.runId()); }
    private Map<String,Object> operation(UUID id) throws Exception { return row(ds,"SELECT fact_sequence,final_outcome FROM financial_operations WHERE tenant_id=? AND run_id=? AND operation_id=?",session.tenantId(),session.runId(),id); }
    private Result status(UUID intent) { return service.status(session,intent); }
    private static Map<String,Object> stableStatus(Result result) { var body=new LinkedHashMap<>(result.body());body.remove("correlationId");return body; }

    /** Compare every row, including counters/versions, without leaking payloads in failed assertions. */
    private Map<String,String> durableWitness() throws Exception {
        var hashes=new LinkedHashMap<String,String>();
        try(var c=ds.getConnection()) {
            for(String table:WITNESS_TABLES) {
                var rows=new ArrayList<String>();
                try(var p=c.prepareStatement("SELECT row_to_json(t)::text FROM "+table+" t WHERE tenant_id=? AND run_id=? ORDER BY 1")) {
                    p.setString(1,session.tenantId());p.setObject(2,session.runId());
                    try(var r=p.executeQuery()) { while(r.next())rows.add(r.getString(1)); }
                }
                hashes.put(table,rows.size()+":"+PaymentRules.sha256(String.join("\n",rows)));
            }
        }
        return hashes;
    }
    private UUID dispatched(String scenario) {
        UUID intent=preparing(service,session,scenario);assertTrue(service.runNext(session));assertTrue(service.runNext(session));return intent;
    }
    private Fact journalFact(UUID intent) throws Exception {
        String encoded=row(ds,"SELECT identity_json FROM simulator_operation_journal WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId()).get("identity_json").toString();
        var identity=JSON.readValue(encoded,OperationIdentity.class);assertEquals(intent,identity.intentId());
        return new JdbcSimulatorAdapter(ds).lookup(identity).fact().orElseThrow();
    }

    @Test void exactJournalEventReplayHasNoDurableEffect() throws Exception {
        UUID intent=dispatched("success");Fact fact=journalFact(intent);var before=durableWitness();var original=stableStatus(status(intent));
        for(int replay=0;replay<3;replay++) {
            var verifier=new JdbcSimulatorAdapter(ds);assertEquals(fact,verifier.verifyEvent(fact.identity(),fact.eventId()));
            service=PaymentServices.local(ds);service.reconcile(session,intent,false);
            assertEquals(original,stableStatus(status(intent)));assertEquals(before,durableWitness(),"Exact verified event replay must change no durable row");
        }
        assertEquals(1,count("event_inbox"));assertEquals(1,count("operation_evidence"));
        assertEquals(0L,budget().get("held"));assertEquals(1250L,budget().get("consumed"));
    }

    private static Map<String,UnaryOperator<OperationIdentity>> identityVariants() {
        var variants=new LinkedHashMap<String,UnaryOperator<OperationIdentity>>();
        variants.put("tenant",id->with(id,new ExecutionScope("other-tenant",id.scope().runId(),id.scope().merchantId()),id.intentId(),id.attemptId(),id.operationId(),id.adapterKey(),id.requestDigest()));
        variants.put("run",id->with(id,new ExecutionScope(id.scope().tenantId(),UUID.randomUUID(),id.scope().merchantId()),id.intentId(),id.attemptId(),id.operationId(),id.adapterKey(),id.requestDigest()));
        variants.put("merchant",id->with(id,new ExecutionScope(id.scope().tenantId(),id.scope().runId(),"other-merchant"),id.intentId(),id.attemptId(),id.operationId(),id.adapterKey(),id.requestDigest()));
        variants.put("intent",id->with(id,id.scope(),UUID.randomUUID(),id.attemptId(),id.operationId(),id.adapterKey(),id.requestDigest()));
        variants.put("attempt",id->with(id,id.scope(),id.intentId(),UUID.randomUUID(),id.operationId(),id.adapterKey(),id.requestDigest()));
        variants.put("operation",id->with(id,id.scope(),id.intentId(),id.attemptId(),UUID.randomUUID(),id.adapterKey(),id.requestDigest()));
        variants.put("key",id->with(id,id.scope(),id.intentId(),id.attemptId(),id.operationId(),UUID.randomUUID(),id.requestDigest()));
        variants.put("digest",id->with(id,id.scope(),id.intentId(),id.attemptId(),id.operationId(),id.adapterKey(),PaymentRules.sha256("other-binding")));
        return variants;
    }
    private static OperationIdentity with(OperationIdentity ignored,ExecutionScope scope,UUID intent,UUID attempt,UUID operation,UUID key,String digest) {
        return new OperationIdentity(scope,intent,attempt,operation,key,digest);
    }

    @Test void journalVerifierRejectsAbsentAndCrossBoundEventHandles() throws Exception {
        UUID intent=dispatched("success");Fact original=journalFact(intent);var verifier=new JdbcSimulatorAdapter(ds);
        // A real handle for a different operation is not authority for the expected operation.
        var otherId=new OperationIdentity(original.identity().scope(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),PaymentRules.sha256("other-real-operation"));
        Fact other=verifier.execute(new ChargeOperation(otherId,original.money(),"success"));
        var before=durableWitness();
        assertThrows(IllegalArgumentException.class,()->verifier.verifyEvent(original.identity(),UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class,()->verifier.verifyEvent(original.identity(),other.eventId()));
        for(var entry:identityVariants().entrySet()) {
            var altered=entry.getValue().apply(original.identity());
            assertThrows(IllegalArgumentException.class,()->verifier.verifyEvent(altered,original.eventId()),entry.getKey());
            assertEquals(before,durableWitness(),"Rejected handle must not create inbox, evidence or financial effects");
        }
        assertEquals(original,verifier.verifyEvent(original.identity(),original.eventId()));
        assertEquals(before,durableWitness());
    }

    /** This fixture is privileged adapter code, never an HTTP/webhook authentication claim. */
    private static final class TrustedEvidenceAdapter implements PaymentAdapter {
        final JdbcSimulatorAdapter delegate;
        final Deque<LookupResult> scripted=new ArrayDeque<>();
        ChargeOperation operation;
        Fact override;
        boolean sealOnExecute;
        int lookupCalls;
        TrustedEvidenceAdapter(DataSource ds) { delegate=new JdbcSimulatorAdapter(ds); }
        public CapabilityOffer capabilities() { return delegate.capabilities(); }
        public Preparation prepare(ChargeOperation op) { return delegate.prepare(op); }
        public Fact execute(ChargeOperation op) { operation=op;return sealOnExecute?delegate.sealNoEffect(op):delegate.execute(op); }
        public LookupResult lookup(OperationIdentity id) { lookupCalls++;return !scripted.isEmpty()?scripted.removeFirst():override==null?delegate.lookup(id):LookupResult.found(override); }
        public Fact sealNoEffect(ChargeOperation op) { return delegate.sealNoEffect(op); }
    }
    private TrustedEvidenceAdapter trusted() { var adapter=new TrustedEvidenceAdapter(ds);service=PaymentServices.local(ds,adapter);return adapter; }

    @Test void forgedAdapterDeliveryCannotReachInbox() throws Exception {
        for(boolean absentOnVerification:List.of(false,true)) {
            freshRun();var adapter=trusted();UUID intent=dispatched("delayed-success");Fact original=adapter.delegate.lookup(adapter.operation.identity()).fact().orElseThrow();
            Fact forged=new Fact(original.eventId(),original.identity(),new Money("EUR",1251),original.sequence(),Outcome.SUCCEEDED);
            adapter.scripted.add(LookupResult.found(forged));adapter.scripted.add(absentOnVerification?LookupResult.notFound():LookupResult.found(original));
            var before=durableWitness();int lookups=adapter.lookupCalls;
            assertThrows(IllegalArgumentException.class,()->service.reconcile(session,intent,false));
            assertEquals(lookups+2,adapter.lookupCalls,"Delivery must be independently checked through the trusted lookup boundary");
            assertEquals(before,durableWitness());assertEquals("PENDING",status(intent).body().get("state"));
            assertEquals(1250L,budget().get("held"));assertEquals(0L,budget().get("consumed"));
        }
    }

    @Test void authenticatedBindingVariantsAreRetainedWithoutAuthority() throws Exception {
        var variants=new LinkedHashMap<String,UnaryOperator<Fact>>();
        identityVariants().forEach((name,change)->variants.put(name,f->new Fact(UUID.randomUUID(),change.apply(f.identity()),f.money(),f.sequence()+1,Outcome.SUCCEEDED)));
        variants.put("amount",f->new Fact(UUID.randomUUID(),f.identity(),new Money("EUR",1251),f.sequence()+1,Outcome.SUCCEEDED));
        variants.put("currency",f->new Fact(UUID.randomUUID(),f.identity(),new Money("USD",1250),f.sequence()+1,Outcome.SUCCEEDED));
        for(var entry:variants.entrySet()) {
            freshRun();var adapter=trusted();UUID intent=dispatched("success");Fact original=adapter.delegate.lookup(adapter.operation.identity()).fact().orElseThrow();
            var before=status(intent);adapter.override=entry.getValue().apply(original);service.reconcile(session,intent,false);
            assertTerminalUnchanged(before,status(intent));assertEquals(true,budget().get("frozen"),entry.getKey());
            assertEquals(0L,budget().get("held"));assertEquals(1250L,budget().get("consumed"));
            assertEquals(1,count("conflict_evidence"));assertEquals(1,count("disputed_exposure"));
            var retained=row(ds,"SELECT reason,safe_fact_json FROM conflict_evidence WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId());
            assertEquals("binding-conflict",retained.get("reason"));assertEquals(adapter.override,JSON.readValue(retained.get("safe_fact_json").toString(),Fact.class));
            assertEquals(1,scalar(ds,"SELECT count(*) FROM operation_evidence WHERE tenant_id=? AND run_id=? AND accepted",session.tenantId(),session.runId()));
            assertEquals(original,adapter.delegate.lookup(original.identity()).fact().orElseThrow(),"Conflict cannot rewrite the adapter-owned journal");
        }
    }
    private static void assertTerminalUnchanged(Result before,Result after) {
        for(String field:List.of("state","version","exposureState","outcomeEvidenceId","attemptId","operationId"))assertEquals(before.body().get(field),after.body().get(field),field);
        assertEquals(true,after.body().get("reconciliationRequired"));
    }

    @Test void sameSequenceDistinctEventFreezesWithoutConsumption() throws Exception {
        var adapter=trusted();UUID intent=dispatched("delayed-success");Fact pending=adapter.delegate.lookup(adapter.operation.identity()).fact().orElseThrow();
        var before=status(intent);adapter.override=new Fact(UUID.randomUUID(),pending.identity(),pending.money(),pending.sequence(),Outcome.PENDING);
        service.reconcile(session,intent,false);
        assertTerminalUnchanged(before,status(intent));assertEquals("PENDING",status(intent).body().get("state"));assertEquals("HELD",status(intent).body().get("exposureState"));
        assertEquals(1250L,budget().get("held"));assertEquals(0L,budget().get("consumed"));assertEquals(true,budget().get("frozen"));
        assertEquals("sequence-conflict",row(ds,"SELECT reason FROM conflict_evidence WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId()).get("reason"));
        assertEquals(2,count("operation_evidence"));assertEquals(1,scalar(ds,"SELECT count(*) FROM operation_evidence WHERE tenant_id=? AND run_id=? AND accepted",session.tenantId(),session.runId()));
        assertEquals(pending.sequence(),operation(pending.identity().operationId()).get("fact_sequence"));
        var frozen=durableWitness();service.reconcile(session,intent,false);assertEquals(frozen,durableWitness(),"Repeated quarantined event must not duplicate conflict or dispute");
    }

    @Test void contradictorySuccessAfterSealedNoEffectCannotRewriteRelease() throws Exception {
        var adapter=trusted();adapter.sealOnExecute=true;UUID intent=dispatched("success");Fact noEffect=adapter.delegate.lookup(adapter.operation.identity()).fact().orElseThrow();
        assertEquals(Outcome.NO_EFFECT,noEffect.outcome());var before=status(intent);
        adapter.override=new Fact(UUID.randomUUID(),noEffect.identity(),noEffect.money(),noEffect.sequence()+1,Outcome.SUCCEEDED);service.reconcile(session,intent,false);
        assertTerminalUnchanged(before,status(intent));assertEquals("FAILED",status(intent).body().get("state"));assertEquals("RELEASED",status(intent).body().get("exposureState"));
        assertEquals(0L,budget().get("held"));assertEquals(0L,budget().get("consumed"));assertEquals(true,budget().get("frozen"));
        assertEquals("NO_EFFECT",operation(noEffect.identity().operationId()).get("final_outcome"));
        assertEquals("final-conflict",row(ds,"SELECT reason FROM conflict_evidence WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId()).get("reason"));
        assertEquals(1250,scalar(ds,"SELECT minor_units FROM disputed_exposure WHERE tenant_id=? AND run_id=?",session.tenantId(),session.runId()));
        assertEquals(noEffect,adapter.delegate.lookup(noEffect.identity()).fact().orElseThrow());
    }

    @Test void higherSequenceSameFinalCannotReplaceAcceptedProof() throws Exception {
        var adapter=trusted();UUID intent=dispatched("success");Fact original=adapter.delegate.lookup(adapter.operation.identity()).fact().orElseThrow();var before=stableStatus(status(intent));
        adapter.override=new Fact(UUID.randomUUID(),original.identity(),original.money(),original.sequence()+1,Outcome.SUCCEEDED);service.reconcile(session,intent,false);
        assertEquals(before,stableStatus(status(intent)));assertEquals(original.sequence()+1,operation(original.identity().operationId()).get("fact_sequence"));
        assertEquals(2,count("operation_evidence"));assertEquals(1,scalar(ds,"SELECT count(*) FROM operation_evidence WHERE tenant_id=? AND run_id=? AND accepted",session.tenantId(),session.runId()));
        assertEquals(false,row(ds,"SELECT accepted FROM operation_evidence WHERE tenant_id=? AND run_id=? AND event_id=?",session.tenantId(),session.runId(),adapter.override.eventId()).get("accepted"));
        assertEquals(0,count("conflict_evidence"));assertEquals(false,budget().get("frozen"));assertEquals(1250L,budget().get("consumed"));
        assertEquals(original,adapter.delegate.lookup(original.identity()).fact().orElseThrow());
    }

    @Test void rejectedCredentialAndActionCanariesStayOutOfSafeCoreSurfaces() throws Exception {
        String credential="SYNTHETIC_CREDENTIAL_CANARY_"+UUID.randomUUID();String actionSecret="SYNTHETIC_ACTION_CANARY_"+UUID.randomUUID();var canaries=List.of(credential,actionSecret);
        var adapter=trusted();var created=service.create(session,key(),new CreateCommand("merchant-demo","challenge-then-success",PaymentRules.PROFILE,List.of()));UUID intent=uuid(created.body().get("intentId"));
        var rejectedQuote=new QuoteCommand(version(created),"cart-1250-eur",List.of(new Tender("simulator","synthetic-card",credential)));
        assertTrue(JSON.writeValueAsString(rejectedQuote).contains(credential),"Positive control: rejected credential input contains the synthetic canary");
        var before=durableWitness();PaymentException quoteError=assertThrows(PaymentException.class,()->service.quote(session,intent,key(),rejectedQuote));
        assertEquals("UNSUPPORTED_TENDER",quoteError.code());assertEquals(before,durableWitness());assertSafe("credential error",quoteError.toString(),canaries);
        var quoted=service.quote(session,intent,key(),new QuoteCommand(version(created),"cart-1250-eur",List.of(new Tender("simulator","synthetic-card","fixture-credential-1"))));
        assertSafe("trusted checkout",service.checkout(session,intent).body(),canaries);
        var approved=service.approve(session,intent,key(),approval(quoted));service.execute(session,intent,key(),new ExecuteCommand(version(approved),uuid(approved.body().get("attemptId"))));service.runNext(session);
        UUID actionId=uuid(status(intent).body().get("actionId"));var rejectedAction=new CompleteActionCommand(1,actionSecret);
        assertTrue(JSON.writeValueAsString(rejectedAction).contains(actionSecret),"Positive control: rejected action input contains the synthetic canary");
        before=durableWitness();PaymentException actionError=assertThrows(PaymentException.class,()->service.completeAction(session,actionId,key(),rejectedAction));
        assertEquals("INVALID_REQUEST",actionError.code());assertEquals(before,durableWitness());assertSafe("action error",actionError.toString(),canaries);
        assertSafe("graph-safe waiting status",status(intent).body(),canaries);assertSafe("trusted action",service.action(session,actionId).body(),canaries);
        service.completeAction(session,actionId,key(),new CompleteActionCommand(1,"approve"));service.runNext(session);
        Fact fact=adapter.delegate.lookup(adapter.operation.identity()).fact().orElseThrow();assertSafe("durable verified fact",adapter.delegate.verifyEvent(fact.identity(),fact.eventId()),canaries);
        // Produce a nonempty safe conflict surface, rather than checking an empty table.
        adapter.override=new Fact(UUID.randomUUID(),fact.identity(),fact.money(),fact.sequence()+1,Outcome.NO_EFFECT);service.reconcile(session,intent,false);
        assertSafe("settled status",status(intent).body(),canaries);assertEquals(1,count("conflict_evidence"));
        for(String query:List.of("SELECT response_body FROM commands","SELECT row_to_json(a)::text FROM audit_events a","SELECT safe_fact_json FROM conflict_evidence","SELECT identity_json FROM simulator_journal_events")) {
            int observed=0;
            try(var c=ds.getConnection();var p=c.prepareStatement(query+" WHERE tenant_id=? AND run_id=?")) {
                p.setString(1,session.tenantId());p.setObject(2,session.runId());
                try(var r=p.executeQuery()) { while(r.next()){assertSafe("persisted safe projection",JSON.readTree(r.getString(1)),canaries);observed++;} }
            }
            assertTrue(observed>0,"Each claimed persisted privacy surface must have an actual observation");
        }
    }
    private static void assertSafe(String surface,Object value,List<String> canaries) throws Exception {
        JsonNode node=JSON.valueToTree(value);String encoded=JSON.writeValueAsString(node);
        for(String canary:canaries)assertFalse(encoded.contains(canary),surface+" contains a forbidden synthetic canary");
        assertNoSensitiveFields(node,surface);
    }
    private static void assertNoSensitiveFields(JsonNode node,String surface) {
        if(node.isObject())node.fields().forEachRemaining(field->{
            assertFalse(Set.of("credentialReference","credential_reference","canonical_bytes","authorization","otp","privateKey","rawProviderPayload").contains(field.getKey()),surface+" exposes a forbidden sensitive field");
            assertNoSensitiveFields(field.getValue(),surface);
        });
        else if(node.isArray())node.forEach(item->assertNoSensitiveFields(item,surface));
    }
}
