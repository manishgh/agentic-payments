package io.agenticpayments.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import io.agenticpayments.application.PaymentException;
import io.agenticpayments.application.NativeApplicationCoordinator;
import io.agenticpayments.application.NativeApplicationCoordinator.*;
import io.agenticpayments.application.PaymentStore;
import io.agenticpayments.application.WorkerReceipt;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.domain.PaymentRules;
import io.agenticpayments.domain.OperationBinding;
import io.agenticpayments.sdk.*;
import io.agenticpayments.composition.Composition.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static io.agenticpayments.infrastructure.Sql.*;

/** Run-serialized normalized transaction repository and local application unit of work. */
public final class JdbcPaymentStore implements PaymentStore {
    public static final String NATIVE_PROFILE="local-ap2-acp-single-tender-0.1";
    private final DataSource ds;
    private final PaymentAdapter adapter;
    private final UUID replayOwner;
    private final Ports composition;
    private final NativeApplicationCoordinator nativeCoordinator;
    private final ObjectMapper json=new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true).configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY,true);
    private record Tx(Connection c,Session s,Instant now) {}
    @FunctionalInterface private interface Work<T> { T run(Tx tx) throws Exception; }
    private record Dispatch(ChargeOperation operation,String kind,long fence) {}
    @Override public Set<String> admissionCapabilities() { return Set.of("atomic-replay-grant-reservation-0.1"); }
    public JdbcPaymentStore(DataSource ds,PaymentAdapter adapter) { this(ds,adapter,(UUID)null); }
    public JdbcPaymentStore(DataSource ds,PaymentAdapter adapter,UUID replayOwner) { this(ds,adapter,replayOwner,null); }
    public JdbcPaymentStore(DataSource ds,PaymentAdapter adapter,UUID replayOwner,Ports composition) {
        this.ds=Objects.requireNonNull(ds); this.adapter=Objects.requireNonNull(adapter);this.replayOwner=replayOwner; this.composition=composition;
        this.nativeCoordinator=composition==null?null:new NativeApplicationCoordinator(composition.checkout(),composition.issuer(),composition.verifier(),composition.artifacts());
    }

    public void seedRun(Session s,Instant clock,long budget) {
        role(s,"runner:write"); if(clock==null||budget<1250) fail(400,"INVALID_REQUEST");
        try(var c=ds.getConnection()) {
            c.setAutoCommit(false);
            update(c,"INSERT INTO simulation_runs(tenant_id,run_id,virtual_time,sequence_origin) VALUES(?,?,?,'RECORDED') ON CONFLICT DO NOTHING",s.tenantId(),s.runId(),clock);
            owner(one(c,"SELECT * FROM simulation_runs WHERE tenant_id=? AND run_id=? FOR UPDATE",s.tenantId(),s.runId()));
            for(String currency:List.of("EUR","USD")) {
                String budgetId="budget-"+currency.toLowerCase(Locale.ROOT), grant=currency.equals("EUR")?"fixture-grant-1":"fixture-grant-usd";
                update(c,"INSERT INTO budgets(tenant_id,run_id,budget_id,currency,limit_minor) VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",s.tenantId(),s.runId(),budgetId,currency,budget);
                update(c,"INSERT INTO authority_grants(tenant_id,run_id,grant_id,budget_id,subject_id,merchant_id,valid_from,expires_at) VALUES(?,?,?,?,?,'merchant-demo',?,?) ON CONFLICT DO NOTHING",s.tenantId(),s.runId(),grant,budgetId,s.principalId(),clock,clock.plusSeconds(3600));
            }
            c.commit();
        } catch(SQLException e) { unavailable(); }
    }
    public Result create(Session s,String key,CreateCommand cmd) {
        role(s,"intent:write");
        capabilities();
        if(cmd==null||cmd.requiredCapabilities()==null||!Set.of(PaymentRules.PROFILE,NATIVE_PROFILE).contains(cmd.profile())) fail(422,"UNSUPPORTED_PROFILE");
        if(NATIVE_PROFILE.equals(cmd.profile())) nativeCapabilities();
        merchant(s,cmd.merchantId());
        if(!PaymentRules.SCENARIOS.contains(cmd.scenarioId())) fail(400,"INVALID_REQUEST");
        if(cmd.requiredCapabilities().size()>16||new HashSet<>(cmd.requiredCapabilities()).size()!=cmd.requiredCapabilities().size()||!PaymentRules.CAPABILITIES.containsAll(cmd.requiredCapabilities())) fail(422,"UNSUPPORTED_CAPABILITY");
        return command(s,key,"POST /intents","new",cmd,tx->{
            var sequence=one(tx.c,"UPDATE simulation_runs SET next_intent_sequence=next_intent_sequence+1 WHERE tenant_id=? AND run_id=? RETURNING next_intent_sequence",s.tenantId(),s.runId());
            UUID id=UUID.randomUUID(); update(tx.c,"INSERT INTO payment_intents(tenant_id,run_id,intent_id,merchant_id,scenario_id,state,creation_sequence,profile) VALUES(?,?,?,?,?,'DRAFT',?,?)",s.tenantId(),s.runId(),id,cmd.merchantId(),cmd.scenarioId(),sequence.get("next_intent_sequence"),cmd.profile());
            audit(tx,id,"DRAFT"); return result(201,map("intentId",id,"state","DRAFT","version",1));
        });
    }
    public Result quote(Session s,UUID id,String key,QuoteCommand cmd) {
        role(s,"intent:write"); validate(cmd==null?0:cmd.expectedVersion());
        capabilities();
        if(cmd.tenders()==null||cmd.tenders().size()!=1) fail(422,"UNSUPPORTED_TENDER");
        var t=cmd.tenders().getFirst();
        if(t==null||!"simulator".equals(t.provider())||!"synthetic-card".equals(t.method())||!"fixture-credential-1".equals(t.credentialReference())) fail(422,"UNSUPPORTED_TENDER");
        if(!Set.of("cart-1250-eur","cart-1250-usd").contains(cmd.cartFixtureId())) fail(400,"INVALID_REQUEST");
        return command(s,key,"POST /intents/{id}/quote",id.toString(),cmd,tx->{
            var i=intent(tx,id); version(i,cmd.expectedVersion()); state(i,"DRAFT","QUOTED");
            int revision=(int)number(i,"checkout_revision")+1; String currency=cmd.cartFixtureId().endsWith("usd")?"USD":"EUR";
            String bytes=PaymentRules.checkout(text(i,"merchant_id"),revision,cmd.cartFixtureId(),currency,t.credentialReference()), digest=PaymentRules.sha256(bytes);
            Instant expiry=tx.now.plusSeconds(900);
            StoredCheckout nativeCheckout=null;
            if(nativeProfile(i)) {
                nativeCapabilities();
                CheckoutFacts facts=new CheckoutFacts(scope(tx),id,text(i,"merchant_id"),revision,currency,1250L,t.credentialReference(),expiry);
                var provision=provision(tx);
                var frozen=nativeCoordinator.freezeCheckout(new CheckoutRequest(facts,provision.merchantSigner()));
                nativeCoordinator.validateCheckout(facts,frozen,keyResolver(tx),tx.now);
                nativeCheckout=nativeCoordinator.retainCheckout(scope(tx),frozen);
                digest=frozen.checkoutDigest(); bytes="native-artifact:"+nativeCheckout.checkoutJwt().id();
            }
            update(tx.c,"INSERT INTO checkout_snapshots(tenant_id,run_id,intent_id,revision,digest,canonical_bytes,currency,minor_units,credential_reference,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?)",s.tenantId(),s.runId(),id,revision,digest,bytes,currency,1250L,t.credentialReference(),expiry);
            if(nativeCheckout!=null) update(tx.c,"INSERT INTO native_checkouts VALUES(?,?,?,?,?)",s.tenantId(),s.runId(),id,revision,encode(nativeCheckout));
            update(tx.c,"UPDATE payment_intents SET checkout_revision=? WHERE tenant_id=? AND run_id=? AND intent_id=?",revision,s.tenantId(),s.runId(),id);
            change(tx,id,"QUOTED"); return result(200,map("intentId",id,"state","QUOTED","version",cmd.expectedVersion()+1,"checkoutRevision",revision,"checkoutDigest",digest,"money",money(currency,1250),"quoteExpiresAt",expiry.toString()));
        });
    }
    public Result approve(Session s,UUID id,String key,ApproveCommand cmd) {
        role(s,"approval:write"); validate(cmd==null?0:cmd.expectedVersion()); grantAllowed(s,cmd.authorityReference());
        capabilities();
        return command(s,key,"POST /intents/{id}/approve",id.toString(),cmd,tx->{
            var i=intent(tx,id); version(i,cmd.expectedVersion()); state(i,"QUOTED"); var q=quote(tx,i); var g=grant(tx,cmd.authorityReference());
            authority(tx,g,s.principalId(),text(i,"merchant_id"));
            if(!tx.now.isBefore(instant(q,"expires_at"))) fail(410,"AUTHORITY_EXPIRED");
            if(cmd.checkoutRevision()!=number(q,"revision")||!Objects.equals(cmd.checkoutDigest(),q.get("digest"))) fail(409,"CHECKOUT_CHANGED");
            var b=budget(tx,text(g,"budget_id")); long total=number(q,"minor_units");
            if(!Objects.equals(b.get("currency"),q.get("currency"))) fail(422,"AUTHORITY_BINDING_INVALID");
            if(Boolean.TRUE.equals(b.get("frozen"))||number(b,"held")+number(b,"consumed")+total>number(b,"limit_minor")) fail(409,"BUDGET_EXCEEDED");
            UUID attempt=UUID.randomUUID();
            update(tx.c,"INSERT INTO payment_attempts VALUES(?,?,?,?,?,?,?,?,'simulator-core')",s.tenantId(),s.runId(),attempt,id,cmd.checkoutRevision(),cmd.authorityReference(),number(g,"version"),s.principalId());
            if(nativeProfile(i)) approveNative(tx,i,q,g,attempt);
            update(tx.c,"INSERT INTO reservations VALUES(?,?,?,?,?,'HELD')",s.tenantId(),s.runId(),attempt,g.get("budget_id"),total);
            update(tx.c,"UPDATE budgets SET held=held+? WHERE tenant_id=? AND run_id=? AND budget_id=?",total,s.tenantId(),s.runId(),g.get("budget_id"));
            change(tx,id,"APPROVED");
            return result(200,map("intentId",id,"state","APPROVED","version",cmd.expectedVersion()+1,"attemptId",attempt,"executionOwner","simulator-core","authorityProfile",nativeProfile(i)?"ap2-v0.2-direct-tap-synthetic-0.1":"local-fake","authorityVersion",number(g,"version"),"reserved",money(text(q,"currency"),total)));
        });
    }
    public Result execute(Session s,UUID id,String key,ExecuteCommand cmd) {
        role(s,"execution:write"); validate(cmd==null?0:cmd.expectedVersion());
        capabilities();
        return command(s,key,"POST /intents/{id}/execute",id.toString(),cmd,tx->{
            var i=intent(tx,id); version(i,cmd.expectedVersion()); state(i,"APPROVED"); var a=attempt(tx,id); var q=quote(tx,i); var g=grant(tx,text(a,"grant_id"));
            authority(tx,g,text(a,"subject_id"),text(i,"merchant_id"));
            if(!eligible(tx,g,a,i)) fail(409,"AUTHORITY_REVOKED");
            if(nativeProfile(i)) verifyNative(tx,i,a,g);
            if(!Objects.equals(cmd.attemptId(),a.get("attempt_id"))) fail(422,"AUTHORITY_BINDING_INVALID");
            UUID op=UUID.randomUUID();
            String digest=new OperationBinding(s.tenantId(),s.runId(),text(i,"merchant_id"),id,(UUID)a.get("attempt_id"),op,text(q,"digest"),text(q,"currency"),number(q,"minor_units"),text(a,"grant_id"),number(a,"grant_version"),text(a,"subject_id"),text(i,"scenario_id"),text(q,"credential_reference")).digest();
            update(tx.c,"INSERT INTO financial_operations(tenant_id,run_id,operation_id,attempt_id,adapter_key,request_digest) VALUES(?,?,?,?,?,?)",s.tenantId(),s.runId(),op,a.get("attempt_id"),op,digest);
            work(tx,op,"PREPARE"); change(tx,id,"PREPARING");
            return result(202,map("intentId",id,"state","PREPARING","version",cmd.expectedVersion()+1,"attemptId",a.get("attempt_id"),"operationId",op));
        });
    }
    public Result status(Session s,UUID id) {
        role(s,"intent:read"); return read(s,tx->{
            var i=intent(tx,id); var a=attempt(tx,id); var q=number(i,"checkout_revision")==0?null:quote(tx,i); var o=a==null?null:operation(tx,(UUID)a.get("attempt_id"));
            var ac=o==null?null:one(tx.c,"SELECT * FROM customer_actions WHERE tenant_id=? AND run_id=? AND operation_id=?",s.tenantId(),s.runId(),o.get("operation_id"));
            var r=a==null?null:reservation(tx,(UUID)a.get("attempt_id"));
            var ev=o==null?null:one(tx.c,"SELECT event_id FROM operation_evidence WHERE tenant_id=? AND run_id=? AND operation_id=? AND accepted AND (CAST(? AS text) IS NULL OR outcome=CAST(? AS text)) ORDER BY sequence DESC LIMIT 1",s.tenantId(),s.runId(),o.get("operation_id"),o.get("final_outcome"),o.get("final_outcome"));
            boolean frozen=r!=null&&Boolean.TRUE.equals(budget(tx,text(r,"budget_id")).get("frozen"));
            return result(200,map("intentId",id,"state",i.get("state"),"version",number(i,"version"),"profile",i.get("profile"),"runId",s.runId(),"attemptId",a==null?null:a.get("attempt_id"),"operationId",o==null?null:o.get("operation_id"),"money",q==null?null:money(text(q,"currency"),number(q,"minor_units")),"exposureState",r==null?"NONE":r.get("state"),"reconciliationRequired",frozen||Set.of("ADMITTED","PENDING","UNKNOWN").contains(i.get("state")),"actionId",ac==null?null:ac.get("action_id"),"outcomeEvidenceId",ev==null?null:ev.get("event_id")));
        });
    }
    public Result checkout(Session s,UUID id) {
        role(s,"approval:write"); return read(s,tx->{
            var i=intent(tx,id); state(i,"QUOTED","APPROVED"); var q=quote(tx,i);
            String grantId=text(q,"currency").equals("USD")?"fixture-grant-usd":"fixture-grant-1"; grantAllowed(s,grantId); var g=grant(tx,grantId);
            if(!Objects.equals(g.get("subject_id"),s.principalId())) fail(404,"RESOURCE_NOT_FOUND");
            return result(200,map("intentId",id,"version",number(i,"version"),"state",i.get("state"),"merchantId",i.get("merchant_id"),"checkoutRevision",number(q,"revision"),"checkoutDigest",q.get("digest"),"money",money(text(q,"currency"),number(q,"minor_units")),"quoteExpiresAt",instant(q,"expires_at").toString()));
        });
    }
    public Result action(Session s,UUID actionId) {
        role(s,"action:complete"); return read(s,tx->{
            var ac=actionRow(tx,actionId); var o=operationById(tx,(UUID)ac.get("operation_id"));
            return result(200,map("actionId",actionId,"version",number(ac,"version"),"type","synthetic-challenge","attemptId",o.get("attempt_id"),"state",ac.get("state"),"expiresAt",instant(ac,"expires_at").toString(),"choices",text(ac,"state").equals("OPEN")?List.of("approve","deny"):List.of()));
        });
    }
    public Result completeAction(Session s,UUID id,String key,CompleteActionCommand cmd) {
        role(s,"action:complete"); validate(cmd==null?0:cmd.expectedVersion()); if(!Set.of("approve","deny").contains(cmd.decision())) fail(400,"INVALID_REQUEST");
        return command(s,key,"POST /actions/{id}/complete",id.toString(),cmd,tx->{
            var ac=actionRow(tx,id); var o=operationById(tx,(UUID)ac.get("operation_id")); var a=attemptById(tx,(UUID)o.get("attempt_id")); var i=intent(tx,(UUID)a.get("intent_id"));
            if(!text(ac,"state").equals("OPEN")) fail(409,"ACTION_ALREADY_CONSUMED"); version(ac,cmd.expectedVersion());
            if(!tx.now.isBefore(instant(ac,"expires_at"))) fail(410,"ACTION_EXPIRED");
            var g=grant(tx,text(a,"grant_id")); authority(tx,g,s.principalId(),text(i,"merchant_id")); state(i,"WAITING_ACTION");
            String next=cmd.decision().equals("approve")?"VERIFIED":"DENIED";
            update(tx.c,"UPDATE customer_actions SET state=?,version=version+1 WHERE tenant_id=? AND run_id=? AND action_id=?",next,s.tenantId(),s.runId(),id);
            if(next.equals("VERIFIED")) { change(tx,(UUID)i.get("intent_id"),"READY"); work(tx,(UUID)o.get("operation_id"),"DISPATCH"); }
            else terminate(tx,i,a,"FAILED");
            return result(200,map("actionId",id,"version",cmd.expectedVersion()+1,"state",next,"intentState",next.equals("VERIFIED")?"READY":"FAILED"));
        });
    }
    public Result revoke(Session s,String authorityId,String key,RevokeCommand cmd) {
        role(s,"authority:revoke"); grantAllowed(s,authorityId);
        if(cmd==null||!Set.of("user-request","policy-revoked","test-revocation").contains(cmd.reason())) fail(400,"INVALID_REQUEST");
        return command(s,key,"POST /authorities/{id}/revoke",authorityId,cmd,tx->{
            var g=grant(tx,authorityId); if(!Objects.equals(g.get("subject_id"),s.principalId())) fail(404,"RESOURCE_NOT_FOUND");
            if(text(g,"state").equals("ACTIVE")) update(tx.c,"UPDATE authority_grants SET state='REVOKED',version=version+1,revoked_at=?,revoked_admission_sequence=(SELECT admission_sequence FROM simulation_runs WHERE tenant_id=? AND run_id=?) WHERE tenant_id=? AND run_id=? AND grant_id=?",tx.now,s.tenantId(),s.runId(),s.tenantId(),s.runId(),authorityId);
            var admitted=new ArrayList<String>();
            for(var a:rows(tx.c,"SELECT a.* FROM payment_attempts a JOIN payment_intents i USING(tenant_id,run_id,intent_id) WHERE a.tenant_id=? AND a.run_id=? AND a.grant_id=? ORDER BY i.creation_sequence",s.tenantId(),s.runId(),authorityId)) {
                var i=intentUnchecked(tx,(UUID)a.get("intent_id")); var o=operation(tx,(UUID)a.get("attempt_id"));
                if(o!=null&&o.get("admitted_sequence")!=null) admitted.add(a.get("attempt_id").toString());
                else if(!PaymentRules.TERMINAL.contains(text(i,"state"))) terminate(tx,i,a,"CANCELLED");
            }
            Collections.sort(admitted); g=grant(tx,authorityId);
            return result(200,map("authorityId",authorityId,"state","REVOKED","version",number(g,"version"),"futureAdmissionBlocked",true,"alreadyAdmittedAttemptIds",admitted));
        });
    }
    public void advanceClock(Session s,Instant clock) {
        role(s,"runner:write"); transaction(s,tx->{
            if(clock==null||clock.isBefore(tx.now)) fail(400,"INVALID_REQUEST");
            update(tx.c,"UPDATE simulation_runs SET virtual_time=? WHERE tenant_id=? AND run_id=?",clock,s.tenantId(),s.runId());audit(new Tx(tx.c,s,clock),null,"CLOCK_ADVANCED");return null;
        });
    }
    public boolean runNext(Session s) {
        WorkerReceipt receipt=runNextRecorded(s);return receipt.operationId()!=null||receipt.progressed();
    }
    public WorkerReceipt runNextRecorded(Session s) {
        role(s,"runner:write");
        capabilities();
        long[] before=new long[4];Object[] selection=new Object[3];
        Dispatch d=transaction(s,tx->{
            activity(tx,before);
            expire(tx);
            var pending=one(tx.c,"SELECT w.*,a.intent_id FROM outbox w JOIN financial_operations o USING(tenant_id,run_id,operation_id) JOIN payment_attempts a USING(tenant_id,run_id,attempt_id) WHERE w.tenant_id=? AND w.run_id=? AND NOT w.done ORDER BY CASE w.kind WHEN 'PREPARE' THEN 0 WHEN 'DISPATCH' THEN 1 ELSE 2 END,w.enqueue_sequence LIMIT 1",s.tenantId(),s.runId());
            if(pending==null) return null;
            UUID op=(UUID)pending.get("operation_id"), id=(UUID)pending.get("intent_id"); var i=intentUnchecked(tx,id); var a=attempt(tx,id); String kind=text(pending,"kind");
            selection[0]=op;selection[1]=kind;selection[2]=number(pending,"fence");
            if(PaymentRules.TERMINAL.contains(text(i,"state"))) { done(tx,op,kind); return null; }
            long fence=number(pending,"fence")+1;
            selection[2]=fence;
            update(tx.c,"UPDATE outbox SET fence=? WHERE tenant_id=? AND run_id=? AND operation_id=? AND kind=?",fence,s.tenantId(),s.runId(),op,kind);
            if(kind.equals("PREPARE")&&nativeProfile(i)&&!nativeGate(tx,i,a,grant(tx,text(a,"grant_id")))) return null;
            if(kind.equals("DISPATCH")) {
                if(!text(i,"state").equals("READY")) { done(tx,op,kind); return null; }
                var g=grant(tx,text(a,"grant_id")); var r=reservation(tx,(UUID)a.get("attempt_id")); var b=budget(tx,text(r,"budget_id"));
                if(Boolean.TRUE.equals(b.get("frozen"))) { done(tx,op,kind); return null; }
                if(!eligible(tx,g,a,i)) { terminate(tx,i,a,text(g,"state").equals("REVOKED")?"CANCELLED":"EXPIRED"); return null; }
                if(nativeProfile(i)&&!nativeGate(tx,i,a,g)) return null;
                var ac=one(tx.c,"SELECT * FROM customer_actions WHERE tenant_id=? AND run_id=? AND operation_id=?",s.tenantId(),s.runId(),op);
                boolean needsAction=text(i,"scenario_id").equals("challenge-then-success");
                var q=quote(tx,i);
                if(needsAction&&(ac==null||!text(ac,"state").equals("VERIFIED")||!tx.now.isBefore(instant(ac,"expires_at"))||!Objects.equals(ac.get("subject_id"),a.get("subject_id"))||!Objects.equals(ac.get("checkout_digest"),q.get("digest")))) { terminate(tx,i,a,"FAILED"); return null; }
                if(!text(r,"state").equals("HELD")) throw new IllegalStateException("Missing execution exposure");
                var seq=one(tx.c,"UPDATE simulation_runs SET admission_sequence=admission_sequence+1 WHERE tenant_id=? AND run_id=? RETURNING admission_sequence",s.tenantId(),s.runId());
                update(tx.c,"UPDATE financial_operations SET admitted_sequence=?,admitted_at=?,admitted_grant_version=? WHERE tenant_id=? AND run_id=? AND operation_id=?",seq.get("admission_sequence"),tx.now,g.get("version"),s.tenantId(),s.runId(),op);
                change(tx,id,"ADMITTED"); done(tx,op,"DISPATCH"); work(tx,op,"RECONCILE");
            }
            return new Dispatch(envelope(tx,i,a,operationById(tx,op)),kind,fence);
        });
        if(d==null) return receipt(s,before,selection);
        if(d.kind.equals("PREPARE")) {
            Preparation preparation=adapter.prepare(d.operation);
            transaction(s,tx->{
                var id=d.operation.identity(); var i=intentUnchecked(tx,id.intentId()); var a=attempt(tx,id.intentId());
                if(!text(i,"state").equals("PREPARING")||!fence(tx,id.operationId(),"PREPARE",d.fence)) return null;
                var g=grant(tx,text(a,"grant_id"));
                if(!eligible(tx,g,a,i)) { terminate(tx,i,a,text(g,"state").equals("REVOKED")?"CANCELLED":"EXPIRED"); return null; }
                if(nativeProfile(i)&&!nativeGate(tx,i,a,g)) return null;
                if((preparation.actionType()==ActionType.SYNTHETIC_CHALLENGE)!=text(i,"scenario_id").equals("challenge-then-success")) { terminate(tx,i,a,"FAILED"); return null; }
                if(preparation.actionType()==ActionType.SYNTHETIC_CHALLENGE) {
                    var q=quote(tx,i);
                    update(tx.c,"INSERT INTO customer_actions(tenant_id,run_id,action_id,operation_id,subject_id,checkout_digest,state,expires_at) VALUES(?,?,?,?,?,?,'OPEN',?) ON CONFLICT DO NOTHING",s.tenantId(),s.runId(),UUID.randomUUID(),id.operationId(),a.get("subject_id"),q.get("digest"),tx.now.plusSeconds(600));
                    change(tx,id.intentId(),"WAITING_ACTION");
                } else { change(tx,id.intentId(),"READY"); work(tx,id.operationId(),"DISPATCH"); }
                done(tx,id.operationId(),"PREPARE"); return null;
            });
        } else if(d.kind.equals("DISPATCH")) {
            try { acceptFact(s,d.operation,adapter.execute(d.operation)); }
            catch(AdapterUnavailableException e) { markUnknown(s,d.operation.identity()); }
        } else {
            try {
                LookupResult lookup=adapter.lookup(d.operation.identity());
                if(lookup.status()==LookupResult.Status.FOUND) {
                    Fact fact=lookup.fact().orElseThrow();
                    // Explicit scheduler step progresses a delayed synthetic scenario, never a live provider.
                    if(fact.outcome()==Outcome.PENDING&&adapter instanceof JdbcSimulatorAdapter local) fact=local.finishPending(d.operation.identity());
                    acceptFact(s,d.operation,fact);
                } else markUnknown(s,d.operation.identity());
            } catch(AdapterUnavailableException e) { markUnknown(s,d.operation.identity()); }
        }
        return receipt(s,before,selection);
    }
    public void reconcile(Session s,UUID id,boolean sealIfAbsent) {
        role(s,"runner:write"); ChargeOperation op=transaction(s,tx->{
            var i=intentUnchecked(tx,id); var a=attempt(tx,id); if(a==null) fail(409,"INVALID_STATE"); var o=operation(tx,(UUID)a.get("attempt_id"));
            if(o==null||o.get("admitted_sequence")==null) fail(409,"INVALID_STATE"); return envelope(tx,i,a,o);
        });
        try {
            var lookup=adapter.lookup(op.identity());
            if(lookup.status()==LookupResult.Status.FOUND) acceptFact(s,op,lookup.fact().orElseThrow());
            else if(lookup.status()==LookupResult.Status.NOT_FOUND&&sealIfAbsent) acceptFact(s,op,adapter.sealNoEffect(op));
            else markUnknown(s,op.identity());
        } catch(AdapterUnavailableException e) { markUnknown(s,op.identity()); }
    }
    private void acceptFact(Session s,ChargeOperation expected,Fact delivered) {
        Fact verified;
        if(adapter instanceof JdbcSimulatorAdapter local) verified=local.verifyEvent(expected.identity(),delivered.eventId());
        else verified=adapter.lookup(expected.identity()).fact().filter(delivered::equals).orElseThrow(()->new IllegalArgumentException("Unverified adapter fact"));
        if(!verified.equals(delivered)) throw new IllegalArgumentException("Unverified adapter fact");
        transaction(s,tx->{
            var id=expected.identity(); var i=intentUnchecked(tx,id.intentId()); var a=attempt(tx,id.intentId()); var o=operationById(tx,id.operationId());
            String digest=PaymentRules.sha256(encode(delivered));
            var existing=one(tx.c,"SELECT * FROM event_inbox WHERE tenant_id=? AND run_id=? AND event_id=?",s.tenantId(),s.runId(),delivered.eventId());
            if(existing!=null) {
                if(!Objects.equals(existing.get("fact_digest"),digest)) { conflict(tx,delivered,id.operationId(),digest,"event-conflict"); freeze(tx,a,id.operationId(),expected.money().minorUnits(),"event-conflict"); }
                return null;
            }
            update(tx.c,"INSERT INTO event_inbox VALUES(?,?,?,?,?)",s.tenantId(),s.runId(),delivered.eventId(),id.operationId(),digest);
            update(tx.c,"INSERT INTO operation_evidence(tenant_id,run_id,event_id,operation_id,sequence,outcome,fact_digest) VALUES(?,?,?,?,?,?,?)",s.tenantId(),s.runId(),delivered.eventId(),id.operationId(),delivered.sequence(),delivered.outcome().name(),digest);
            if(!delivered.identity().equals(id)||!delivered.money().equals(expected.money())) { conflict(tx,delivered,id.operationId(),digest,"binding-conflict"); freeze(tx,a,id.operationId(),expected.money().minorUnits(),"binding-conflict"); return null; }
            String finalOutcome=text(o,"final_outcome");
            if(finalOutcome!=null&&!finalOutcome.equals(delivered.outcome().name())&&delivered.outcome()!=Outcome.PENDING) { conflict(tx,delivered,id.operationId(),digest,"final-conflict"); freeze(tx,a,id.operationId(),expected.money().minorUnits(),"final-conflict"); return null; }
            if(delivered.sequence()<number(o,"fact_sequence")) return null;
            if(delivered.sequence()==number(o,"fact_sequence")&&number(o,"fact_sequence")>0) { conflict(tx,delivered,id.operationId(),digest,"sequence-conflict"); freeze(tx,a,id.operationId(),expected.money().minorUnits(),"sequence-conflict"); return null; }
            update(tx.c,"UPDATE financial_operations SET fact_sequence=? WHERE tenant_id=? AND run_id=? AND operation_id=?",delivered.sequence(),s.tenantId(),s.runId(),id.operationId());
            if(finalOutcome!=null) return null;
            if(delivered.outcome()==Outcome.PENDING) { acceptEvidence(tx,delivered.eventId()); change(tx,id.intentId(),"PENDING"); }
            else {
                var currentReservation=reservation(tx,(UUID)a.get("attempt_id"));
                if(delivered.outcome()==Outcome.NO_EFFECT&&Boolean.TRUE.equals(budget(tx,text(currentReservation,"budget_id")).get("frozen"))) return null;
                acceptEvidence(tx,delivered.eventId());
                String next=delivered.outcome()==Outcome.SUCCEEDED?"SUCCEEDED":"FAILED";
                settle(tx,a,delivered.outcome()==Outcome.SUCCEEDED?"CONSUMED":"RELEASED"); change(tx,id.intentId(),next);
                update(tx.c,"UPDATE financial_operations SET final_outcome=? WHERE tenant_id=? AND run_id=? AND operation_id=?",delivered.outcome().name(),s.tenantId(),s.runId(),id.operationId());
                done(tx,id.operationId(),"RECONCILE");
            }
            return null;
        });
    }
    private void markUnknown(Session s,OperationIdentity id) {
        transaction(s,tx->{ var i=intentUnchecked(tx,id.intentId()); if(!PaymentRules.TERMINAL.contains(text(i,"state"))&&!text(i,"state").equals("UNKNOWN")) change(tx,id.intentId(),"UNKNOWN"); work(tx,id.operationId(),"RECONCILE"); return null; });
    }
    private void expire(Tx tx) throws Exception {
        for(var i:rows(tx.c,"SELECT * FROM payment_intents WHERE tenant_id=? AND run_id=? AND state IN ('QUOTED','APPROVED','PREPARING','WAITING_ACTION','READY') ORDER BY creation_sequence",tx.s.tenantId(),tx.s.runId())) {
            var a=attempt(tx,(UUID)i.get("intent_id"));
            if(a==null) { if(!tx.now.isBefore(instant(quote(tx,i),"expires_at"))) change(tx,(UUID)i.get("intent_id"),"EXPIRED"); continue; }
            var g=grant(tx,text(a,"grant_id"));
            if(!eligible(tx,g,a,i)) { terminate(tx,i,a,text(g,"state").equals("REVOKED")?"CANCELLED":"EXPIRED"); continue; }
            if(nativeProfile(i)) {
                var nativeAuthority=one(tx.c,"SELECT expires_at FROM native_authorities WHERE tenant_id=? AND run_id=? AND attempt_id=?",tx.s.tenantId(),tx.s.runId(),a.get("attempt_id"));
                if(nativeAuthority==null) { terminate(tx,i,a,"CANCELLED"); continue; }
                if(!tx.now.isBefore(instant(nativeAuthority,"expires_at"))) { terminate(tx,i,a,"EXPIRED"); continue; }
            }
            var o=operation(tx,(UUID)a.get("attempt_id"));
            var ac=o==null?null:one(tx.c,"SELECT * FROM customer_actions WHERE tenant_id=? AND run_id=? AND operation_id=?",tx.s.tenantId(),tx.s.runId(),o.get("operation_id"));
            if(ac!=null&&!tx.now.isBefore(instant(ac,"expires_at"))) terminate(tx,i,a,"FAILED");
        }
    }
    private boolean eligible(Tx tx,Map<String,Object> g,Map<String,Object> a,Map<String,Object> i) {
        return text(g,"state").equals("ACTIVE")&&number(g,"version")==number(a,"grant_version")&&!tx.now.isBefore(instant(g,"valid_from"))&&tx.now.isBefore(instant(g,"expires_at"))&&Objects.equals(g.get("subject_id"),a.get("subject_id"))&&Objects.equals(g.get("merchant_id"),i.get("merchant_id"));
    }
    private void terminate(Tx tx,Map<String,Object> i,Map<String,Object> a,String next) throws SQLException {
        if(PaymentRules.TERMINAL.contains(text(i,"state"))) return;
        var held=reservation(tx,(UUID)a.get("attempt_id"));
        if(Boolean.TRUE.equals(budget(tx,text(held,"budget_id")).get("frozen"))) return;
        var o=operation(tx,(UUID)a.get("attempt_id")); if(o!=null&&o.get("admitted_sequence")!=null) return;
        settle(tx,a,"RELEASED"); change(tx,(UUID)i.get("intent_id"),next);
        if(o!=null) {
            update(tx.c,"UPDATE outbox SET done=true WHERE tenant_id=? AND run_id=? AND operation_id=?",tx.s.tenantId(),tx.s.runId(),o.get("operation_id"));
            update(tx.c,"UPDATE customer_actions SET state=?,version=version+1 WHERE tenant_id=? AND run_id=? AND operation_id=? AND state IN ('OPEN','VERIFIED')",next.equals("FAILED")?"EXPIRED":"CANCELLED",tx.s.tenantId(),tx.s.runId(),o.get("operation_id"));
        }
    }
    private void settle(Tx tx,Map<String,Object> a,String next) throws SQLException {
        var r=reservation(tx,(UUID)a.get("attempt_id")); if(!text(r,"state").equals("HELD")) return;
        long amount=number(r,"minor_units");
        update(tx.c,"UPDATE reservations SET state=? WHERE tenant_id=? AND run_id=? AND attempt_id=? AND state='HELD'",next,tx.s.tenantId(),tx.s.runId(),a.get("attempt_id"));
        update(tx.c,"UPDATE budgets SET held=held-?,consumed=consumed+? WHERE tenant_id=? AND run_id=? AND budget_id=?",amount,next.equals("CONSUMED")?amount:0,tx.s.tenantId(),tx.s.runId(),r.get("budget_id"));
    }
    private void freeze(Tx tx,Map<String,Object> a,UUID op,long amount,String reason) throws SQLException {
        var r=reservation(tx,(UUID)a.get("attempt_id"));
        update(tx.c,"UPDATE budgets SET frozen=true WHERE tenant_id=? AND run_id=? AND budget_id=?",tx.s.tenantId(),tx.s.runId(),r.get("budget_id"));
        update(tx.c,"INSERT INTO disputed_exposure VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",tx.s.tenantId(),tx.s.runId(),op,amount,reason);
    }
    private void conflict(Tx tx,Fact fact,UUID operation,String digest,String reason) throws SQLException {
        update(tx.c,"INSERT INTO conflict_evidence VALUES(?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",tx.s.tenantId(),tx.s.runId(),UUID.randomUUID(),operation,fact.eventId(),digest,encode(fact),reason);
    }
    private void acceptEvidence(Tx tx,UUID event) throws SQLException { update(tx.c,"UPDATE operation_evidence SET accepted=true WHERE tenant_id=? AND run_id=? AND event_id=?",tx.s.tenantId(),tx.s.runId(),event); }
    private ChargeOperation envelope(Tx tx,Map<String,Object> i,Map<String,Object> a,Map<String,Object> o) throws SQLException {
        var q=quote(tx,i); return new ChargeOperation(new OperationIdentity(new ExecutionScope(tx.s.tenantId(),tx.s.runId(),text(i,"merchant_id")),(UUID)i.get("intent_id"),(UUID)a.get("attempt_id"),(UUID)o.get("operation_id"),(UUID)o.get("adapter_key"),text(o,"request_digest")),new Money(text(q,"currency"),number(q,"minor_units")),text(i,"scenario_id"));
    }
    private Result command(Session s,String key,String route,String resource,Object request,Work<Result> body) {
        if(key==null||!key.matches("[\\x20-\\x7E]{16,128}")) fail(400,"INVALID_REQUEST"); String digest=PaymentRules.sha256(encode(request));
        return transaction(s,tx->{
            // Replay remains subject to current object access, not merely knowledge of an old key.
            if(route.contains("/intents/{id}")) intent(tx,UUID.fromString(resource));
            else if(route.contains("/actions/{id}")) actionRow(tx,UUID.fromString(resource));
            var old=one(tx.c,"SELECT * FROM commands WHERE tenant_id=? AND run_id=? AND principal_id=? AND route=? AND resource_id=? AND idempotency_key=?",s.tenantId(),s.runId(),s.principalId(),route,resource,key);
            if(old!=null) {
                if(!digest.equals(old.get("request_digest"))) fail(409,"IDEMPOTENCY_CONFLICT");
                return new Result((int)number(old,"response_status"),json.readValue(text(old,"response_body"),new TypeReference<Map<String,Object>>(){}),true);
            }
            Result result=body.run(tx);
            update(tx.c,"INSERT INTO commands VALUES(?,?,?,?,?,?,?,?,?)",s.tenantId(),s.runId(),s.principalId(),route,resource,key,digest,result.status(),encode(result.body())); return result;
        });
    }
    private <T> T transaction(Session s,Work<T> work) {
        for(int retry=0;retry<3;retry++) {
            try(var c=ds.getConnection()) {
                c.setAutoCommit(false);
                try {
                    var run=one(c,"SELECT * FROM simulation_runs WHERE tenant_id=? AND run_id=? FOR UPDATE",s.tenantId(),s.runId()); if(run==null) fail(404,"RESOURCE_NOT_FOUND");
                    owner(run);
                    T result=work.run(new Tx(c,s,instant(run,"virtual_time"))); c.commit(); return result;
                } catch(Exception e) { c.rollback(); throw e; }
            } catch(PaymentException e) { throw e; }
            catch(SQLException e) { if(e.getSQLState()!=null&&e.getSQLState().startsWith("40")&&retry<2) continue; if("23505".equals(e.getSQLState())) throw new PaymentException(409,"AUTHORITY_REPLAY"); throw new PaymentException(503,"TEMPORARILY_UNAVAILABLE","SAME_COMMAND"); }
            catch(CompositionException e) { throw compositionFailure(e); }
            catch(RuntimeException e) { throw e; }
            catch(Exception e) { throw new IllegalStateException("Synthetic transaction encoding failed",e); }
        }
        throw new PaymentException(503,"TEMPORARILY_UNAVAILABLE","SAME_COMMAND");
    }
    private <T> T read(Session s,Work<T> work) {
        try(var c=ds.getConnection()) {
            c.setAutoCommit(false); c.setReadOnly(true); c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            var run=one(c,"SELECT * FROM simulation_runs WHERE tenant_id=? AND run_id=?",s.tenantId(),s.runId()); if(run==null) fail(404,"RESOURCE_NOT_FOUND");
            T result=work.run(new Tx(c,s,instant(run,"virtual_time"))); c.commit(); return result;
        } catch(PaymentException e) { throw e; } catch(CompositionException e) { throw compositionFailure(e); } catch(SQLException e) { throw new PaymentException(503,"TEMPORARILY_UNAVAILABLE","SAME_COMMAND"); } catch(Exception e) { throw new IllegalStateException("Synthetic read failed",e); }
    }
    private Map<String,Object> intent(Tx tx,UUID id) throws SQLException { var i=intentUnchecked(tx,id); merchant(tx.s,text(i,"merchant_id")); return i; }
    private Map<String,Object> intentUnchecked(Tx tx,UUID id) throws SQLException { return required(one(tx.c,"SELECT * FROM payment_intents WHERE tenant_id=? AND run_id=? AND intent_id=?",tx.s.tenantId(),tx.s.runId(),id)); }
    private Map<String,Object> quote(Tx tx,Map<String,Object> i) throws SQLException { return required(one(tx.c,"SELECT * FROM checkout_snapshots WHERE tenant_id=? AND run_id=? AND intent_id=? AND revision=?",tx.s.tenantId(),tx.s.runId(),i.get("intent_id"),i.get("checkout_revision"))); }
    private Map<String,Object> attempt(Tx tx,UUID id) throws SQLException { return one(tx.c,"SELECT * FROM payment_attempts WHERE tenant_id=? AND run_id=? AND intent_id=?",tx.s.tenantId(),tx.s.runId(),id); }
    private Map<String,Object> attemptById(Tx tx,UUID id) throws SQLException { return required(one(tx.c,"SELECT * FROM payment_attempts WHERE tenant_id=? AND run_id=? AND attempt_id=?",tx.s.tenantId(),tx.s.runId(),id)); }
    private Map<String,Object> operation(Tx tx,UUID attempt) throws SQLException { return one(tx.c,"SELECT * FROM financial_operations WHERE tenant_id=? AND run_id=? AND attempt_id=?",tx.s.tenantId(),tx.s.runId(),attempt); }
    private Map<String,Object> operationById(Tx tx,UUID id) throws SQLException { return required(one(tx.c,"SELECT * FROM financial_operations WHERE tenant_id=? AND run_id=? AND operation_id=?",tx.s.tenantId(),tx.s.runId(),id)); }
    private Map<String,Object> reservation(Tx tx,UUID attempt) throws SQLException { return required(one(tx.c,"SELECT * FROM reservations WHERE tenant_id=? AND run_id=? AND attempt_id=?",tx.s.tenantId(),tx.s.runId(),attempt)); }
    private Map<String,Object> grant(Tx tx,String id) throws SQLException { return required(one(tx.c,"SELECT * FROM authority_grants WHERE tenant_id=? AND run_id=? AND grant_id=?",tx.s.tenantId(),tx.s.runId(),id)); }
    private Map<String,Object> budget(Tx tx,String id) throws SQLException { return required(one(tx.c,"SELECT * FROM budgets WHERE tenant_id=? AND run_id=? AND budget_id=?",tx.s.tenantId(),tx.s.runId(),id)); }
    private Map<String,Object> actionRow(Tx tx,UUID id) throws SQLException {
        var a=required(one(tx.c,"SELECT * FROM customer_actions WHERE tenant_id=? AND run_id=? AND action_id=?",tx.s.tenantId(),tx.s.runId(),id));
        if(!Objects.equals(a.get("subject_id"),tx.s.principalId())) fail(404,"RESOURCE_NOT_FOUND");
        var o=operationById(tx,(UUID)a.get("operation_id")); var attempt=attemptById(tx,(UUID)o.get("attempt_id")); intent(tx,(UUID)attempt.get("intent_id")); grantAllowed(tx.s,text(attempt,"grant_id")); return a;
    }
    private void authority(Tx tx,Map<String,Object> g,String subject,String merchant) {
        if(!Objects.equals(g.get("subject_id"),subject)||!Objects.equals(g.get("merchant_id"),merchant)) fail(422,"AUTHORITY_BINDING_INVALID");
        if(text(g,"state").equals("REVOKED")) fail(409,"AUTHORITY_REVOKED");
        if(tx.now.isBefore(instant(g,"valid_from"))||!tx.now.isBefore(instant(g,"expires_at"))) fail(410,"AUTHORITY_EXPIRED");
    }
    private static boolean nativeProfile(Map<String,Object> intent) { return NATIVE_PROFILE.equals(intent.get("profile")); }
    private Scope scope(Tx tx) { return new Scope(tx.s.tenantId(),tx.s.runId()); }
    private void nativeCapabilities() {
        if(composition==null) fail(422,"UNSUPPORTED_CAPABILITY");
        try { composition.requireSupported(); } catch(CompositionException e) { throw compositionFailure(e); }
    }
    private Provisioning provision(Tx tx) throws SQLException {
        var provision=composition.provisioner().provision(scope(tx),tx.now);
        for(var key:provision.enrollments()) {
            if(!key.scope().equals(scope(tx))||key.revoked()) fail(422,"AUTHORITY_BINDING_INVALID");
            var old=one(tx.c,"SELECT * FROM native_trust_keys WHERE tenant_id=? AND run_id=? AND issuer=? AND kid=? AND purpose=?",tx.s.tenantId(),tx.s.runId(),key.issuer(),key.kid(),key.purpose());
            byte[] publicKey;
            try { publicKey=Base64.getUrlDecoder().decode(key.publicKeySpki()); } catch(IllegalArgumentException e) { fail(422,"AUTHORITY_BINDING_INVALID"); return null; }
            if(old!=null) {
                if(!Arrays.equals(publicKey,(byte[])old.get("public_key"))||!key.validFrom().equals(instant(old,"valid_from"))||!key.validUntil().equals(instant(old,"expires_at"))||key.version()!=number(old,"version")||Boolean.TRUE.equals(old.get("revoked"))) fail(422,"AUTHORITY_BINDING_INVALID");
            } else update(tx.c,"INSERT INTO native_trust_keys VALUES(?,?,?,?,?,?,?,?,?,false)",tx.s.tenantId(),tx.s.runId(),key.issuer(),key.kid(),key.purpose(),publicKey,key.validFrom(),key.validUntil(),key.version());
        }
        return provision;
    }
    private KeyResolver keyResolver(Tx tx) {
        // Enrollment is payment authority data, read under the same run lock as revocation/admission.
        var enrolled=new ArrayList<TrustKey>();
        try {
            for(var key:rows(tx.c,"SELECT * FROM native_trust_keys WHERE tenant_id=? AND run_id=?",tx.s.tenantId(),tx.s.runId()))
                enrolled.add(new TrustKey(scope(tx),text(key,"issuer"),text(key,"kid"),text(key,"purpose"),
                    Base64.getUrlEncoder().withoutPadding().encodeToString((byte[])key.get("public_key")),
                    instant(key,"valid_from"),instant(key,"expires_at"),number(key,"version"),Boolean.TRUE.equals(key.get("revoked"))));
        } catch(SQLException e) { unavailable(); }
        var snapshot=new EnrollmentSnapshot(scope(tx),tx.now,enrolled);
        KeyResolver selected=composition.keyResolvers().bind(snapshot);
        if(selected==null) fail(422,"AUTHORITY_BINDING_INVALID");
        return (requestedScope,issuer,kid,purpose,now)->{
            if(!scope(tx).equals(requestedScope)||!tx.now.equals(now)) fail(422,"AUTHORITY_BINDING_INVALID");
            TrustKey authoritative=snapshot.keys().stream().filter(k->k.issuer().equals(issuer)&&k.kid().equals(kid)&&k.purpose().equals(purpose)).findFirst().orElse(null);
            if(authoritative==null) fail(422,"AUTHORITY_BINDING_INVALID");
            if(authoritative.revoked()) fail(409,"AUTHORITY_REVOKED");
            if(now.isBefore(authoritative.validFrom())||!now.isBefore(authoritative.validUntil())) fail(410,"AUTHORITY_EXPIRED");
            TrustKey resolved=selected.resolve(requestedScope,issuer,kid,purpose,now);
            // A replaceable resolver cannot enroll keys or relax the owner's current revocation policy.
            if(!authoritative.equals(resolved)) fail(422,"AUTHORITY_BINDING_INVALID");
            return resolved;
        };
    }
    /** Internal provisioning administration is ordered by exactly the admission run lock. */
    public void revokeTrustKey(Session session,String issuer,String kid,String purpose) {
        role(session,"runner:write");
        transaction(session,tx->{update(tx.c,"UPDATE native_trust_keys SET revoked=true,version=version+1 WHERE tenant_id=? AND run_id=? AND issuer=? AND kid=? AND purpose=? AND NOT revoked",session.tenantId(),session.runId(),issuer,kid,purpose);return null;});
    }
    private CheckoutFacts checkoutFacts(Tx tx,Map<String,Object> i,Map<String,Object> q) {
        return new CheckoutFacts(scope(tx),(UUID)i.get("intent_id"),text(i,"merchant_id"),(int)number(q,"revision"),text(q,"currency"),number(q,"minor_units"),text(q,"credential_reference"),instant(q,"expires_at"));
    }
    private FrozenCheckout nativeCheckout(Tx tx,Map<String,Object> i,Map<String,Object> q) throws Exception {
        var row=one(tx.c,"SELECT checkout_json FROM native_checkouts WHERE tenant_id=? AND run_id=? AND intent_id=? AND revision=?",tx.s.tenantId(),tx.s.runId(),i.get("intent_id"),q.get("revision"));
        if(row==null) fail(422,"AUTHORITY_BINDING_INVALID");
        var stored=json.readValue(text(row,"checkout_json"),StoredCheckout.class);
        return nativeCoordinator.reloadCheckout(scope(tx),stored,text(q,"digest"));
    }
    private void approveNative(Tx tx,Map<String,Object> i,Map<String,Object> q,Map<String,Object> g,UUID attempt) throws Exception {
        nativeCapabilities();
        var facts=checkoutFacts(tx,i,q); var checkout=nativeCheckout(tx,i,q);
        nativeCoordinator.validateCheckout(facts,checkout,keyResolver(tx),tx.now);
        var provision=provision(tx);
        Instant deadline=Collections.min(List.of(tx.now.plusSeconds(300),instant(g,"expires_at"),facts.expiresAt()));
        var context=new AuthorityContext(facts,checkout,tx.s.principalId(),text(g,"grant_id"),number(g,"version"),provision.tapSigner().issuer(),"merchant-demo","processor-demo",UUID.randomUUID().toString(),UUID.randomUUID().toString(),tx.now,deadline);
        var stored=nativeCoordinator.issueAndRetainAuthority(new IssuanceRequest(context,provision.tapSigner()));
        String digest=nativeCoordinator.authorityDigest(stored);
        update(tx.c,"INSERT INTO native_authorities VALUES(?,?,?,?,?,?,?,?,?,?,?)",tx.s.tenantId(),tx.s.runId(),attempt,i.get("intent_id"),q.get("revision"),tx.s.principalId(),g.get("grant_id"),g.get("version"),encode(stored),digest,deadline);
        for(String role:List.of("MERCHANT","CREDENTIAL_PROVIDER","PAYMENT_PROCESSOR")) {
            boolean merchant=role.equals("MERCHANT");
            update(tx.c,"INSERT INTO native_challenges VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",tx.s.tenantId(),tx.s.runId(),role,merchant?context.checkoutNonce():context.paymentNonce(),i.get("intent_id"),q.get("revision"),tx.s.principalId(),g.get("grant_id"),g.get("version"),context.issuer(),merchant?context.checkoutAudience():context.paymentAudience(),q.get("digest"),deadline,attempt);
        }
        // Reload exact staged bytes. Signing/issuer success never substitutes for independent verification.
        var reloaded=nativeCoordinator.reloadAuthority(scope(tx),stored);
        var verified=nativeCoordinator.verifyAuthority(context,reloaded,keyResolver(tx),tx.now);
        for(var role:verified.authorizations()) update(tx.c,"INSERT INTO native_replay_identities VALUES(?,?,?,?,?,?)",tx.s.tenantId(),role.role(),role.issuer(),role.jwtId(),tx.s.runId(),attempt);
        update(tx.c,"UPDATE native_authorities SET expires_at=? WHERE tenant_id=? AND run_id=? AND attempt_id=?",verified.expiresAt(),tx.s.tenantId(),tx.s.runId(),attempt);
    }
    private void verifyNative(Tx tx,Map<String,Object> i,Map<String,Object> a,Map<String,Object> g) throws Exception {
        nativeCapabilities();
        if(!eligible(tx,g,a,i)) fail(409,"AUTHORITY_REVOKED");
        var q=quote(tx,i);
        if(number(a,"checkout_revision")!=number(q,"revision")) fail(422,"AUTHORITY_BINDING_INVALID");
        var row=one(tx.c,"SELECT * FROM native_authorities WHERE tenant_id=? AND run_id=? AND attempt_id=?",tx.s.tenantId(),tx.s.runId(),a.get("attempt_id"));
        if(row==null||!Objects.equals(row.get("intent_id"),i.get("intent_id"))||number(row,"revision")!=number(q,"revision")||!Objects.equals(row.get("subject_id"),a.get("subject_id"))||!Objects.equals(row.get("grant_id"),a.get("grant_id"))||number(row,"grant_version")!=number(a,"grant_version")) fail(422,"AUTHORITY_BINDING_INVALID");
        if(!tx.now.isBefore(instant(row,"expires_at"))) fail(410,"AUTHORITY_EXPIRED");
        var stored=json.readValue(text(row,"authority_json"),StoredAuthority.class);
        var facts=checkoutFacts(tx,i,q); var checkout=nativeCheckout(tx,i,q);
        nativeCoordinator.validateCheckout(facts,checkout,keyResolver(tx),tx.now);
        var context=new AuthorityContext(facts,checkout,text(a,"subject_id"),text(a,"grant_id"),number(a,"grant_version"),stored.issuer(),stored.checkoutAudience(),stored.paymentAudience(),stored.checkoutNonce(),stored.paymentNonce(),Instant.parse(stored.issuedAt()),Instant.parse(stored.expiresAt()));
        var issued=nativeCoordinator.reloadAuthority(scope(tx),stored);
        nativeCoordinator.validateAuthorityDigest(stored,text(row,"authority_digest"));
        var verified=nativeCoordinator.verifyAuthority(context,issued,keyResolver(tx),tx.now);
        if(!verified.expiresAt().equals(instant(row,"expires_at"))) fail(422,"AUTHORITY_BINDING_INVALID");
        for(var role:verified.authorizations()) {
            var used=one(tx.c,"SELECT * FROM native_replay_identities WHERE tenant_id=? AND role=? AND issuer=? AND jti=?",tx.s.tenantId(),role.role(),role.issuer(),role.jwtId());
            boolean merchant=role.role().equals("MERCHANT");
            var challenge=one(tx.c,"SELECT * FROM native_challenges WHERE tenant_id=? AND run_id=? AND role=? AND nonce=?",tx.s.tenantId(),tx.s.runId(),role.role(),merchant?context.checkoutNonce():context.paymentNonce());
            if(used==null||!Objects.equals(used.get("run_id"),tx.s.runId())||!Objects.equals(used.get("attempt_id"),a.get("attempt_id"))||challenge==null||!Objects.equals(challenge.get("attempt_id"),a.get("attempt_id"))||!Objects.equals(challenge.get("intent_id"),i.get("intent_id"))||number(challenge,"revision")!=number(q,"revision")||!Objects.equals(challenge.get("subject_id"),a.get("subject_id"))||!Objects.equals(challenge.get("grant_id"),a.get("grant_id"))||number(challenge,"grant_version")!=number(a,"grant_version")||!Objects.equals(challenge.get("issuer"),context.issuer())||!Objects.equals(challenge.get("audience"),merchant?context.checkoutAudience():context.paymentAudience())||!Objects.equals(challenge.get("checkout_digest"),q.get("digest"))||!tx.now.isBefore(instant(challenge,"expires_at"))) fail(422,"AUTHORITY_BINDING_INVALID");
        }
    }
    private boolean nativeGate(Tx tx,Map<String,Object> i,Map<String,Object> a,Map<String,Object> g) throws Exception {
        try { verifyNative(tx,i,a,g); return true; }
        catch(CompositionException e) { var failure=compositionFailure(e); if(failure.status()>=500) throw failure; terminate(tx,i,a,failure.status()==410?"EXPIRED":"CANCELLED"); return false; }
        catch(PaymentException e) { if(e.status()>=500) throw e; terminate(tx,i,a,e.status()==410?"EXPIRED":"CANCELLED"); return false; }
    }
    private static PaymentException compositionFailure(CompositionException error) {
        return switch(error.failure()) {
            case UNSUPPORTED_CAPABILITY -> new PaymentException(422,"UNSUPPORTED_CAPABILITY");
            case EXPIRED_AUTHORITY -> new PaymentException(410,"AUTHORITY_EXPIRED");
            case SIGNING_UNAVAILABLE, CRYPTO_UNAVAILABLE, ARTIFACT_UNAVAILABLE -> new PaymentException(503,"TEMPORARILY_UNAVAILABLE","SAME_COMMAND");
            default -> new PaymentException(422,"AUTHORITY_BINDING_INVALID");
        };
    }
    private void change(Tx tx,UUID id,String next) throws SQLException { update(tx.c,"UPDATE payment_intents SET state=?,version=version+1 WHERE tenant_id=? AND run_id=? AND intent_id=?",next,tx.s.tenantId(),tx.s.runId(),id); audit(tx,id,next); }
    private void audit(Tx tx,UUID id,String state) throws SQLException {
        var seq=one(tx.c,"UPDATE simulation_runs SET step_sequence=step_sequence+1 WHERE tenant_id=? AND run_id=? RETURNING step_sequence",tx.s.tenantId(),tx.s.runId());
        update(tx.c,"INSERT INTO audit_events VALUES(?,?,?,?,?,?,?)",tx.s.tenantId(),tx.s.runId(),UUID.randomUUID(),id,state,tx.now,seq.get("step_sequence"));
    }
    private void work(Tx tx,UUID op,String kind) throws SQLException {
        if(one(tx.c,"SELECT enqueue_sequence FROM outbox WHERE tenant_id=? AND run_id=? AND operation_id=? AND kind=?",tx.s.tenantId(),tx.s.runId(),op,kind)!=null)return;
        var sequence=one(tx.c,"UPDATE simulation_runs SET next_work_sequence=next_work_sequence+1 WHERE tenant_id=? AND run_id=? RETURNING next_work_sequence",tx.s.tenantId(),tx.s.runId());
        update(tx.c,"INSERT INTO outbox(tenant_id,run_id,operation_id,kind,enqueue_sequence) VALUES(?,?,?,?,?)",tx.s.tenantId(),tx.s.runId(),op,kind,sequence.get("next_work_sequence"));
    }
    private void done(Tx tx,UUID op,String kind) throws SQLException { update(tx.c,"UPDATE outbox SET done=true WHERE tenant_id=? AND run_id=? AND operation_id=? AND kind=?",tx.s.tenantId(),tx.s.runId(),op,kind); }
    private boolean fence(Tx tx,UUID op,String kind,long fence) throws SQLException { var w=one(tx.c,"SELECT * FROM outbox WHERE tenant_id=? AND run_id=? AND operation_id=? AND kind=?",tx.s.tenantId(),tx.s.runId(),op,kind); return w!=null&&!Boolean.TRUE.equals(w.get("done"))&&number(w,"fence")==fence; }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException("Invalid synthetic request"); } }
    private static Map<String,Object> required(Map<String,Object> r) { if(r==null) fail(404,"RESOURCE_NOT_FOUND"); return r; }
    private static void role(Session s,String role) { if(s==null) fail(401,"UNAUTHENTICATED"); if(!s.roles().contains(role)) fail(403,"FORBIDDEN"); }
    private static void merchant(Session s,String merchant) { if(!s.allowedMerchants().contains(merchant)||!"merchant-demo".equals(merchant)) fail(404,"RESOURCE_NOT_FOUND"); }
    private static void grantAllowed(Session s,String grant) { if(!s.allowedGrants().contains(grant)) fail(404,"RESOURCE_NOT_FOUND"); }
    private static void validate(int version) { if(version<1) fail(400,"INVALID_REQUEST"); }
    private static void version(Map<String,Object> row,int expected) { if(number(row,"version")!=expected) fail(409,"VERSION_CONFLICT"); }
    private static void state(Map<String,Object> i,String... allowed) { if(!List.of(allowed).contains(i.get("state"))) fail(409,"INVALID_STATE"); }
    private static Map<String,Object> money(String currency,long minor) { return map("currency",currency,"minorUnits",Long.toString(minor)); }
    private static Result result(int status,Map<String,Object> body) { var out=map("contractVersion",PaymentRules.CONTRACT,"correlationId",UUID.randomUUID().toString()); out.putAll(body); return new Result(status,out,false); }
    private static void fail(int status,String code) { throw new PaymentException(status,code); }
    private static void unavailable() { throw new PaymentException(503,"TEMPORARILY_UNAVAILABLE","SAME_COMMAND"); }
    private void capabilities() {
        if(!CapabilityOffer.localFake().equals(adapter.capabilities())) fail(422,"UNSUPPORTED_CAPABILITY");
    }
    private void owner(Map<String,Object> run) { if("REPLAY".equals(run.get("execution_mode"))&&!Objects.equals(run.get("replay_owner"),replayOwner))fail(403,"FORBIDDEN"); }
    private void activity(Tx tx,long[] values) throws SQLException {
        values[0]=number(one(tx.c,"SELECT COALESCE(max(step_sequence),0) AS value FROM audit_events WHERE tenant_id=? AND run_id=?",tx.s.tenantId(),tx.s.runId()),"value");
        values[1]=number(one(tx.c,"SELECT count(*) AS value FROM outbox WHERE tenant_id=? AND run_id=? AND done",tx.s.tenantId(),tx.s.runId()),"value");
        values[2]=number(one(tx.c,"SELECT count(*) AS value FROM operation_evidence WHERE tenant_id=? AND run_id=?",tx.s.tenantId(),tx.s.runId()),"value");
        values[3]=number(one(tx.c,"SELECT (SELECT count(*) FROM conflict_evidence WHERE tenant_id=? AND run_id=?)+(SELECT count(*) FROM disputed_exposure WHERE tenant_id=? AND run_id=?) AS value",tx.s.tenantId(),tx.s.runId(),tx.s.tenantId(),tx.s.runId()),"value");
    }
    private WorkerReceipt receipt(Session s,long[] before,Object[] selected) {
        return transaction(s,tx->{long[] after=new long[4];activity(tx,after);boolean progress=!Arrays.equals(before,after);
            var extent=one(tx.c,"SELECT COALESCE(min(step_sequence),0) AS first,COALESCE(max(step_sequence),0) AS last FROM audit_events WHERE tenant_id=? AND run_id=? AND step_sequence>?",s.tenantId(),s.runId(),before[0]);
            return new WorkerReceipt((UUID)selected[0],(String)selected[1],selected[2]==null?0:(long)selected[2],number(extent,"first"),number(extent,"last"),progress);});
    }
}
