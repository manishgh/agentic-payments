package io.agenticpayments.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agenticpayments.sdk.*;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import static io.agenticpayments.infrastructure.Sql.*;

/** Adapter-owned journal: every call uses a separate connection and never locks core rows. */
public final class JdbcSimulatorAdapter implements PaymentAdapter {
    private final DataSource ds;
    private final ObjectMapper json=new ObjectMapper();
    public JdbcSimulatorAdapter(DataSource ds) { this.ds=Objects.requireNonNull(ds); }
    @Override public CapabilityOffer capabilities() { return CapabilityOffer.localFake(); }
    @Override public Preparation prepare(ChargeOperation op) {
        return new Preparation(op.scenarioId().equals("challenge-then-success")?ActionType.SYNTHETIC_CHALLENGE:ActionType.NONE);
    }
    @Override public Fact execute(ChargeOperation op) {
        Fact fact=mutate(op,false);
        if(op.scenarioId().equals("response-loss-after-acceptance")) throw new AdapterUnavailableException();
        return fact;
    }
    @Override public LookupResult lookup(OperationIdentity identity) {
        try(var c=ds.getConnection()) {
            var r=one(c,"SELECT * FROM simulator_operation_journal WHERE tenant_id=? AND run_id=? AND adapter_key=?",identity.scope().tenantId(),identity.scope().runId(),identity.adapterKey());
            if(r==null) return new LookupResult(LookupResult.Status.NOT_FOUND,Optional.empty());
            verify(r,identity);
            return new LookupResult(LookupResult.Status.FOUND,Optional.of(fact(r)));
        } catch(SQLException e) { throw new AdapterUnavailableException(); }
    }
    @Override public Fact sealNoEffect(ChargeOperation op) { return mutate(op,true); }
    private Fact mutate(ChargeOperation op,boolean seal) {
        var id=op.identity();
        try(var c=ds.getConnection()) {
            c.setAutoCommit(false);
            String outcome=seal||op.scenarioId().equals("definitive-no-effect")?"NO_EFFECT":op.scenarioId().equals("delayed-success")?"PENDING":"SUCCEEDED";
            UUID event=UUID.randomUUID(); String identity=json.writeValueAsString(id);
            int inserted=update(c,"INSERT INTO simulator_operation_journal(tenant_id,run_id,adapter_key,request_digest,identity_json,currency,minor_units,scenario_id,outcome,sequence,event_id) VALUES(?,?,?,?,?,?,?,?,?,1,?) ON CONFLICT DO NOTHING",id.scope().tenantId(),id.scope().runId(),id.adapterKey(),id.requestDigest(),identity,op.money().currency(),op.money().minorUnits(),op.scenarioId(),outcome,event);
            var r=one(c,"SELECT * FROM simulator_operation_journal WHERE tenant_id=? AND run_id=? AND adapter_key=? FOR UPDATE",id.scope().tenantId(),id.scope().runId(),id.adapterKey());
            verify(r,id);
            if(!Objects.equals(text(r,"currency"),op.money().currency())||number(r,"minor_units")!=op.money().minorUnits()||!Objects.equals(text(r,"scenario_id"),op.scenarioId())) throw new IllegalArgumentException("Operation binding mismatch");
            if(inserted==1) append(c,r);
            c.commit(); return fact(r);
        } catch(SQLException e) { throw new AdapterUnavailableException(); }
        catch(java.io.IOException e) { throw new IllegalStateException("Synthetic journal encoding failed"); }
    }
    /** Explicit runner-only completion, not an HTTP callback or assertion. */
    public Fact finishPending(OperationIdentity id) {
        try(var c=ds.getConnection()) {
            c.setAutoCommit(false);
            var r=one(c,"SELECT * FROM simulator_operation_journal WHERE tenant_id=? AND run_id=? AND adapter_key=? FOR UPDATE",id.scope().tenantId(),id.scope().runId(),id.adapterKey());
            if(r==null) throw new IllegalArgumentException("No accepted operation"); verify(r,id);
            if(text(r,"outcome").equals("PENDING")) {
                update(c,"UPDATE simulator_operation_journal SET outcome='SUCCEEDED',sequence=sequence+1,event_id=? WHERE tenant_id=? AND run_id=? AND adapter_key=?",UUID.randomUUID(),id.scope().tenantId(),id.scope().runId(),id.adapterKey());
                r=one(c,"SELECT * FROM simulator_operation_journal WHERE tenant_id=? AND run_id=? AND adapter_key=?",id.scope().tenantId(),id.scope().runId(),id.adapterKey()); append(c,r);
            }
            c.commit(); return fact(r);
        } catch(SQLException e) { throw new AdapterUnavailableException(); }
    }
    /** Only journal-backed event handles can reach the core verifier. */
    public Fact verifyEvent(OperationIdentity expected,UUID eventId) {
        try(var c=ds.getConnection()) {
            var r=one(c,"SELECT * FROM simulator_journal_events WHERE tenant_id=? AND run_id=? AND event_id=?",expected.scope().tenantId(),expected.scope().runId(),eventId);
            if(r==null) throw new IllegalArgumentException("Unverified event"); verify(r,expected); return fact(r);
        } catch(SQLException e) { throw new AdapterUnavailableException(); }
    }
    private void verify(Map<String,Object> r,OperationIdentity id) {
        try { if(!Objects.equals(text(r,"request_digest"),id.requestDigest())||!json.readValue(text(r,"identity_json"),OperationIdentity.class).equals(id)) throw new IllegalArgumentException("Operation binding mismatch"); }
        catch(java.io.IOException e) { throw new IllegalStateException("Invalid stored journal identity"); }
    }
    private Fact fact(Map<String,Object> r) {
        try { return new Fact(uuid(r,"event_id"),json.readValue(text(r,"identity_json"),OperationIdentity.class),new Money(text(r,"currency"),number(r,"minor_units")),number(r,"sequence"),Outcome.valueOf(text(r,"outcome"))); }
        catch(java.io.IOException e) { throw new IllegalStateException("Invalid stored journal identity"); }
    }
    private void append(Connection c,Map<String,Object> r) throws SQLException {
        update(c,"INSERT INTO simulator_journal_events(tenant_id,run_id,event_id,adapter_key,request_digest,identity_json,currency,minor_units,outcome,sequence) VALUES(?,?,?,?,?,?,?,?,?,?)",r.get("tenant_id"),r.get("run_id"),r.get("event_id"),r.get("adapter_key"),r.get("request_digest"),r.get("identity_json"),r.get("currency"),r.get("minor_units"),r.get("outcome"),r.get("sequence"));
    }
}
