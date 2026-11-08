package io.agenticpayments.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import io.agenticpayments.application.PaymentException;
import io.agenticpayments.application.ApplicationFailure;
import io.agenticpayments.application.ApplicationException;
import io.agenticpayments.application.NativeApplicationCoordinator;
import io.agenticpayments.application.NativeApplicationCoordinator.*;
import io.agenticpayments.application.ExecutePayment;
import io.agenticpayments.application.ApprovePayment;
import io.agenticpayments.application.PaymentCommandTransactions;
import io.agenticpayments.application.PaymentCommandTransactions.*;
import io.agenticpayments.application.PaymentStore;
import io.agenticpayments.application.WorkerReceipt;
import io.agenticpayments.application.RunPaymentWork;
import io.agenticpayments.application.PaymentWorkerTransactions;
import io.agenticpayments.application.PaymentWorkerTransactions.*;
import io.agenticpayments.application.PaymentFactVerifier;
import io.agenticpayments.application.SyntheticPendingProgression;
import io.agenticpayments.application.ExternalCheckoutRegistration;
import io.agenticpayments.application.ProviderExecutionRegistration;
import io.agenticpayments.application.ProviderExecutionBinding;
import io.agenticpayments.application.ProviderEvidenceVerifier;
import io.agenticpayments.infrastructure.provider.ProviderAdmissionCodec;
import io.agenticpayments.infrastructure.provider.SyntheticProviderRegistration;
import io.agenticpayments.infrastructure.provider.JournaledPaymentAdapter;
import io.agenticpayments.infrastructure.provider.JdbcProviderExecutionBindings;
import io.agenticpayments.sdk.execution.v1.*;
import io.agenticpayments.application.CheckoutLifecycleTransactions.CoordinationDomain;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.domain.PaymentRules;
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
    public static final String EXTERNAL_PROFILE="local-external-ap2-acp-single-tender-0.1";
    private final DataSource ds;
    private final PaymentAdapter adapter;
    private final UUID replayOwner;
    private final Ports composition;
    private final NativeApplicationCoordinator nativeCoordinator;
    private final Ports externalComposition;
    private final NativeApplicationCoordinator externalCoordinator;
    private final ExternalCheckoutRegistration externalRegistration;
    private final JdbcObservedCheckoutSource externalSource=new JdbcObservedCheckoutSource();
    private final ExecutePayment execution;
    private final ApprovePayment approval;
    private final RunPaymentWork worker;
    private final ProviderExecutionRegistration providerRegistration;
    private final ProviderAdmissionCodec providerCodec=new ProviderAdmissionCodec();
    private final ThreadLocal<Boolean> entered=ThreadLocal.withInitial(()->false);
    private final ObjectMapper json=new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true).configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY,true);
    private record Tx(Connection c,Session s,Instant now) {}
    @FunctionalInterface private interface Work<T> { T run(Tx tx) throws Exception; }
    @Override public Set<String> admissionCapabilities() {
        return externalRegistration==null?Set.of("atomic-replay-grant-reservation-0.1"):
                Set.of("atomic-replay-grant-reservation-0.1","atomic-external-checkout-binding-0.1");
    }
    public JdbcPaymentStore(DataSource ds,PaymentAdapter adapter) { this(ds,adapter,(UUID)null); }
    public JdbcPaymentStore(DataSource ds,PaymentAdapter adapter,UUID replayOwner) { this(ds,adapter,replayOwner,null); }
    public JdbcPaymentStore(DataSource ds,PaymentAdapter adapter,UUID replayOwner,Ports composition) {
        this(ds,adapter,replayOwner,composition,null,null,null);
    }
    public JdbcPaymentStore(DataSource ds,PaymentAdapter adapter,UUID replayOwner,Ports composition,Ports externalComposition,
                            ExternalCheckoutRegistration externalRegistration,CoordinationDomain coordinationDomain) {
        this(ds,adapter,replayOwner,composition,externalComposition,externalRegistration,coordinationDomain,
                legacyVerifier(adapter),legacyProgression(adapter));
    }
    /** Trusted provider collaborators can be directly implemented, adapted or decorated. */
    public JdbcPaymentStore(DataSource ds,PaymentAdapter adapter,UUID replayOwner,Ports composition,Ports externalComposition,
                            ExternalCheckoutRegistration externalRegistration,CoordinationDomain coordinationDomain,
                            PaymentFactVerifier verifier,SyntheticPendingProgression progression) {
        this(ds,adapter,replayOwner,composition,externalComposition,externalRegistration,coordinationDomain,verifier,progression,null);
    }
    /** Exact provider route enrollment is atomic with core operation creation, never inferred from the adapter class. */
    private JdbcPaymentStore(DataSource ds,PaymentAdapter adapter,UUID replayOwner,Ports composition,Ports externalComposition,
                            ExternalCheckoutRegistration externalRegistration,CoordinationDomain coordinationDomain,
                            PaymentFactVerifier verifier,SyntheticPendingProgression progression,ProviderExecutionRegistration providerRegistration) {
        this.ds=Objects.requireNonNull(ds); this.adapter=Objects.requireNonNull(adapter);this.replayOwner=replayOwner; this.composition=composition;
        this.providerRegistration=providerRegistration;
        this.nativeCoordinator=composition==null?null:new NativeApplicationCoordinator(composition.checkout(),composition.issuer(),composition.verifier(),composition.artifacts());
        if((externalComposition==null)!=(externalRegistration==null)
                ||externalRegistration!=null&&externalRegistration.coordinationDomain()!=coordinationDomain) fail(ApplicationFailure.UNSUPPORTED_CAPABILITY);
        this.externalComposition=externalComposition; this.externalRegistration=externalRegistration;
        this.externalCoordinator=externalComposition==null?null:new NativeApplicationCoordinator(externalComposition.checkout(),externalComposition.issuer(),externalComposition.verifier(),externalComposition.artifacts(),EXTERNAL_PROFILE);
        if(externalComposition!=null) externalCapabilities();
        var transactions=new PaymentCommandTransactions() {
            public String requestDigest(ExecuteCommand command){return PaymentRules.sha256(encode(command));}
            public String approvalRequestDigest(ApproveCommand command){return PaymentRules.sha256(encode(command));}
            public Result run(Session session,PaymentCommandTransactions.Work work){
                return transaction(session,tx->{
                        var scope=new CommandTransaction(tx);
                        try{return work.run(scope);}finally{scope.active=false;}
                    });
            }
            public Result runApproval(Session session,PaymentCommandTransactions.ApprovalWork work){
                return transaction(session,tx->{
                        var scope=new ApprovalCommandTransaction(tx);
                        try{return work.run(scope);}finally{scope.active=false;}
                    });
            }
        };
        this.execution=new ExecutePayment(transactions,adapter::capabilities);
        this.approval=new ApprovePayment(transactions,adapter::capabilities);
        var workerTransactions=new PaymentWorkerTransactions() {
            public <T> T run(Session session,PaymentWorkerTransactions.Work<T> work) {
                return transaction(session,tx->{
                    var scope=new WorkerTransaction(tx);
                    try{return work.run(scope);}finally{scope.active=false;}
                });
            }
        };
        this.worker=new RunPaymentWork(workerTransactions,adapter,verifier,progression);
    }
    /** Assemble a coherent provider route: arbitrary legacy adapters cannot bypass its binding or projection. */
    public static JdbcPaymentStore providerJournal(DataSource ds,UUID replayOwner,Ports composition,Ports externalComposition,
            ExternalCheckoutRegistration externalRegistration,CoordinationDomain coordinationDomain,
            ProviderExecutionRegistration registration,ExecutionPreparer preparer,FinancialSubmitter submitter,
            ExecutionReconciler reconciler,Optional<NoEffectFencer> fencer,ProviderEvidenceVerifier verifier,
            SyntheticPendingProgression progression) {
        var adapter=new JournaledPaymentAdapter(Objects.requireNonNull(registration),new JdbcProviderExecutionBindings(ds),preparer,submitter,reconciler,fencer,verifier);
        return new JdbcPaymentStore(ds,adapter,replayOwner,composition,externalComposition,externalRegistration,coordinationDomain,adapter,progression,registration);
    }

    private static PaymentFactVerifier legacyVerifier(PaymentAdapter adapter) {
        Objects.requireNonNull(adapter);
        if(adapter instanceof JdbcSimulatorAdapter local)return (expected,delivered)->local.verifyEvent(expected.identity(),delivered.eventId());
        return (expected,delivered)->adapter.lookup(expected.identity()).fact().filter(delivered::equals)
                .orElseThrow(()->new IllegalArgumentException("Unverified adapter fact"));
    }
    private static SyntheticPendingProgression legacyProgression(PaymentAdapter adapter) {
        if(adapter instanceof JdbcSimulatorAdapter local)return (expected,pending)->local.finishPending(expected.identity());
        return (expected,pending)->pending;
    }

    public void seedRun(Session s,Instant clock,long budget) {
        role(s,"runner:write"); if(clock==null||budget<1250) fail(ApplicationFailure.INVALID_REQUEST);
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
        if(cmd==null||cmd.requiredCapabilities()==null||cmd.profile()==null||!Set.of(PaymentRules.PROFILE,NATIVE_PROFILE,EXTERNAL_PROFILE).contains(cmd.profile())) fail(ApplicationFailure.UNSUPPORTED_PROFILE);
        if(NATIVE_PROFILE.equals(cmd.profile())) nativeCapabilities();
        if(EXTERNAL_PROFILE.equals(cmd.profile())) externalCapabilities();
        merchant(s,cmd.merchantId());
        if(!PaymentRules.SCENARIOS.contains(cmd.scenarioId())) fail(ApplicationFailure.INVALID_REQUEST);
        if(cmd.requiredCapabilities().size()>16||new HashSet<>(cmd.requiredCapabilities()).size()!=cmd.requiredCapabilities().size()||!PaymentRules.CAPABILITIES.containsAll(cmd.requiredCapabilities())) fail(ApplicationFailure.UNSUPPORTED_CAPABILITY);
        return command(s,key,"POST /intents","new",cmd,tx->{
            var sequence=one(tx.c,"UPDATE simulation_runs SET next_intent_sequence=next_intent_sequence+1 WHERE tenant_id=? AND run_id=? RETURNING next_intent_sequence",s.tenantId(),s.runId());
            UUID id=UUID.randomUUID(); update(tx.c,"INSERT INTO payment_intents(tenant_id,run_id,intent_id,merchant_id,scenario_id,state,creation_sequence,profile) VALUES(?,?,?,?,?,'DRAFT',?,?)",s.tenantId(),s.runId(),id,cmd.merchantId(),cmd.scenarioId(),sequence.get("next_intent_sequence"),cmd.profile());
            audit(tx,id,"DRAFT"); return result(201,map("intentId",id,"state","DRAFT","version",1));
        });
    }
    public Result quote(Session s,UUID id,String key,QuoteCommand cmd) {
        role(s,"intent:write"); validate(cmd==null?0:cmd.expectedVersion());
        capabilities();
        if(cmd.tenders()==null||cmd.tenders().size()!=1) fail(ApplicationFailure.UNSUPPORTED_TENDER);
        var t=cmd.tenders().getFirst();
        if(t==null||!"simulator".equals(t.provider())||!"synthetic-card".equals(t.method())||!"fixture-credential-1".equals(t.credentialReference())) fail(ApplicationFailure.UNSUPPORTED_TENDER);
        if(!Set.of("cart-1250-eur","cart-1250-usd").contains(cmd.cartFixtureId())) fail(ApplicationFailure.INVALID_REQUEST);
        return command(s,key,"POST /intents/{id}/quote",id.toString(),cmd,tx->{
            var i=intent(tx,id); version(i,cmd.expectedVersion()); state(i,"DRAFT","QUOTED");
            if(externalProfile(i)) fail(ApplicationFailure.UNSUPPORTED_PROFILE);
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
    public Result quoteExternal(Session s,UUID id,String key,ExternalQuoteCommand cmd) {
        role(s,"intent:write");
        if(id==null||cmd==null||cmd.expectedVersion()<1||cmd.purchaseId()==null||cmd.expectedPurchaseRevision()<1||cmd.responseReference()==null) fail(ApplicationFailure.INVALID_REQUEST);
        return command(s,key,"POST /intents/{id}/quote-external",id.toString(),cmd,tx->{
            var i=intent(tx,id); version(i,cmd.expectedVersion());
            if(!externalProfile(i)) fail(ApplicationFailure.UNSUPPORTED_PROFILE); state(i,"DRAFT");
            capabilities(); externalCapabilities();
            if(cmd.tenders()==null||cmd.tenders().size()!=1) fail(ApplicationFailure.UNSUPPORTED_TENDER);
            var tender=cmd.tenders().getFirst();
            if(tender==null||!"simulator".equals(tender.provider())||!"synthetic-card".equals(tender.method())
                    ||!"fixture-credential-1".equals(tender.credentialReference())) fail(ApplicationFailure.UNSUPPORTED_TENDER);
            if(one(tx.c,"SELECT intent_id FROM external_checkout_bindings WHERE tenant_id=? AND run_id=? AND (intent_id=? OR (principal_id=? AND purchase_id=?))",
                    s.tenantId(),s.runId(),id,s.principalId(),cmd.purchaseId())!=null) fail(ApplicationFailure.CHECKOUT_ALREADY_BOUND);
            var observation=externalSource.load(tx.c,scope(tx),s.principalId(),cmd.purchaseId(),cmd.expectedPurchaseRevision(),cmd.responseReference());
            validateRegistration(tx,observation);
            if(!observation.configuration().binding().merchantId().equals(text(i,"merchant_id"))) fail(ApplicationFailure.AUTHORITY_BINDING_INVALID);
            var terms=externalCoordinator.inspectObserved(observation,tx.now);
            Instant expiry=Collections.min(List.of(observation.observedAt().plusSeconds(300),terms.expiresAt()));
            int revision=(int)number(i,"checkout_revision")+1;
            var facts=new CheckoutFacts(scope(tx),id,text(i,"merchant_id"),revision,terms.currency(),terms.minorUnits(),tender.credentialReference(),expiry);
            var provision=provision(tx,externalComposition);
            var frozen=externalCoordinator.freezeObserved(new ObservedCheckoutRequest(observation,facts,provision.merchantSigner()),terms);
            var stored=externalCoordinator.retainCheckout(scope(tx),frozen);
            var reloaded=externalCoordinator.reloadCheckout(scope(tx),stored,frozen.checkoutDigest());
            externalCoordinator.validateObservedBytes(observation,terms,facts,reloaded);
            externalCoordinator.validateCheckout(facts,reloaded,keyResolver(tx,externalComposition),tx.now);
            update(tx.c,"INSERT INTO checkout_snapshots(tenant_id,run_id,intent_id,revision,digest,canonical_bytes,currency,minor_units,credential_reference,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    s.tenantId(),s.runId(),id,revision,frozen.checkoutDigest(),"native-artifact:"+stored.checkoutJwt().id(),terms.currency(),terms.minorUnits(),tender.credentialReference(),expiry);
            update(tx.c,"INSERT INTO native_checkouts VALUES(?,?,?,?,?)",s.tenantId(),s.runId(),id,revision,encode(stored));
            update(tx.c,"INSERT INTO external_checkout_bindings VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'local-synthetic-attestation-0.1')",
                    s.tenantId(),s.runId(),s.principalId(),cmd.purchaseId(),id,observation.commandId(),cmd.responseReference(),cmd.expectedPurchaseRevision(),encode(observation.configuration()),
                    observation.observedAt(),io.agenticpayments.composition.ArtifactIntegrity.digest(observation.bytes()),expiry);
            update(tx.c,"UPDATE payment_intents SET checkout_revision=? WHERE tenant_id=? AND run_id=? AND intent_id=?",revision,s.tenantId(),s.runId(),id);
            change(tx,id,"QUOTED");
            return result(200,map("intentId",id,"state","QUOTED","version",cmd.expectedVersion()+1,"checkoutRevision",revision,
                    "checkoutDigest",frozen.checkoutDigest(),"money",money(terms.currency(),terms.minorUnits()),"quoteExpiresAt",expiry.toString()));
        });
    }
    public Result approve(Session s,UUID id,String key,ApproveCommand cmd) {
        return approval.approve(s,id,key,cmd);
    }
    public Result execute(Session s,UUID id,String key,ExecuteCommand cmd) {
        return execution.execute(s,id,key,cmd);
    }

    /** Approval row mapping and phased atomic writes; policy lives in ApprovePayment. */
    private final class ApprovalCommandTransaction implements PaymentCommandTransactions.ApprovalTransaction {
        private final Tx tx;
        private final Thread thread=Thread.currentThread();
        private boolean active=true,nativeVerified,finalized;
        private Map<String,Object> intentRow,checkoutRow,grantRow,acceptedRow;
        private ApprovalIntent intentValue;
        private ApprovalCheckout checkoutValue;
        private ApprovalGrant grantValue;
        private ApprovalBudget budgetValue;
        private AcceptedCommand acceptedValue;
        private ApprovalWrite stagedWrite;
        ApprovalCommandTransaction(Tx tx){this.tx=tx;}
        private void active(){if(!active||thread!=Thread.currentThread())throw new IllegalStateException("Payment transaction is no longer active");}
        private void reading(){active();if(stagedWrite!=null)throw new IllegalStateException("Approval facts are already staged");}
        public Instant now(){active();return tx.now;}
        public ApprovalIntent intent(UUID id) throws SQLException {
            reading();intentRow=intentUnchecked(tx,id);
            checkoutRow=null;checkoutValue=null;grantRow=null;grantValue=null;budgetValue=null;
            return intentValue=new ApprovalIntent(id,text(intentRow,"merchant_id"),text(intentRow,"profile"),text(intentRow,"state"),number(intentRow,"version"),(int)number(intentRow,"checkout_revision"));
        }
        public ApprovalCheckout checkout(ApprovalIntent intent) throws SQLException {
            reading();if(intent!=intentValue)throw new IllegalArgumentException("Unbound payment transaction facts");
            checkoutRow=quote(tx,intentRow);
            return checkoutValue=new ApprovalCheckout((int)number(checkoutRow,"revision"),text(checkoutRow,"digest"),text(checkoutRow,"currency"),number(checkoutRow,"minor_units"),instant(checkoutRow,"expires_at"));
        }
        public ApprovalGrant grant(String id) throws SQLException {
            reading();grantRow=JdbcPaymentStore.this.grant(tx,id);budgetValue=null;
            return grantValue=new ApprovalGrant(id,text(grantRow,"subject_id"),text(grantRow,"merchant_id"),text(grantRow,"state"),number(grantRow,"version"),instant(grantRow,"valid_from"),instant(grantRow,"expires_at"),text(grantRow,"budget_id"));
        }
        public ApprovalBudget budget(String id) throws SQLException {
            reading();var row=JdbcPaymentStore.this.budget(tx,id);
            return budgetValue=new ApprovalBudget(id,text(row,"currency"),Boolean.TRUE.equals(row.get("frozen")),number(row,"held"),number(row,"consumed"),number(row,"limit_minor"));
        }
        public Optional<AcceptedCommand> accepted(CommandIdentity id) throws Exception {
            active();acceptedRow=one(tx.c,"SELECT * FROM commands WHERE tenant_id=? AND run_id=? AND principal_id=? AND route=? AND resource_id=? AND idempotency_key=?",tx.s.tenantId(),tx.s.runId(),tx.s.principalId(),id.route(),id.resource(),id.key());
            acceptedValue=acceptedRow==null?null:new AcceptedCommand(text(acceptedRow,"request_digest"));
            return Optional.ofNullable(acceptedValue);
        }
        public Result replay(AcceptedCommand accepted) throws Exception {
            active();if(accepted==null||accepted!=acceptedValue)throw new IllegalArgumentException("Unbound accepted command");
            return new Result((int)number(acceptedRow,"response_status"),json.readValue(text(acceptedRow,"response_body"),new TypeReference<Map<String,Object>>(){}),false);
        }
        public void stageAttempt(ApprovalWrite write) throws SQLException {
            active();
            if(write==null||stagedWrite!=null||intentValue==null||checkoutValue==null||grantValue==null||budgetValue==null
                    ||!write.intentId().equals(intentValue.id())||write.expectedVersion()!=intentValue.version()
                    ||write.checkoutRevision()!=checkoutValue.revision()||write.checkoutRevision()!=intentValue.checkoutRevision()
                    ||!write.grantId().equals(grantValue.id())||write.grantVersion()!=grantValue.version()
                    ||!write.subjectId().equals(tx.s.principalId())||!write.subjectId().equals(grantValue.subjectId())
                    ||!write.budgetId().equals(grantValue.budgetId())||!write.budgetId().equals(budgetValue.id())
                    ||write.minorUnits()!=checkoutValue.minorUnits())throw new IllegalArgumentException("Unbound payment approval");
            update(tx.c,"INSERT INTO payment_attempts VALUES(?,?,?,?,?,?,?,?,'simulator-core')",tx.s.tenantId(),tx.s.runId(),write.attemptId(),write.intentId(),write.checkoutRevision(),write.grantId(),write.grantVersion(),write.subjectId());
            stagedWrite=write;
        }
        public void issueAndVerifyNative(ApprovalIntent intent,ApprovalCheckout checkout,ApprovalGrant grant,ApprovalWrite write) throws Exception {
            active();
            if(write==null||write!=stagedWrite||intent!=intentValue||checkout!=checkoutValue||grant!=grantValue
                    ||nativeVerified||finalized||!nativeProfile(intentRow))throw new IllegalArgumentException("Unbound native approval");
            approveNative(tx,intentRow,checkoutRow,grantRow,write.attemptId());nativeVerified=true;
        }
        public void reserveAndApprove(ApprovalWrite write) throws SQLException {
            active();
            if(write==null||write!=stagedWrite||finalized||(nativeProfile(intentRow)&&!nativeVerified))throw new IllegalArgumentException("Unbound payment reservation");
            update(tx.c,"INSERT INTO reservations VALUES(?,?,?,?,?,'HELD')",tx.s.tenantId(),tx.s.runId(),write.attemptId(),write.budgetId(),write.minorUnits());
            update(tx.c,"UPDATE budgets SET held=held+? WHERE tenant_id=? AND run_id=? AND budget_id=?",write.minorUnits(),tx.s.tenantId(),tx.s.runId(),write.budgetId());
            change(tx,write.intentId(),"APPROVED");finalized=true;
        }
        public void accept(CommandIdentity id,String requestDigest,Result result) throws SQLException {
            active();update(tx.c,"INSERT INTO commands VALUES(?,?,?,?,?,?,?,?,?)",tx.s.tenantId(),tx.s.runId(),tx.s.principalId(),id.route(),id.resource(),id.key(),requestDigest,result.status(),encode(result.body()));
        }
    }

    /** Infrastructure-only row mapping and indivisible writes; use-case decisions live in ExecutePayment. */
    private final class CommandTransaction implements PaymentCommandTransactions.Transaction {
        private final Tx tx;
        private final Thread thread=Thread.currentThread();
        private boolean active=true,nativeVerified;
        private Map<String,Object> intentRow,attemptRow,grantRow,acceptedRow;
        private ExecutionIntent intentValue;
        private ExecutionAttempt attemptValue;
        private ExecutionGrant grantValue;
        private AcceptedCommand acceptedValue;
        CommandTransaction(Tx tx){this.tx=tx;}
        private void active(){if(!active||thread!=Thread.currentThread())throw new IllegalStateException("Payment transaction is no longer active");}
        public Instant now(){active();return tx.now;}
        public ExecutionIntent intent(UUID id) throws SQLException {
            active();intentRow=intentUnchecked(tx,id);nativeVerified=false;
            return intentValue=new ExecutionIntent(id,text(intentRow,"merchant_id"),text(intentRow,"profile"),text(intentRow,"state"),number(intentRow,"version"),(int)number(intentRow,"checkout_revision"),text(intentRow,"scenario_id"));
        }
        public ExecutionAttempt attempt(UUID id) throws SQLException {
            active();attemptRow=JdbcPaymentStore.this.attempt(tx,id);
            return attemptValue=attemptRow==null?null:new ExecutionAttempt(uuid(attemptRow,"attempt_id"),uuid(attemptRow,"intent_id"),(int)number(attemptRow,"checkout_revision"),text(attemptRow,"grant_id"),number(attemptRow,"grant_version"),text(attemptRow,"subject_id"));
        }
        public ExecutionCheckout checkout(ExecutionIntent intent) throws SQLException {
            active();if(intent!=intentValue)throw new IllegalArgumentException("Unbound payment transaction facts");
            var row=quote(tx,intentRow);return new ExecutionCheckout(text(row,"digest"),text(row,"currency"),number(row,"minor_units"),text(row,"credential_reference"));
        }
        public ExecutionGrant grant(String id) throws SQLException {
            active();grantRow=JdbcPaymentStore.this.grant(tx,id);
            return grantValue=new ExecutionGrant(id,text(grantRow,"subject_id"),text(grantRow,"merchant_id"),text(grantRow,"state"),number(grantRow,"version"),instant(grantRow,"valid_from"),instant(grantRow,"expires_at"));
        }
        public Optional<AcceptedCommand> accepted(CommandIdentity id) throws Exception {
            active();acceptedRow=one(tx.c,"SELECT * FROM commands WHERE tenant_id=? AND run_id=? AND principal_id=? AND route=? AND resource_id=? AND idempotency_key=?",tx.s.tenantId(),tx.s.runId(),tx.s.principalId(),id.route(),id.resource(),id.key());
            acceptedValue=acceptedRow==null?null:new AcceptedCommand(text(acceptedRow,"request_digest"));
            return Optional.ofNullable(acceptedValue);
        }
        public Result replay(AcceptedCommand accepted) throws Exception {
            active();if(accepted==null||accepted!=acceptedValue)throw new IllegalArgumentException("Unbound accepted command");
            return new Result((int)number(acceptedRow,"response_status"),json.readValue(text(acceptedRow,"response_body"),new TypeReference<Map<String,Object>>(){}),false);
        }
        public void verifyNative(ExecutionIntent intent,ExecutionAttempt attempt,ExecutionGrant grant) throws Exception {
            active();if(intent!=intentValue||attempt!=attemptValue||grant!=grantValue)throw new IllegalArgumentException("Unbound payment transaction facts");
            JdbcPaymentStore.this.verifyNative(tx,intentRow,attemptRow,grantRow);nativeVerified=true;
        }
        public void prepare(PreparationWrite write) throws SQLException {
            active();
            if(intentValue==null||attemptValue==null||!write.intentId().equals(intentValue.id())||write.expectedVersion()!=intentValue.version()
                    ||!write.attemptId().equals(attemptValue.id())||!write.intentId().equals(attemptValue.intentId())
                    ||(nativeProfile(intentRow)&&!nativeVerified))throw new IllegalArgumentException("Unbound payment preparation");
            update(tx.c,"INSERT INTO financial_operations(tenant_id,run_id,operation_id,attempt_id,adapter_key,request_digest) VALUES(?,?,?,?,?,?)",tx.s.tenantId(),tx.s.runId(),write.operationId(),write.attemptId(),write.operationId(),write.requestDigest());
            var q=quote(tx,intentRow);
            var core=new ChargeOperation(new OperationIdentity(new ExecutionScope(tx.s.tenantId(),tx.s.runId(),text(intentRow,"merchant_id")),write.intentId(),write.attemptId(),write.operationId(),write.operationId(),write.requestDigest()),new Money(text(q,"currency"),number(q,"minor_units")),text(intentRow,"scenario_id"));
            String route="legacy-local-fake-1",bindingJson=null;
            if(providerRegistration!=null) {
                var binding=providerRegistration.binding(core);validateProviderBinding(core,text(q,"credential_reference"),binding);
                route=binding.profile();bindingJson=providerCodec.encode(binding);
            }
            update(tx.c,"INSERT INTO payment_operation_routes(tenant_id,run_id,operation_id,route,binding_json) VALUES(?,?,?,?,?)",tx.s.tenantId(),tx.s.runId(),write.operationId(),route,bindingJson);
            work(tx,write.operationId(),"PREPARE");change(tx,write.intentId(),"PREPARING");
        }
        public void accept(CommandIdentity id,String requestDigest,Result result) throws SQLException {
            active();update(tx.c,"INSERT INTO commands VALUES(?,?,?,?,?,?,?,?,?)",tx.s.tenantId(),tx.s.runId(),tx.s.principalId(),id.route(),id.resource(),id.key(),requestDigest,result.status(),encode(result.body()));
        }
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
            if(!Objects.equals(g.get("subject_id"),s.principalId())) fail(ApplicationFailure.RESOURCE_NOT_FOUND);
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
        role(s,"action:complete"); validate(cmd==null?0:cmd.expectedVersion()); if(!Set.of("approve","deny").contains(cmd.decision())) fail(ApplicationFailure.INVALID_REQUEST);
        return command(s,key,"POST /actions/{id}/complete",id.toString(),cmd,tx->{
            var ac=actionRow(tx,id); var o=operationById(tx,(UUID)ac.get("operation_id")); var a=attemptById(tx,(UUID)o.get("attempt_id")); var i=intent(tx,(UUID)a.get("intent_id"));
            if(!text(ac,"state").equals("OPEN")) fail(ApplicationFailure.ACTION_ALREADY_CONSUMED); version(ac,cmd.expectedVersion());
            if(!tx.now.isBefore(instant(ac,"expires_at"))) fail(ApplicationFailure.ACTION_EXPIRED);
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
        if(cmd==null||!Set.of("user-request","policy-revoked","test-revocation").contains(cmd.reason())) fail(ApplicationFailure.INVALID_REQUEST);
        return command(s,key,"POST /authorities/{id}/revoke",authorityId,cmd,tx->{
            var g=grant(tx,authorityId); if(!Objects.equals(g.get("subject_id"),s.principalId())) fail(ApplicationFailure.RESOURCE_NOT_FOUND);
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
            if(clock==null||clock.isBefore(tx.now)) fail(ApplicationFailure.INVALID_REQUEST);
            update(tx.c,"UPDATE simulation_runs SET virtual_time=? WHERE tenant_id=? AND run_id=?",clock,s.tenantId(),s.runId());audit(new Tx(tx.c,s,clock),null,"CLOCK_ADVANCED");return null;
        });
    }
    /** Typed SQL phases; application decisions are in RunPaymentWork. */
    private final class WorkerTransaction implements PaymentWorkerTransactions.Transaction {
        private final Tx tx;
        private final Thread thread=Thread.currentThread();
        private boolean active=true,nativeVerified,written;
        private Map<String,Object> intentRow,attemptRow,operationRow,grantRow;
        private Snapshot snapshotValue;
        private WorkItem selected;
        private Claim claimed;
        WorkerTransaction(Tx tx){this.tx=tx;}
        private void active(){if(!active||thread!=Thread.currentThread())throw new IllegalStateException("Payment transaction is no longer active");}
        private void bound(Snapshot snapshot) {
            active();if(snapshot==null||snapshot!=snapshotValue||written)throw new IllegalArgumentException("Unbound worker transaction facts");
        }
        private void bound(Claim claim,Snapshot snapshot) {
            bound(snapshot);if(claim==null||!claim.operationId().equals(snapshot.operation().identity().operationId()))throw new IllegalArgumentException("Unbound worker claim");
        }
        private Map<String,Object> outbox(UUID operation,WorkKind kind) throws SQLException {
            return one(tx.c,"SELECT * FROM outbox WHERE tenant_id=? AND run_id=? AND operation_id=? AND kind=?",tx.s.tenantId(),tx.s.runId(),operation,kind.name());
        }
        public Instant now(){active();return tx.now;}
        public Activity activity() throws SQLException {
            active();long[] values=new long[4];JdbcPaymentStore.this.activity(tx,values);
            return new Activity(values[0],values[1],values[2],values[3]);
        }
        public void expireEligible() throws Exception {
            active();if(snapshotValue!=null||selected!=null||claimed!=null||written)throw new IllegalStateException("Worker phase already selected");
            expire(tx);
        }
        public Optional<WorkItem> next() throws SQLException {
            active();if(claimed!=null||written)throw new IllegalStateException("Worker phase already claimed");
            var row=one(tx.c,"SELECT w.* FROM outbox w JOIN financial_operations o USING(tenant_id,run_id,operation_id) JOIN payment_attempts a USING(tenant_id,run_id,attempt_id) WHERE w.tenant_id=? AND w.run_id=? AND NOT w.done ORDER BY CASE w.kind WHEN 'PREPARE' THEN 0 WHEN 'DISPATCH' THEN 1 ELSE 2 END,w.enqueue_sequence LIMIT 1",tx.s.tenantId(),tx.s.runId());
            selected=row==null?null:new WorkItem(uuid(row,"operation_id"),WorkKind.valueOf(text(row,"kind")),number(row,"fence"));
            claimed=null;return Optional.ofNullable(selected);
        }
        public Snapshot load(UUID operationId) throws SQLException {
            active();if(written)throw new IllegalStateException("Worker phase already written");
            operationRow=operationById(tx,operationId);attemptRow=required(one(tx.c,"SELECT * FROM payment_attempts WHERE tenant_id=? AND run_id=? AND attempt_id=?",tx.s.tenantId(),tx.s.runId(),operationRow.get("attempt_id")));
            intentRow=intentUnchecked(tx,uuid(attemptRow,"intent_id"));grantRow=grant(tx,text(attemptRow,"grant_id"));
            var quote=JdbcPaymentStore.this.quote(tx,intentRow);var reservation=JdbcPaymentStore.this.reservation(tx,uuid(attemptRow,"attempt_id"));
            var budget=JdbcPaymentStore.this.budget(tx,text(reservation,"budget_id"));
            var action=one(tx.c,"SELECT * FROM customer_actions WHERE tenant_id=? AND run_id=? AND operation_id=?",tx.s.tenantId(),tx.s.runId(),operationId);
            nativeVerified=false;
            return snapshotValue=new Snapshot(envelope(tx,intentRow,attemptRow,operationRow),text(intentRow,"profile"),text(intentRow,"state"),
                    number(attemptRow,"grant_version"),text(attemptRow,"subject_id"),text(quote,"digest"),
                    new Grant(text(grantRow,"state"),number(grantRow,"version"),text(grantRow,"subject_id"),text(grantRow,"merchant_id"),instant(grantRow,"valid_from"),instant(grantRow,"expires_at")),
                    text(reservation,"state"),Boolean.TRUE.equals(budget.get("frozen")),operationRow.get("admitted_sequence")!=null,
                    action==null?null:new Action(text(action,"state"),text(action,"subject_id"),text(action,"checkout_digest"),instant(action,"expires_at")));
        }
        public Snapshot findAdmitted(UUID intentId) throws SQLException {
            active();intentUnchecked(tx,intentId);var attempt=JdbcPaymentStore.this.attempt(tx,intentId);
            if(attempt==null)fail(ApplicationFailure.INVALID_STATE);
            var operation=JdbcPaymentStore.this.operation(tx,uuid(attempt,"attempt_id"));
            if(operation==null||operation.get("admitted_sequence")==null)fail(ApplicationFailure.INVALID_STATE);
            return load(uuid(operation,"operation_id"));
        }
        public Claim claim(WorkItem work) throws SQLException {
            active();if(work==null||work!=selected||claimed!=null)throw new IllegalArgumentException("Unbound worker selection");
            var row=outbox(work.operationId(),work.kind());
            if(row==null||Boolean.TRUE.equals(row.get("done"))||number(row,"fence")!=work.fence())throw new IllegalArgumentException("Stale worker selection");
            long next=Math.addExact(work.fence(),1);
            update(tx.c,"UPDATE outbox SET fence=? WHERE tenant_id=? AND run_id=? AND operation_id=? AND kind=?",next,tx.s.tenantId(),tx.s.runId(),work.operationId(),work.kind().name());
            return claimed=new Claim(work.operationId(),work.kind(),next);
        }
        public Claim reconciliationClaim(Snapshot snapshot) throws SQLException {
            bound(snapshot);if(!snapshot.admitted())throw new IllegalArgumentException("Unadmitted observation");
            var operation=snapshot.operation().identity().operationId();var row=outbox(operation,WorkKind.RECONCILE);
            if(row==null)throw new IllegalStateException("Missing reconciliation work");
            if(Boolean.TRUE.equals(row.get("done")))return new Claim(operation,WorkKind.RECONCILE,number(row,"fence"));
            selected=new WorkItem(operation,WorkKind.RECONCILE,number(row,"fence"));return claim(selected);
        }
        public boolean isCurrent(Claim claim) throws SQLException {
            active();return claim!=null&&fence(tx,claim.operationId(),claim.kind().name(),claim.fence());
        }
        public boolean nativeGate(Snapshot snapshot) throws Exception {
            bound(snapshot);if(!nativeProfile(intentRow))throw new IllegalArgumentException("Non-native worker gate");
            nativeVerified=JdbcPaymentStore.this.nativeGate(tx,intentRow,attemptRow,grantRow);
            if(!nativeVerified)written=true;
            return nativeVerified;
        }
        public void terminateBeforeAdmission(Snapshot snapshot,Termination termination) throws SQLException {
            bound(snapshot);terminate(tx,intentRow,attemptRow,Objects.requireNonNull(termination).name());written=true;
        }
        public void completeObsolete(WorkItem work) throws SQLException {
            active();if(work==null||work!=selected||written)throw new IllegalArgumentException("Unbound worker selection");
            long expected=claimed==null?work.fence():claimed.fence();
            if(!fence(tx,work.operationId(),work.kind().name(),expected))throw new IllegalArgumentException("Stale worker selection");
            done(tx,work.operationId(),work.kind().name());written=true;
        }
        public void admit(Claim claim,Snapshot snapshot) throws SQLException {
            bound(claim,snapshot);
            if(claim!=claimed||claim.kind()!=WorkKind.DISPATCH||!isCurrent(claim)||snapshot.admitted()
                    ||!"READY".equals(snapshot.state())||!"HELD".equals(snapshot.reservationState())
                    ||(nativeProfile(intentRow)&&!nativeVerified))throw new IllegalArgumentException("Unbound dispatch admission");
            var sequence=one(tx.c,"UPDATE simulation_runs SET admission_sequence=admission_sequence+1 WHERE tenant_id=? AND run_id=? RETURNING admission_sequence",tx.s.tenantId(),tx.s.runId());
            update(tx.c,"UPDATE financial_operations SET admitted_sequence=?,admitted_at=?,admitted_grant_version=? WHERE tenant_id=? AND run_id=? AND operation_id=?",sequence.get("admission_sequence"),tx.now,grantRow.get("version"),tx.s.tenantId(),tx.s.runId(),claim.operationId());
            change(tx,snapshot.operation().identity().intentId(),"ADMITTED");done(tx,claim.operationId(),"DISPATCH");work(tx,claim.operationId(),"RECONCILE");written=true;
        }
        public void completePreparation(Claim claim,Snapshot snapshot,ActionType action) throws SQLException {
            bound(claim,snapshot);
            if(claim.kind()!=WorkKind.PREPARE||!isCurrent(claim)||snapshot.admitted()||!"PREPARING".equals(snapshot.state())
                    ||(nativeProfile(intentRow)&&!nativeVerified))throw new IllegalArgumentException("Unbound preparation completion");
            if(action==ActionType.SYNTHETIC_CHALLENGE) {
                update(tx.c,"INSERT INTO customer_actions(tenant_id,run_id,action_id,operation_id,subject_id,checkout_digest,state,expires_at) VALUES(?,?,?,?,?,?,'OPEN',?) ON CONFLICT DO NOTHING",tx.s.tenantId(),tx.s.runId(),UUID.randomUUID(),claim.operationId(),attemptRow.get("subject_id"),snapshot.checkoutDigest(),tx.now.plusSeconds(600));
                change(tx,snapshot.operation().identity().intentId(),"WAITING_ACTION");
            } else {change(tx,snapshot.operation().identity().intentId(),"READY");work(tx,claim.operationId(),"DISPATCH");}
            done(tx,claim.operationId(),"PREPARE");written=true;
        }
        public void markUnknown(Claim claim,Snapshot snapshot) throws SQLException {
            bound(claim,snapshot);
            if(!snapshot.admitted()||!"ADMITTED".equals(snapshot.state()))return;
            var row=outbox(claim.operationId(),claim.kind());
            boolean valid=row!=null&&number(row,"fence")==claim.fence()
                    &&(claim.kind()==WorkKind.DISPATCH&&Boolean.TRUE.equals(row.get("done"))
                    ||claim.kind()==WorkKind.RECONCILE&&!Boolean.TRUE.equals(row.get("done")));
            if(!valid)return;
            change(tx,snapshot.operation().identity().intentId(),"UNKNOWN");work(tx,claim.operationId(),"RECONCILE");written=true;
        }
        public void applyVerifiedFact(ChargeOperation expected,Fact fact) throws Exception {
            active();var snapshot=load(expected.identity().operationId());
            if(!snapshot.operation().equals(expected)||!snapshot.admitted())throw new IllegalArgumentException("Unbound financial evidence");
            applyFact(tx,expected,fact);written=true;
        }
        public void quarantineProvider(ChargeOperation expected) throws Exception {
            active();var snapshot=load(expected.identity().operationId());
            if(providerRegistration==null||!snapshot.operation().equals(expected))throw new IllegalArgumentException("Unbound provider quarantine");
            var prior=one(tx.c,"SELECT operation_id FROM disputed_exposure WHERE tenant_id=? AND run_id=? AND operation_id=?",tx.s.tenantId(),tx.s.runId(),expected.identity().operationId());
            freeze(tx,attemptRow,expected.identity().operationId(),expected.money().minorUnits(),"provider-evidence-conflict");
            if(prior==null)audit(tx,expected.identity().intentId(),"PROVIDER_EVIDENCE_CONFLICT");written=true;
        }
        public WorkerReceipt receipt(Activity before,Selection selected) throws SQLException {
            active();return JdbcPaymentStore.this.receipt(tx,new long[]{before.auditSequence(),before.completedWork(),before.evidence(),before.conflicts()},selected);
        }
    }
    public boolean runNext(Session s) { return worker.runNext(s); }
    public WorkerReceipt runNextRecorded(Session s) { return worker.runNextRecorded(s); }
    public void reconcile(Session s,UUID id,boolean sealIfAbsent) { worker.reconcile(s,id,sealIfAbsent); }
    private void applyFact(Tx tx,ChargeOperation expected,Fact delivered) throws Exception {
        Session s=tx.s;
        var id=expected.identity(); var i=intentUnchecked(tx,id.intentId()); var a=attempt(tx,id.intentId()); var o=operationById(tx,id.operationId());
        String digest=PaymentRules.sha256(encode(delivered));
        var existing=one(tx.c,"SELECT * FROM event_inbox WHERE tenant_id=? AND run_id=? AND event_id=?",s.tenantId(),s.runId(),delivered.eventId());
        if(existing!=null) {
            if(!Objects.equals(existing.get("fact_digest"),digest)) { conflict(tx,delivered,id.operationId(),digest,"event-conflict"); freeze(tx,a,id.operationId(),expected.money().minorUnits(),"event-conflict"); }
            return;
        }
        update(tx.c,"INSERT INTO event_inbox VALUES(?,?,?,?,?)",s.tenantId(),s.runId(),delivered.eventId(),id.operationId(),digest);
        update(tx.c,"INSERT INTO operation_evidence(tenant_id,run_id,event_id,operation_id,sequence,outcome,fact_digest) VALUES(?,?,?,?,?,?,?)",s.tenantId(),s.runId(),delivered.eventId(),id.operationId(),delivered.sequence(),delivered.outcome().name(),digest);
        if(!delivered.identity().equals(id)||!delivered.money().equals(expected.money())) { conflict(tx,delivered,id.operationId(),digest,"binding-conflict"); freeze(tx,a,id.operationId(),expected.money().minorUnits(),"binding-conflict"); return; }
        String finalOutcome=text(o,"final_outcome");
        if(finalOutcome!=null&&!finalOutcome.equals(delivered.outcome().name())&&delivered.outcome()!=Outcome.PENDING) { conflict(tx,delivered,id.operationId(),digest,"final-conflict"); freeze(tx,a,id.operationId(),expected.money().minorUnits(),"final-conflict"); return; }
        if(delivered.sequence()<number(o,"fact_sequence")) return;
        if(delivered.sequence()==number(o,"fact_sequence")&&number(o,"fact_sequence")>0) { conflict(tx,delivered,id.operationId(),digest,"sequence-conflict"); freeze(tx,a,id.operationId(),expected.money().minorUnits(),"sequence-conflict"); return; }
        update(tx.c,"UPDATE financial_operations SET fact_sequence=? WHERE tenant_id=? AND run_id=? AND operation_id=?",delivered.sequence(),s.tenantId(),s.runId(),id.operationId());
        if(finalOutcome!=null) return;
        if(delivered.outcome()==Outcome.PENDING) { acceptEvidence(tx,delivered.eventId()); change(tx,id.intentId(),"PENDING"); }
        else {
            var currentReservation=reservation(tx,(UUID)a.get("attempt_id"));
            if(delivered.outcome()==Outcome.NO_EFFECT&&Boolean.TRUE.equals(budget(tx,text(currentReservation,"budget_id")).get("frozen"))) return;
            acceptEvidence(tx,delivered.eventId());
            String next=delivered.outcome()==Outcome.SUCCEEDED?"SUCCEEDED":"FAILED";
            settle(tx,a,delivered.outcome()==Outcome.SUCCEEDED?"CONSUMED":"RELEASED"); change(tx,id.intentId(),next);
            update(tx.c,"UPDATE financial_operations SET final_outcome=? WHERE tenant_id=? AND run_id=? AND operation_id=?",delivered.outcome().name(),s.tenantId(),s.runId(),id.operationId());
            done(tx,id.operationId(),"RECONCILE");
        }
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
        var q=quote(tx,i);var core=new ChargeOperation(new OperationIdentity(new ExecutionScope(tx.s.tenantId(),tx.s.runId(),text(i,"merchant_id")),(UUID)i.get("intent_id"),(UUID)a.get("attempt_id"),(UUID)o.get("operation_id"),(UUID)o.get("adapter_key"),text(o,"request_digest")),new Money(text(q,"currency"),number(q,"minor_units")),text(i,"scenario_id"));
        var route=one(tx.c,"SELECT route,binding_json FROM payment_operation_routes WHERE tenant_id=? AND run_id=? AND operation_id=?",tx.s.tenantId(),tx.s.runId(),core.identity().operationId());
        if(route==null)fail(ApplicationFailure.UNSUPPORTED_CAPABILITY);
        if(providerRegistration==null) {
            if(!"legacy-local-fake-1".equals(text(route,"route"))||route.get("binding_json")!=null)fail(ApplicationFailure.UNSUPPORTED_CAPABILITY);
        } else {
            if(!SyntheticProviderRegistration.PROFILE.equals(text(route,"route")))fail(ApplicationFailure.UNSUPPORTED_CAPABILITY);
            ProviderExecutionBinding retained;
            try{retained=providerCodec.decode(text(route,"binding_json"));}catch(RuntimeException invalid){throw new PaymentException(ApplicationFailure.UNSUPPORTED_CAPABILITY);}
            validateProviderBinding(core,text(q,"credential_reference"),retained);
            if(!providerRegistration.binding(core).equals(retained))fail(ApplicationFailure.UNSUPPORTED_CAPABILITY);
        }
        return core;
    }
    private static void validateProviderBinding(ChargeOperation core,String credential,ProviderExecutionBinding binding) {
        if(binding==null||!binding.coreOperation().equals(core)||!credential.equals(binding.credentialReference())
                ||!SyntheticProviderRegistration.PROFILE.equals(binding.profile())||!SyntheticProviderRegistration.MAPPING.equals(binding.mappingPolicy())
                ||!"synthetic-card".equals(binding.operation().selection().combination().methodId())
                ||!"synthetic-reference".equals(binding.operation().selection().combination().credentialFormatId()))fail(ApplicationFailure.UNSUPPORTED_CAPABILITY);
    }
    private Result command(Session s,String key,String route,String resource,Object request,Work<Result> body) {
        if(key==null||!key.matches("[\\x20-\\x7E]{16,128}")) fail(ApplicationFailure.INVALID_REQUEST); String digest=PaymentRules.sha256(encode(request));
        return transaction(s,tx->{
            // Replay remains subject to current object access, not merely knowledge of an old key.
            if(route.contains("/intents/{id}")) intent(tx,UUID.fromString(resource));
            else if(route.contains("/actions/{id}")) actionRow(tx,UUID.fromString(resource));
            var old=one(tx.c,"SELECT * FROM commands WHERE tenant_id=? AND run_id=? AND principal_id=? AND route=? AND resource_id=? AND idempotency_key=?",s.tenantId(),s.runId(),s.principalId(),route,resource,key);
            if(old!=null) {
                if(!digest.equals(old.get("request_digest"))) fail(ApplicationFailure.IDEMPOTENCY_CONFLICT);
                return new Result((int)number(old,"response_status"),json.readValue(text(old,"response_body"),new TypeReference<Map<String,Object>>(){}),true);
            }
            Result result=body.run(tx);
            update(tx.c,"INSERT INTO commands VALUES(?,?,?,?,?,?,?,?,?)",s.tenantId(),s.runId(),s.principalId(),route,resource,key,digest,result.status(),encode(result.body())); return result;
        });
    }
    private <T> T transaction(Session s,Work<T> work) {
        if(entered.get())throw new IllegalStateException("Nested payment transaction");
        entered.set(true);
        try{return transactionBody(s,work);}finally{entered.remove();}
    }
    private <T> T transactionBody(Session s,Work<T> work) {
        for(int retry=0;retry<3;retry++) {
            try(var c=ds.getConnection()) {
                c.setAutoCommit(false);
                try {
                    var run=one(c,"SELECT * FROM simulation_runs WHERE tenant_id=? AND run_id=? FOR UPDATE",s.tenantId(),s.runId()); if(run==null) fail(ApplicationFailure.RESOURCE_NOT_FOUND);
                    owner(run);
                    T result=work.run(new Tx(c,s,instant(run,"virtual_time"))); c.commit(); return result;
                } catch(Exception e) { c.rollback(); throw e; }
            } catch(ApplicationException e) { throw e; }
            catch(SQLException e) { if(e.getSQLState()!=null&&e.getSQLState().startsWith("40")&&retry<2) continue; if("23505".equals(e.getSQLState())) throw new PaymentException(ApplicationFailure.AUTHORITY_REPLAY); throw new PaymentException(ApplicationFailure.TEMPORARILY_UNAVAILABLE_SAME_COMMAND); }
            catch(CompositionException e) { throw compositionFailure(e); }
            catch(RuntimeException e) { throw e; }
            catch(Exception e) { throw new IllegalStateException("Synthetic transaction encoding failed",e); }
        }
        throw new PaymentException(ApplicationFailure.TEMPORARILY_UNAVAILABLE_SAME_COMMAND);
    }
    private <T> T read(Session s,Work<T> work) {
        try(var c=ds.getConnection()) {
            c.setAutoCommit(false); c.setReadOnly(true); c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            var run=one(c,"SELECT * FROM simulation_runs WHERE tenant_id=? AND run_id=?",s.tenantId(),s.runId()); if(run==null) fail(ApplicationFailure.RESOURCE_NOT_FOUND);
            T result=work.run(new Tx(c,s,instant(run,"virtual_time"))); c.commit(); return result;
        } catch(ApplicationException e) { throw e; } catch(CompositionException e) { throw compositionFailure(e); } catch(SQLException e) { throw new PaymentException(ApplicationFailure.TEMPORARILY_UNAVAILABLE_SAME_COMMAND); } catch(Exception e) { throw new IllegalStateException("Synthetic read failed",e); }
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
        if(!Objects.equals(a.get("subject_id"),tx.s.principalId())) fail(ApplicationFailure.RESOURCE_NOT_FOUND);
        var o=operationById(tx,(UUID)a.get("operation_id")); var attempt=attemptById(tx,(UUID)o.get("attempt_id")); intent(tx,(UUID)attempt.get("intent_id")); grantAllowed(tx.s,text(attempt,"grant_id")); return a;
    }
    private void authority(Tx tx,Map<String,Object> g,String subject,String merchant) {
        if(!Objects.equals(g.get("subject_id"),subject)||!Objects.equals(g.get("merchant_id"),merchant)) fail(ApplicationFailure.AUTHORITY_BINDING_INVALID);
        if(text(g,"state").equals("REVOKED")) fail(ApplicationFailure.AUTHORITY_REVOKED);
        if(tx.now.isBefore(instant(g,"valid_from"))||!tx.now.isBefore(instant(g,"expires_at"))) fail(ApplicationFailure.AUTHORITY_EXPIRED);
    }
    private static boolean externalProfile(Map<String,Object> intent) { return EXTERNAL_PROFILE.equals(intent.get("profile")); }
    private static boolean nativeProfile(Map<String,Object> intent) { return NATIVE_PROFILE.equals(intent.get("profile"))||externalProfile(intent); }
    private Ports protocols(Map<String,Object> intent) { return externalProfile(intent)?externalComposition:composition; }
    private NativeApplicationCoordinator coordinator(Map<String,Object> intent) { return externalProfile(intent)?externalCoordinator:nativeCoordinator; }
    private Scope scope(Tx tx) { return new Scope(tx.s.tenantId(),tx.s.runId()); }
    private void nativeCapabilities() {
        if(composition==null) fail(ApplicationFailure.UNSUPPORTED_CAPABILITY);
        try { composition.requireSupported(); } catch(CompositionException e) { throw compositionFailure(e); }
    }
    private void externalCapabilities() {
        if(externalComposition==null||externalRegistration==null) fail(ApplicationFailure.UNSUPPORTED_CAPABILITY);
        try { externalComposition.requireSupported(EXTERNAL_PROFILE); }
        catch(CompositionException error) { throw compositionFailure(error); }
    }
    private void nativeCapabilities(Map<String,Object> intent) { if(externalProfile(intent)) externalCapabilities(); else nativeCapabilities(); }
    private void validateRegistration(Tx tx,ObservedCheckout observation) {
        externalCapabilities();
        if(!externalRegistration.scope().equals(scope(tx))
                ||!externalRegistration.sourcePrincipalId().equals(observation.configuration().binding().principalId())
                ||!externalRegistration.configuration().equals(observation.configuration())) fail(ApplicationFailure.UNSUPPORTED_CAPABILITY);
        try {
            var resolved=externalRegistration.resolver().resolve(observation.configuration());
            if(resolved==null||!observation.configuration().equals(resolved.identity())) fail(ApplicationFailure.UNSUPPORTED_CAPABILITY);
            resolved.transport().describe().require(observation.configuration().transportProfile(),Set.of());
            if(!observation.configuration().encodingVersion().equals(resolved.transport().encodingVersion())) fail(ApplicationFailure.UNSUPPORTED_CAPABILITY);
        } catch(RuntimeException error) { throw new PaymentException(ApplicationFailure.UNSUPPORTED_CAPABILITY); }
    }
    private Provisioning provision(Tx tx) throws SQLException {
        return provision(tx,composition);
    }
    private Provisioning provision(Tx tx,Ports ports) throws SQLException {
        var provision=ports.provisioner().provision(scope(tx),tx.now);
        for(var key:provision.enrollments()) {
            if(!key.scope().equals(scope(tx))||key.revoked()) fail(ApplicationFailure.AUTHORITY_BINDING_INVALID);
            var old=one(tx.c,"SELECT * FROM native_trust_keys WHERE tenant_id=? AND run_id=? AND issuer=? AND kid=? AND purpose=?",tx.s.tenantId(),tx.s.runId(),key.issuer(),key.kid(),key.purpose());
            byte[] publicKey;
            try { publicKey=Base64.getUrlDecoder().decode(key.publicKeySpki()); } catch(IllegalArgumentException e) { fail(ApplicationFailure.AUTHORITY_BINDING_INVALID); return null; }
            if(old!=null) {
                if(!Arrays.equals(publicKey,(byte[])old.get("public_key"))||!key.validFrom().equals(instant(old,"valid_from"))||!key.validUntil().equals(instant(old,"expires_at"))||key.version()!=number(old,"version")||Boolean.TRUE.equals(old.get("revoked"))) fail(ApplicationFailure.AUTHORITY_BINDING_INVALID);
            } else update(tx.c,"INSERT INTO native_trust_keys VALUES(?,?,?,?,?,?,?,?,?,false)",tx.s.tenantId(),tx.s.runId(),key.issuer(),key.kid(),key.purpose(),publicKey,key.validFrom(),key.validUntil(),key.version());
        }
        return provision;
    }
    private KeyResolver keyResolver(Tx tx) {
        return keyResolver(tx,composition);
    }
    private KeyResolver keyResolver(Tx tx,Ports ports) {
        // Enrollment is payment authority data, read under the same run lock as revocation/admission.
        var enrolled=new ArrayList<TrustKey>();
        try {
            for(var key:rows(tx.c,"SELECT * FROM native_trust_keys WHERE tenant_id=? AND run_id=?",tx.s.tenantId(),tx.s.runId()))
                enrolled.add(new TrustKey(scope(tx),text(key,"issuer"),text(key,"kid"),text(key,"purpose"),
                    Base64.getUrlEncoder().withoutPadding().encodeToString((byte[])key.get("public_key")),
                    instant(key,"valid_from"),instant(key,"expires_at"),number(key,"version"),Boolean.TRUE.equals(key.get("revoked"))));
        } catch(SQLException e) { unavailable(); }
        var snapshot=new EnrollmentSnapshot(scope(tx),tx.now,enrolled);
        KeyResolver selected=ports.keyResolvers().bind(snapshot);
        if(selected==null) fail(ApplicationFailure.AUTHORITY_BINDING_INVALID);
        return (requestedScope,issuer,kid,purpose,now)->{
            if(!scope(tx).equals(requestedScope)||!tx.now.equals(now)) fail(ApplicationFailure.AUTHORITY_BINDING_INVALID);
            TrustKey authoritative=snapshot.keys().stream().filter(k->k.issuer().equals(issuer)&&k.kid().equals(kid)&&k.purpose().equals(purpose)).findFirst().orElse(null);
            if(authoritative==null) fail(ApplicationFailure.AUTHORITY_BINDING_INVALID);
            if(authoritative.revoked()) fail(ApplicationFailure.AUTHORITY_REVOKED);
            if(now.isBefore(authoritative.validFrom())||!now.isBefore(authoritative.validUntil())) fail(ApplicationFailure.AUTHORITY_EXPIRED);
            TrustKey resolved=selected.resolve(requestedScope,issuer,kid,purpose,now);
            // A replaceable resolver cannot enroll keys or relax the owner's current revocation policy.
            if(!authoritative.equals(resolved)) fail(ApplicationFailure.AUTHORITY_BINDING_INVALID);
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
        if(row==null) fail(ApplicationFailure.AUTHORITY_BINDING_INVALID);
        var stored=json.readValue(text(row,"checkout_json"),StoredCheckout.class);
        return coordinator(i).reloadCheckout(scope(tx),stored,text(q,"digest"));
    }
    private void approveNative(Tx tx,Map<String,Object> i,Map<String,Object> q,Map<String,Object> g,UUID attempt) throws Exception {
        nativeCapabilities(i);
        var coordinator=coordinator(i); var ports=protocols(i);
        var facts=checkoutFacts(tx,i,q); var checkout=nativeCheckout(tx,i,q);
        if(externalProfile(i)) verifyExternalBinding(tx,i,q,checkout);
        coordinator.validateCheckout(facts,checkout,keyResolver(tx,ports),tx.now);
        var provision=provision(tx,ports);
        Instant deadline=Collections.min(List.of(tx.now.plusSeconds(300),instant(g,"expires_at"),facts.expiresAt()));
        var context=new AuthorityContext(facts,checkout,tx.s.principalId(),text(g,"grant_id"),number(g,"version"),provision.tapSigner().issuer(),"merchant-demo","processor-demo",UUID.randomUUID().toString(),UUID.randomUUID().toString(),tx.now,deadline);
        var stored=coordinator.issueAndRetainAuthority(new IssuanceRequest(context,provision.tapSigner()));
        String digest=coordinator.authorityDigest(stored);
        update(tx.c,"INSERT INTO native_authorities VALUES(?,?,?,?,?,?,?,?,?,?,?)",tx.s.tenantId(),tx.s.runId(),attempt,i.get("intent_id"),q.get("revision"),tx.s.principalId(),g.get("grant_id"),g.get("version"),encode(stored),digest,deadline);
        for(String role:List.of("MERCHANT","CREDENTIAL_PROVIDER","PAYMENT_PROCESSOR")) {
            boolean merchant=role.equals("MERCHANT");
            update(tx.c,"INSERT INTO native_challenges VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",tx.s.tenantId(),tx.s.runId(),role,merchant?context.checkoutNonce():context.paymentNonce(),i.get("intent_id"),q.get("revision"),tx.s.principalId(),g.get("grant_id"),g.get("version"),context.issuer(),merchant?context.checkoutAudience():context.paymentAudience(),q.get("digest"),deadline,attempt);
        }
        // Reload exact staged bytes. Signing/issuer success never substitutes for independent verification.
        var reloaded=coordinator.reloadAuthority(scope(tx),stored);
        var verified=coordinator.verifyAuthority(context,reloaded,keyResolver(tx,ports),tx.now);
        for(var role:verified.authorizations()) update(tx.c,"INSERT INTO native_replay_identities VALUES(?,?,?,?,?,?)",tx.s.tenantId(),role.role(),role.issuer(),role.jwtId(),tx.s.runId(),attempt);
        update(tx.c,"UPDATE native_authorities SET expires_at=? WHERE tenant_id=? AND run_id=? AND attempt_id=?",verified.expiresAt(),tx.s.tenantId(),tx.s.runId(),attempt);
    }
    private void verifyNative(Tx tx,Map<String,Object> i,Map<String,Object> a,Map<String,Object> g) throws Exception {
        nativeCapabilities(i);
        var coordinator=coordinator(i); var ports=protocols(i);
        if(!eligible(tx,g,a,i)) fail(ApplicationFailure.AUTHORITY_REVOKED);
        var q=quote(tx,i);
        if(number(a,"checkout_revision")!=number(q,"revision")) fail(ApplicationFailure.AUTHORITY_BINDING_INVALID);
        var row=one(tx.c,"SELECT * FROM native_authorities WHERE tenant_id=? AND run_id=? AND attempt_id=?",tx.s.tenantId(),tx.s.runId(),a.get("attempt_id"));
        if(row==null||!Objects.equals(row.get("intent_id"),i.get("intent_id"))||number(row,"revision")!=number(q,"revision")||!Objects.equals(row.get("subject_id"),a.get("subject_id"))||!Objects.equals(row.get("grant_id"),a.get("grant_id"))||number(row,"grant_version")!=number(a,"grant_version")) fail(ApplicationFailure.AUTHORITY_BINDING_INVALID);
        if(!tx.now.isBefore(instant(row,"expires_at"))) fail(ApplicationFailure.AUTHORITY_EXPIRED);
        var stored=json.readValue(text(row,"authority_json"),StoredAuthority.class);
        var facts=checkoutFacts(tx,i,q); var checkout=nativeCheckout(tx,i,q);
        if(externalProfile(i)) verifyExternalBinding(tx,i,q,checkout);
        coordinator.validateCheckout(facts,checkout,keyResolver(tx,ports),tx.now);
        var context=new AuthorityContext(facts,checkout,text(a,"subject_id"),text(a,"grant_id"),number(a,"grant_version"),stored.issuer(),stored.checkoutAudience(),stored.paymentAudience(),stored.checkoutNonce(),stored.paymentNonce(),Instant.parse(stored.issuedAt()),Instant.parse(stored.expiresAt()));
        var issued=coordinator.reloadAuthority(scope(tx),stored);
        coordinator.validateAuthorityDigest(stored,text(row,"authority_digest"));
        var verified=coordinator.verifyAuthority(context,issued,keyResolver(tx,ports),tx.now);
        if(!verified.expiresAt().equals(instant(row,"expires_at"))) fail(ApplicationFailure.AUTHORITY_BINDING_INVALID);
        for(var role:verified.authorizations()) {
            var used=one(tx.c,"SELECT * FROM native_replay_identities WHERE tenant_id=? AND role=? AND issuer=? AND jti=?",tx.s.tenantId(),role.role(),role.issuer(),role.jwtId());
            boolean merchant=role.role().equals("MERCHANT");
            var challenge=one(tx.c,"SELECT * FROM native_challenges WHERE tenant_id=? AND run_id=? AND role=? AND nonce=?",tx.s.tenantId(),tx.s.runId(),role.role(),merchant?context.checkoutNonce():context.paymentNonce());
            if(used==null||!Objects.equals(used.get("run_id"),tx.s.runId())||!Objects.equals(used.get("attempt_id"),a.get("attempt_id"))||challenge==null||!Objects.equals(challenge.get("attempt_id"),a.get("attempt_id"))||!Objects.equals(challenge.get("intent_id"),i.get("intent_id"))||number(challenge,"revision")!=number(q,"revision")||!Objects.equals(challenge.get("subject_id"),a.get("subject_id"))||!Objects.equals(challenge.get("grant_id"),a.get("grant_id"))||number(challenge,"grant_version")!=number(a,"grant_version")||!Objects.equals(challenge.get("issuer"),context.issuer())||!Objects.equals(challenge.get("audience"),merchant?context.checkoutAudience():context.paymentAudience())||!Objects.equals(challenge.get("checkout_digest"),q.get("digest"))||!tx.now.isBefore(instant(challenge,"expires_at"))) fail(ApplicationFailure.AUTHORITY_BINDING_INVALID);
        }
    }
    private void verifyExternalBinding(Tx tx,Map<String,Object> intent,Map<String,Object> quote,FrozenCheckout checkout) throws Exception {
        var binding=one(tx.c,"SELECT * FROM external_checkout_bindings WHERE tenant_id=? AND run_id=? AND intent_id=?",tx.s.tenantId(),tx.s.runId(),intent.get("intent_id"));
        if(binding==null||!"local-synthetic-attestation-0.1".equals(text(binding,"attestation_provenance"))) fail(ApplicationFailure.AUTHORITY_BINDING_INVALID);
        var observation=externalSource.load(tx.c,scope(tx),text(binding,"principal_id"),uuid(binding,"purchase_id"),(int)number(binding,"purchase_revision"),uuid(binding,"response_reference"));
        validateRegistration(tx,observation);
        if(!observation.commandId().equals(uuid(binding,"command_id"))||!observation.observedAt().equals(instant(binding,"observed_at"))
                ||!observation.configuration().equals(json.readValue(text(binding,"configuration_json"),io.agenticpayments.application.CheckoutConfigurationResolver.ConfigurationIdentity.class))
                ||!io.agenticpayments.composition.ArtifactIntegrity.digest(observation.bytes()).equals(text(binding,"raw_digest"))
                ||!instant(binding,"expires_at").equals(instant(quote,"expires_at"))
                ||!observation.configuration().binding().merchantId().equals(text(intent,"merchant_id"))) fail(ApplicationFailure.AUTHORITY_BINDING_INVALID);
        var terms=externalCoordinator.inspectObserved(observation,tx.now);
        externalCoordinator.validateObservedBytes(observation,terms,checkoutFacts(tx,intent,quote),checkout);
    }
    private boolean nativeGate(Tx tx,Map<String,Object> i,Map<String,Object> a,Map<String,Object> g) throws Exception {
        try { verifyNative(tx,i,a,g); return true; }
        catch(CompositionException e) { var failure=compositionFailure(e); if(failure.failure().category()==ApplicationFailure.Category.UNAVAILABLE || failure.failure().category()==ApplicationFailure.Category.INTERNAL) throw failure; terminate(tx,i,a,failure.failure().category()==ApplicationFailure.Category.EXPIRED?"EXPIRED":"CANCELLED"); return false; }
        catch(ApplicationException e) { if(e.failure().category()==ApplicationFailure.Category.UNAVAILABLE || e.failure().category()==ApplicationFailure.Category.INTERNAL) throw e; terminate(tx,i,a,e.failure().category()==ApplicationFailure.Category.EXPIRED?"EXPIRED":"CANCELLED"); return false; }
    }
    private static PaymentException compositionFailure(CompositionException error) {
        return switch(error.failure()) {
            case UNSUPPORTED_CAPABILITY -> new PaymentException(ApplicationFailure.UNSUPPORTED_CAPABILITY);
            case EXPIRED_AUTHORITY -> new PaymentException(ApplicationFailure.AUTHORITY_EXPIRED);
            case SIGNING_UNAVAILABLE, CRYPTO_UNAVAILABLE, ARTIFACT_UNAVAILABLE -> new PaymentException(ApplicationFailure.TEMPORARILY_UNAVAILABLE_SAME_COMMAND);
            default -> new PaymentException(ApplicationFailure.AUTHORITY_BINDING_INVALID);
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
    private static Map<String,Object> required(Map<String,Object> r) { if(r==null) fail(ApplicationFailure.RESOURCE_NOT_FOUND); return r; }
    private static void role(Session s,String role) { if(s==null) fail(ApplicationFailure.UNAUTHENTICATED); if(!s.roles().contains(role)) fail(ApplicationFailure.FORBIDDEN); }
    private static void merchant(Session s,String merchant) { if(!s.allowedMerchants().contains(merchant)||!"merchant-demo".equals(merchant)) fail(ApplicationFailure.RESOURCE_NOT_FOUND); }
    private static void grantAllowed(Session s,String grant) { if(!s.allowedGrants().contains(grant)) fail(ApplicationFailure.RESOURCE_NOT_FOUND); }
    private static void validate(int version) { if(version<1) fail(ApplicationFailure.INVALID_REQUEST); }
    private static void version(Map<String,Object> row,int expected) { if(number(row,"version")!=expected) fail(ApplicationFailure.VERSION_CONFLICT); }
    private static void state(Map<String,Object> i,String... allowed) { if(!List.of(allowed).contains(i.get("state"))) fail(ApplicationFailure.INVALID_STATE); }
    private static Map<String,Object> money(String currency,long minor) { return map("currency",currency,"minorUnits",Long.toString(minor)); }
    private static Result result(int status,Map<String,Object> body) { var out=map("contractVersion",PaymentRules.CONTRACT,"correlationId",UUID.randomUUID().toString()); out.putAll(body); return new Result(status,out,false); }
    private static void fail(ApplicationFailure failure) { throw new PaymentException(failure); }
    private static void unavailable() { throw new PaymentException(ApplicationFailure.TEMPORARILY_UNAVAILABLE_SAME_COMMAND); }
    private void capabilities() {
        if(!CapabilityOffer.localFake().equals(adapter.capabilities())) fail(ApplicationFailure.UNSUPPORTED_CAPABILITY);
    }
    private void owner(Map<String,Object> run) { if("REPLAY".equals(run.get("execution_mode"))&&!Objects.equals(run.get("replay_owner"),replayOwner))fail(ApplicationFailure.FORBIDDEN); }
    private void activity(Tx tx,long[] values) throws SQLException {
        values[0]=number(one(tx.c,"SELECT COALESCE(max(step_sequence),0) AS value FROM audit_events WHERE tenant_id=? AND run_id=?",tx.s.tenantId(),tx.s.runId()),"value");
        values[1]=number(one(tx.c,"SELECT count(*) AS value FROM outbox WHERE tenant_id=? AND run_id=? AND done",tx.s.tenantId(),tx.s.runId()),"value");
        values[2]=number(one(tx.c,"SELECT count(*) AS value FROM operation_evidence WHERE tenant_id=? AND run_id=?",tx.s.tenantId(),tx.s.runId()),"value");
        values[3]=number(one(tx.c,"SELECT (SELECT count(*) FROM conflict_evidence WHERE tenant_id=? AND run_id=?)+(SELECT count(*) FROM disputed_exposure WHERE tenant_id=? AND run_id=?) AS value",tx.s.tenantId(),tx.s.runId(),tx.s.tenantId(),tx.s.runId()),"value");
    }
    private WorkerReceipt receipt(Tx tx,long[] before,Selection selected) throws SQLException {
        Session s=tx.s;long[] after=new long[4];activity(tx,after);boolean progress=!Arrays.equals(before,after);
        var extent=one(tx.c,"SELECT COALESCE(min(step_sequence),0) AS first,COALESCE(max(step_sequence),0) AS last FROM audit_events WHERE tenant_id=? AND run_id=? AND step_sequence>?",s.tenantId(),s.runId(),before[0]);
        return new WorkerReceipt(selected.operationId(),selected.kind()==null?null:selected.kind().name(),selected.fence(),number(extent,"first"),number(extent,"last"),progress);
    }
}
