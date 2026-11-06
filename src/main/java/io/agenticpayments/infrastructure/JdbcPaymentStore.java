package io.agenticpayments.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import io.agenticpayments.application.PaymentException;
import io.agenticpayments.application.PaymentStore;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.domain.PaymentRules;
import io.agenticpayments.domain.OperationBinding;
import io.agenticpayments.sdk.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static io.agenticpayments.infrastructure.Sql.*;

/** Run-serialized normalized transaction repository and local application unit of work. */
public final class JdbcPaymentStore implements PaymentStore {
    private final DataSource ds;
    private final PaymentAdapter adapter;
    private final ObjectMapper json=new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true).configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY,true);
    private record Tx(Connection c,Session s,Instant now) {}
    @FunctionalInterface private interface Work<T> { T run(Tx tx) throws Exception; }
    private record Dispatch(ChargeOperation operation,String kind,long fence) {}
    public JdbcPaymentStore(DataSource ds,PaymentAdapter adapter) { this.ds=Objects.requireNonNull(ds); this.adapter=Objects.requireNonNull(adapter); }

    public void seedRun(Session s,Instant clock,long budget) {
        role(s,"runner:write"); if(clock==null||budget<1250) fail(400,"INVALID_REQUEST");
        try(var c=ds.getConnection()) {
            c.setAutoCommit(false);
            update(c,"INSERT INTO simulation_runs(tenant_id,run_id,virtual_time) VALUES(?,?,?) ON CONFLICT DO NOTHING",s.tenantId(),s.runId(),clock);
            one(c,"SELECT * FROM simulation_runs WHERE tenant_id=? AND run_id=? FOR UPDATE",s.tenantId(),s.runId());
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
        if(cmd==null||cmd.requiredCapabilities()==null||!PaymentRules.PROFILE.equals(cmd.profile())) fail(422,"UNSUPPORTED_PROFILE");
        merchant(s,cmd.merchantId());
        if(!PaymentRules.SCENARIOS.contains(cmd.scenarioId())) fail(400,"INVALID_REQUEST");
        if(cmd.requiredCapabilities().size()>16||new HashSet<>(cmd.requiredCapabilities()).size()!=cmd.requiredCapabilities().size()||!PaymentRules.CAPABILITIES.containsAll(cmd.requiredCapabilities())) fail(422,"UNSUPPORTED_CAPABILITY");
        return command(s,key,"POST /intents","new",cmd,tx->{
            UUID id=UUID.randomUUID(); update(tx.c,"INSERT INTO payment_intents(tenant_id,run_id,intent_id,merchant_id,scenario_id,state) VALUES(?,?,?,?,?,'DRAFT')",s.tenantId(),s.runId(),id,cmd.merchantId(),cmd.scenarioId());
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
            update(tx.c,"INSERT INTO checkout_snapshots VALUES(?,?,?,?,?,?,?,?,?,?)",s.tenantId(),s.runId(),id,revision,digest,bytes,currency,1250L,t.credentialReference(),expiry);
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
            update(tx.c,"INSERT INTO reservations VALUES(?,?,?,?,?,'HELD')",s.tenantId(),s.runId(),attempt,g.get("budget_id"),total);
            update(tx.c,"UPDATE budgets SET held=held+? WHERE tenant_id=? AND run_id=? AND budget_id=?",total,s.tenantId(),s.runId(),g.get("budget_id"));
            change(tx,id,"APPROVED");
            return result(200,map("intentId",id,"state","APPROVED","version",cmd.expectedVersion()+1,"attemptId",attempt,"executionOwner","simulator-core","authorityProfile","local-fake","authorityVersion",number(g,"version"),"reserved",money(text(q,"currency"),total)));
        });
    }
    public Result execute(Session s,UUID id,String key,ExecuteCommand cmd) {
        role(s,"execution:write"); validate(cmd==null?0:cmd.expectedVersion());
        capabilities();
        return command(s,key,"POST /intents/{id}/execute",id.toString(),cmd,tx->{
            var i=intent(tx,id); version(i,cmd.expectedVersion()); state(i,"APPROVED"); var a=attempt(tx,id); var q=quote(tx,i); var g=grant(tx,text(a,"grant_id"));
            authority(tx,g,text(a,"subject_id"),text(i,"merchant_id"));
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
            return result(200,map("intentId",id,"state",i.get("state"),"version",number(i,"version"),"profile",PaymentRules.PROFILE,"runId",s.runId(),"attemptId",a==null?null:a.get("attempt_id"),"operationId",o==null?null:o.get("operation_id"),"money",q==null?null:money(text(q,"currency"),number(q,"minor_units")),"exposureState",r==null?"NONE":r.get("state"),"reconciliationRequired",frozen||Set.of("ADMITTED","PENDING","UNKNOWN").contains(i.get("state")),"actionId",ac==null?null:ac.get("action_id"),"outcomeEvidenceId",ev==null?null:ev.get("event_id")));
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
            if(text(g,"state").equals("ACTIVE")) update(tx.c,"UPDATE authority_grants SET state='REVOKED',version=version+1 WHERE tenant_id=? AND run_id=? AND grant_id=?",s.tenantId(),s.runId(),authorityId);
            var admitted=new ArrayList<String>();
            for(var a:rows(tx.c,"SELECT * FROM payment_attempts WHERE tenant_id=? AND run_id=? AND grant_id=? ORDER BY intent_id",s.tenantId(),s.runId(),authorityId)) {
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
            update(tx.c,"UPDATE simulation_runs SET virtual_time=?,step_sequence=step_sequence+1 WHERE tenant_id=? AND run_id=?",clock,s.tenantId(),s.runId()); return null;
        });
    }
    public boolean runNext(Session s) {
        role(s,"runner:write");
        capabilities();
        Dispatch d=transaction(s,tx->{
            expire(tx);
            var pending=one(tx.c,"SELECT w.*,a.intent_id FROM outbox w JOIN financial_operations o USING(tenant_id,run_id,operation_id) JOIN payment_attempts a USING(tenant_id,run_id,attempt_id) WHERE w.tenant_id=? AND w.run_id=? AND NOT w.done ORDER BY CASE w.kind WHEN 'PREPARE' THEN 0 WHEN 'DISPATCH' THEN 1 ELSE 2 END,w.operation_id LIMIT 1",s.tenantId(),s.runId());
            if(pending==null) return null;
            UUID op=(UUID)pending.get("operation_id"), id=(UUID)pending.get("intent_id"); var i=intentUnchecked(tx,id); var a=attempt(tx,id); String kind=text(pending,"kind");
            if(PaymentRules.TERMINAL.contains(text(i,"state"))) { done(tx,op,kind); return null; }
            long fence=number(pending,"fence")+1;
            update(tx.c,"UPDATE outbox SET fence=? WHERE tenant_id=? AND run_id=? AND operation_id=? AND kind=?",fence,s.tenantId(),s.runId(),op,kind);
            if(kind.equals("DISPATCH")) {
                if(!text(i,"state").equals("READY")) { done(tx,op,kind); return null; }
                var g=grant(tx,text(a,"grant_id")); var r=reservation(tx,(UUID)a.get("attempt_id")); var b=budget(tx,text(r,"budget_id"));
                if(Boolean.TRUE.equals(b.get("frozen"))) { done(tx,op,kind); return null; }
                if(!eligible(tx,g,a,i)) { terminate(tx,i,a,text(g,"state").equals("REVOKED")?"CANCELLED":"EXPIRED"); return null; }
                var ac=one(tx.c,"SELECT * FROM customer_actions WHERE tenant_id=? AND run_id=? AND operation_id=?",s.tenantId(),s.runId(),op);
                boolean needsAction=text(i,"scenario_id").equals("challenge-then-success");
                var q=quote(tx,i);
                if(needsAction&&(ac==null||!text(ac,"state").equals("VERIFIED")||!tx.now.isBefore(instant(ac,"expires_at"))||!Objects.equals(ac.get("subject_id"),a.get("subject_id"))||!Objects.equals(ac.get("checkout_digest"),q.get("digest")))) { terminate(tx,i,a,"FAILED"); return null; }
                if(!text(r,"state").equals("HELD")) throw new IllegalStateException("Missing execution exposure");
                var seq=one(tx.c,"UPDATE simulation_runs SET admission_sequence=admission_sequence+1 WHERE tenant_id=? AND run_id=? RETURNING admission_sequence",s.tenantId(),s.runId());
                update(tx.c,"UPDATE financial_operations SET admitted_sequence=? WHERE tenant_id=? AND run_id=? AND operation_id=?",seq.get("admission_sequence"),s.tenantId(),s.runId(),op);
                change(tx,id,"ADMITTED"); done(tx,op,"DISPATCH"); work(tx,op,"RECONCILE");
            }
            return new Dispatch(envelope(tx,i,a,operationById(tx,op)),kind,fence);
        });
        if(d==null) return false;
        if(d.kind.equals("PREPARE")) {
            Preparation preparation=adapter.prepare(d.operation);
            transaction(s,tx->{
                var id=d.operation.identity(); var i=intentUnchecked(tx,id.intentId()); var a=attempt(tx,id.intentId());
                if(!text(i,"state").equals("PREPARING")||!fence(tx,id.operationId(),"PREPARE",d.fence)) return null;
                var g=grant(tx,text(a,"grant_id"));
                if(!eligible(tx,g,a,i)) { terminate(tx,i,a,text(g,"state").equals("REVOKED")?"CANCELLED":"EXPIRED"); return null; }
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
        return true;
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
    private void expire(Tx tx) throws SQLException {
        for(var i:rows(tx.c,"SELECT * FROM payment_intents WHERE tenant_id=? AND run_id=? AND state IN ('QUOTED','APPROVED','PREPARING','WAITING_ACTION','READY') ORDER BY intent_id",tx.s.tenantId(),tx.s.runId())) {
            var a=attempt(tx,(UUID)i.get("intent_id"));
            if(a==null) { if(!tx.now.isBefore(instant(quote(tx,i),"expires_at"))) change(tx,(UUID)i.get("intent_id"),"EXPIRED"); continue; }
            var g=grant(tx,text(a,"grant_id"));
            if(!eligible(tx,g,a,i)) { terminate(tx,i,a,text(g,"state").equals("REVOKED")?"CANCELLED":"EXPIRED"); continue; }
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
                    T result=work.run(new Tx(c,s,instant(run,"virtual_time"))); c.commit(); return result;
                } catch(Exception e) { c.rollback(); throw e; }
            } catch(PaymentException e) { throw e; }
            catch(SQLException e) { if(e.getSQLState()!=null&&e.getSQLState().startsWith("40")&&retry<2) continue; throw new PaymentException(503,"TEMPORARILY_UNAVAILABLE","SAME_COMMAND"); }
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
        } catch(PaymentException e) { throw e; } catch(SQLException e) { throw new PaymentException(503,"TEMPORARILY_UNAVAILABLE","SAME_COMMAND"); } catch(Exception e) { throw new IllegalStateException("Synthetic read failed",e); }
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
    private void change(Tx tx,UUID id,String next) throws SQLException { update(tx.c,"UPDATE payment_intents SET state=?,version=version+1 WHERE tenant_id=? AND run_id=? AND intent_id=?",next,tx.s.tenantId(),tx.s.runId(),id); audit(tx,id,next); }
    private void audit(Tx tx,UUID id,String state) throws SQLException {
        var seq=one(tx.c,"UPDATE simulation_runs SET step_sequence=step_sequence+1 WHERE tenant_id=? AND run_id=? RETURNING step_sequence",tx.s.tenantId(),tx.s.runId());
        update(tx.c,"INSERT INTO audit_events VALUES(?,?,?,?,?,?,?)",tx.s.tenantId(),tx.s.runId(),UUID.randomUUID(),id,state,tx.now,seq.get("step_sequence"));
    }
    private void work(Tx tx,UUID op,String kind) throws SQLException { update(tx.c,"INSERT INTO outbox(tenant_id,run_id,operation_id,kind) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",tx.s.tenantId(),tx.s.runId(),op,kind); }
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
}
