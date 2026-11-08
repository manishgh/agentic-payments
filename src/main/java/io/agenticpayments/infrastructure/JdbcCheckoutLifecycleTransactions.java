package io.agenticpayments.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agenticpayments.application.*;
import io.agenticpayments.application.CheckoutConfigurationResolver.ConfigurationIdentity;
import io.agenticpayments.application.CheckoutLifecycle.*;
import io.agenticpayments.application.CheckoutLifecycleTransactions.*;
import io.agenticpayments.checkout.CheckoutCommandTransport.Prepared;
import io.agenticpayments.checkout.CheckoutSessions.*;
import io.agenticpayments.domain.PaymentRules;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import static io.agenticpayments.infrastructure.Sql.*;

/** Scoped PostgreSQL adapter. Claim commits before the application invokes its transport. */
public final class JdbcCheckoutLifecycleTransactions implements CheckoutLifecycleTransactions {
    private final DataSource database;
    private final ObjectMapper json = new ObjectMapper();
    public JdbcCheckoutLifecycleTransactions(DataSource database) { this.database = Objects.requireNonNull(database); }
    @Override public <T> T run(PaymentService.Session s, Work<T> work) {
        Objects.requireNonNull(s); Objects.requireNonNull(work);
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (one(c, "SELECT run_id FROM simulation_runs WHERE tenant_id=? AND run_id=? FOR UPDATE", s.tenantId(), s.runId()) == null)
                    throw new PaymentException(404, "NOT_FOUND");
                var tx = new Unit(c, s);
                try { T result = work.run(tx); c.commit(); return result; }
                finally { tx.active = false; }
            } catch (RuntimeException | SQLException failure) { c.rollback(); throw failure; }
        } catch (PaymentException failure) { throw failure; }
        catch (SQLException failure) { throw new PaymentException(503, "CHECKOUT_STORE_UNAVAILABLE"); }
    }
    @Override public void enroll(PaymentService.Session s, ConfigurationIdentity identity) {
        require(identity.binding().tenantId().equals(s.tenantId()) && identity.binding().principalId().equals(s.principalId()),
                422, "CHECKOUT_BINDING_CONFLICT");
        run(s, raw -> {
            Unit tx = (Unit)raw; var b = identity.binding();
            Map<String,Object> existing = tx.row("SELECT metadata_json FROM checkout_configurations WHERE tenant_id=? AND run_id=? AND principal_id=? AND merchant_id=? AND configuration_id=? AND configuration_version=?",
                    b.merchantId(), b.configurationId(), identity.configurationVersion());
            if (existing != null) require(identity.equals(decode(text(existing,"metadata_json"), ConfigurationIdentity.class)), 409, "CONFIGURATION_IDENTITY_CONFLICT");
            else tx.write("INSERT INTO checkout_configurations VALUES(?,?,?,?,?,?,?)", b.merchantId(), b.configurationId(), identity.configurationVersion(), encode(identity));
            tx.write("INSERT INTO checkout_current_configurations VALUES(?,?,?,?,?,?) ON CONFLICT(tenant_id,run_id,principal_id,merchant_id,configuration_id) DO UPDATE SET configuration_version=EXCLUDED.configuration_version",
                    b.merchantId(), b.configurationId(), identity.configurationVersion());
            return null;
        });
    }
    private record Payload(StageCommand input, ConfigurationIdentity configuration, Prepared prepared, int stagedRevision) {
        @Override public String toString() { return "Payload[protected]"; }
    }
    private final class Unit implements Transaction {
        private final Connection c; private final PaymentService.Session s;
        private final Thread thread = Thread.currentThread(); private boolean active = true;
        Unit(Connection c, PaymentService.Session s) { this.c=c; this.s=s; }
        private void active() { if (!active || thread != Thread.currentThread()) throw new IllegalStateException("Checkout transaction is no longer active"); }
        private Object[] scoped(Object... tail) {
            Object[] values = new Object[tail.length+3]; values[0]=s.tenantId(); values[1]=s.runId(); values[2]=s.principalId();
            System.arraycopy(tail,0,values,3,tail.length); return values;
        }
        Map<String,Object> row(String sql, Object... tail) {
            active(); try { return one(c,sql,scoped(tail)); }
            catch (SQLException error) { throw new PaymentException(503,"CHECKOUT_STORE_UNAVAILABLE"); }
        }
        void write(String sql, Object... tail) {
            active(); try { update(c,sql,scoped(tail)); }
            catch (SQLException error) { throw new PaymentException(503,"CHECKOUT_STORE_UNAVAILABLE"); }
        }
        @Override public Purchase purchase(UUID id) {
            var row=row("SELECT purchase_json FROM checkout_purchases WHERE tenant_id=? AND run_id=? AND principal_id=? AND purchase_id=?",id);
            return row==null?null:decode(text(row,"purchase_json"),Purchase.class);
        }
        @Override public StoredCommand replay(UUID purchaseId,String key) {
            return command(row("SELECT * FROM checkout_lifecycle_commands WHERE tenant_id=? AND run_id=? AND principal_id=? AND purchase_id=? AND local_key=?",purchaseId,key));
        }
        @Override public StoredCommand command(UUID purchaseId,UUID id) {
            return command(row("SELECT * FROM checkout_lifecycle_commands WHERE tenant_id=? AND run_id=? AND principal_id=? AND purchase_id=? AND command_id=?",purchaseId,id));
        }
        private StoredCommand command(Map<String,Object> row) {
            if(row==null)return null;
            Payload p=decode(text(row,"immutable_json"),Payload.class);
            return new StoredCommand(uuid(row,"command_id"),uuid(row,"purchase_id"),text(row,"local_key"),text(row,"fingerprint"),
                    p.input(),p.configuration(),p.prepared(),p.stagedRevision(),CommandState.valueOf(text(row,"state")),uuid(row,"claim_token"),
                    text(row,"outcome_code"),Observation.valueOf(text(row,"observation")),(int)number(row,"http_status"),uuid(row,"response_reference"));
        }
        @Override public ConfigurationIdentity currentConfiguration(String merchant,String configuration) {
            var row=row("SELECT e.metadata_json FROM checkout_current_configurations p JOIN checkout_configurations e USING(tenant_id,run_id,principal_id,merchant_id,configuration_id,configuration_version) WHERE p.tenant_id=? AND p.run_id=? AND p.principal_id=? AND p.merchant_id=? AND p.configuration_id=?",merchant,configuration);
            return row==null?null:decode(text(row,"metadata_json"),ConfigurationIdentity.class);
        }
        @Override public StoredCommand stage(UUID purchaseId,String key,String fingerprint,StageCommand input,ConfigurationIdentity identity,Prepared prepared) {
            Purchase p=purchase(purchaseId);
            require(replay(purchaseId,key)==null,409,"IDEMPOTENCY_CONFLICT");
            require(identity.equals(currentConfiguration(input.merchantId(),input.configurationId())),409,"CONFIGURATION_CHANGED");
            require(identity.binding().tenantId().equals(s.tenantId()) && identity.binding().principalId().equals(s.principalId())
                    && identity.binding().equals(prepared.request().binding()) && input.operation()==prepared.request().operation()
                    && input.currency().equals(prepared.request().currency()) && identity.transportProfile().equals(prepared.profile())
                    && identity.encodingVersion().equals(prepared.encodingVersion())
                    && input.itemIds().equals(prepared.request().items().stream().map(ItemReference::id).toList()),422,"CHECKOUT_BINDING_CONFLICT");
            if(p==null) require(input.operation()==Operation.CREATE && input.expectedRevision()==0 && prepared.request().sessionId()==null,409,"CHECKOUT_REVISION_CONFLICT");
            else {
                require(p.revision()==input.expectedRevision() && identity.equals(p.configuration()) && input.currency().equals(p.currency())
                        && Objects.equals(p.sessionId(),prepared.request().sessionId()),409,"CHECKOUT_REVISION_CONFLICT");
                require(input.operation()!=Operation.CREATE && p.sessionId()!=null,409,"CHECKOUT_ALREADY_EXISTS");
                require(!p.mutationBlocked() || input.operation()==Operation.RETRIEVE,409,"CHECKOUT_MUTATION_BLOCKED");
                StoredCommand prior=command(purchaseId,p.currentCommandId());
                require(prior.state()!=CommandState.STAGED && prior.state()!=CommandState.CLAIMED,409,"CHECKOUT_COMMAND_IN_FLIGHT");
            }
            UUID id=UUID.randomUUID(); int revision=p==null?1:increment(p.revision());
            var next=new Purchase(purchaseId,identity,input.currency(),p==null?null:p.sessionId(),revision,
                    p==null?null:p.remoteStatus(),p==null?null:p.documentDigest(),id,p!=null && p.mutationBlocked());
            if(p==null) write("INSERT INTO checkout_purchases VALUES(?,?,?,?,?,?,?,?)",purchaseId,input.merchantId(),input.configurationId(),identity.configurationVersion(),encode(next));
            else save(next);
            write("INSERT INTO checkout_lifecycle_commands VALUES(?,?,?,?,?,?,?,?,'STAGED',NULL,'NONE','NOT_SENT',0,NULL)",
                    purchaseId,id,key,fingerprint,encode(new Payload(input,identity,prepared,revision)));
            return command(purchaseId,id);
        }
        @Override public Claim claim(UUID purchaseId,UUID commandId,UUID token) {
            StoredCommand cmd=command(purchaseId,commandId); require(cmd!=null,404,"NOT_FOUND");
            if(cmd.state()!=CommandState.STAGED)return null;
            Purchase p=purchase(purchaseId);
            require(p.currentCommandId().equals(commandId) && p.revision()==cmd.stagedRevision(),409,"CHECKOUT_REVISION_CONFLICT");
            require(cmd.configuration().equals(currentConfiguration(cmd.input().merchantId(),cmd.input().configurationId())),409,"CONFIGURATION_CHANGED");
            require(!p.mutationBlocked() || cmd.input().operation()==Operation.RETRIEVE,409,"CHECKOUT_MUTATION_BLOCKED");
            direct("UPDATE checkout_lifecycle_commands SET state='CLAIMED',claim_token=?,observation='UNKNOWN' WHERE tenant_id=? AND run_id=? AND principal_id=? AND purchase_id=? AND command_id=?",
                    reordered(token,purchaseId,commandId));
            return new Claim(command(purchaseId,commandId),token);
        }
        /** SQL helpers usually scope first; UPDATE SET parameters precede scope here. */
        private Object[] reordered(Object first,UUID purchaseId,UUID commandId) {
            return new Object[]{first,s.tenantId(),s.runId(),s.principalId(),purchaseId,commandId};
        }
        private void direct(String sql,Object... values) {
            active(); try { update(c,sql,values); } catch(SQLException error) { throw new PaymentException(503,"CHECKOUT_STORE_UNAVAILABLE"); }
        }
        @Override public StoredCommand record(Claim claim,Result result,boolean resolverCurrent) {
            StoredCommand original=claim.command(); StoredCommand cmd=command(original.purchaseId(),original.id());
            require(cmd!=null && original.state()==CommandState.CLAIMED && claim.token().equals(original.claimToken())
                    && cmd.fingerprint().equals(original.fingerprint()) && cmd.configuration().equals(original.configuration())
                    && cmd.input().equals(original.input()) && encode(cmd.prepared()).equals(encode(original.prepared()))
                    && cmd.stagedRevision()==original.stagedRevision(),409,"CHECKOUT_CORRELATION_CONFLICT");
            var existing=row("SELECT response_reference FROM checkout_lifecycle_outcomes WHERE tenant_id=? AND run_id=? AND principal_id=? AND purchase_id=? AND command_id=? AND claim_token=?",cmd.purchaseId(),cmd.id(),claim.token());
            if(existing!=null)return cmd;
            Purchase p=purchase(cmd.purchaseId());
            boolean current=resolverCurrent && cmd.state()==CommandState.CLAIMED && claim.token().equals(cmd.claimToken())
                    && p.currentCommandId().equals(cmd.id()) && p.revision()==cmd.stagedRevision()
                    && cmd.configuration().equals(currentConfiguration(cmd.input().merchantId(),cmd.input().configurationId()));
            UUID reference=UUID.randomUUID();
            String requestDigest=PaymentRules.sha256(encode(original.prepared())).substring("sha256:".length());
            write("INSERT INTO checkout_lifecycle_outcomes VALUES(?,?,?,?,?,?,?,?,?,?)",cmd.purchaseId(),cmd.id(),reference,claim.token(),requestDigest,encode(result),!current);
            // A recovered original worker is still allowed to send. Its late result is retained only.
            if(cmd.state()!=CommandState.CLAIMED || !claim.token().equals(cmd.claimToken()))return cmd;
            boolean mutation=cmd.input().operation()!=Operation.RETRIEVE;
            boolean unknown=result.observation()==Observation.UNKNOWN;
            CommandState state=!current?CommandState.STALE:unknown?CommandState.UNKNOWN:CommandState.RECORDED;
            String code=!current?"STALE_OUTCOME":result.code().name();
            Observation observation=!current?Observation.UNKNOWN:result.observation();
            direct("UPDATE checkout_lifecycle_commands SET state=?,claim_token=NULL,outcome_code=?,observation=?,http_status=?,response_reference=? WHERE tenant_id=? AND run_id=? AND principal_id=? AND purchase_id=? AND command_id=?",
                    state.name(),code,observation.name(),result.httpStatus(),reference,s.tenantId(),s.runId(),s.principalId(),cmd.purchaseId(),cmd.id());
            boolean promote=current && result.code()==Code.OK;
            String session=promote?result.document().session().id():p.sessionId();
            String status=promote?result.document().status():p.remoteStatus();
            String digest=promote?PaymentRules.sha256(java.util.Base64.getEncoder().encodeToString(result.document().bytes())):p.documentDigest();
            save(new Purchase(p.id(),p.configuration(),p.currency(),session,increment(p.revision()),status,digest,p.currentCommandId(),
                    p.mutationBlocked() || mutation && (!current || unknown)));
            return command(cmd.purchaseId(),cmd.id());
        }
        @Override public StoredCommand recoverClaim(UUID purchaseId,UUID id) {
            StoredCommand cmd=command(purchaseId,id);require(cmd!=null,404,"NOT_FOUND");
            if(cmd.state()!=CommandState.CLAIMED)return cmd;
            Purchase p=purchase(purchaseId);
            direct("UPDATE checkout_lifecycle_commands SET state='UNKNOWN',claim_token=NULL,outcome_code='WORKER_INTERRUPTED',observation='UNKNOWN' WHERE tenant_id=? AND run_id=? AND principal_id=? AND purchase_id=? AND command_id=?",
                    s.tenantId(),s.runId(),s.principalId(),purchaseId,id);
            save(new Purchase(p.id(),p.configuration(),p.currency(),p.sessionId(),increment(p.revision()),p.remoteStatus(),p.documentDigest(),p.currentCommandId(),p.mutationBlocked() || cmd.input().operation()!=Operation.RETRIEVE));
            return command(purchaseId,id);
        }
        private void save(Purchase p) {
            direct("UPDATE checkout_purchases SET purchase_json=? WHERE tenant_id=? AND run_id=? AND principal_id=? AND purchase_id=?",
                    encode(p),s.tenantId(),s.runId(),s.principalId(),p.id());
        }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch(Exception error) { throw new IllegalStateException("Protected checkout encoding failed"); }
    }
    private <T> T decode(String value,Class<T> type) {
        try { return json.readValue(value,type); } catch(Exception error) { throw new IllegalStateException("Protected checkout decoding failed"); }
    }
    private static int increment(int value) { require(value<Integer.MAX_VALUE,409,"CHECKOUT_REVISION_EXHAUSTED");return value+1; }
    private static void require(boolean condition,int status,String code) { if(!condition)throw new PaymentException(status,code); }
}
