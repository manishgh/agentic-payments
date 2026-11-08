package io.agenticpayments.application;

import io.agenticpayments.application.PaymentCommandTransactions.*;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.domain.PaymentRules;
import io.agenticpayments.sdk.CapabilityOffer;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

/** Independent policy evidence; neither owner uses JDBC, JSON, nor a native protocol engine. */
class ApprovePaymentTest {
    private static final Instant NOW=Instant.parse("2026-11-06T10:00:00Z");
    private static final String KEY="approval-policy-command-0001";
    private static final String NATIVE="local-ap2-acp-single-tender-0.1";
    private static final String EXTERNAL="local-external-ap2-acp-single-tender-0.1";

    private static final class Facts {
        final UUID id=UUID.randomUUID();
        final Session session=new Session("approval-policy",UUID.randomUUID(),"customer-demo",Set.of("approval:write"),Set.of("merchant-demo"),Set.of("fixture-grant-1"));
        ApprovalIntent intent=new ApprovalIntent(id,"merchant-demo",PaymentRules.PROFILE,"QUOTED",2,1);
        ApprovalCheckout checkout=new ApprovalCheckout(1,PaymentRules.sha256("exact-checkout"),"EUR",1250,NOW.plusSeconds(300));
        ApprovalGrant grant=new ApprovalGrant("fixture-grant-1","customer-demo","merchant-demo","ACTIVE",7,NOW,NOW.plusSeconds(600),"budget-eur");
        ApprovalBudget budget=new ApprovalBudget("budget-eur","EUR",false,100,150,1500);
        Instant now=NOW;
        RuntimeException nativeFailure,acceptFailure;
        int runs,nativeCalls,digests;
        final List<String> observations=new ArrayList<>();
        ApprovalTransaction escaped;
        void profile(String profile){intent=new ApprovalIntent(id,intent.merchantId(),profile,intent.state(),intent.version(),intent.checkoutRevision());}
        void state(String state,long version){intent=new ApprovalIntent(id,intent.merchantId(),intent.profile(),state,version,intent.checkoutRevision());}
        void grant(String subject,String merchant,String state,Instant from,Instant until){grant=new ApprovalGrant(grant.id(),subject,merchant,state,grant.version(),from,until,grant.budgetId());}
        ApproveCommand command(){return new ApproveCommand(2,1,checkout.digest(),grant.id());}
    }

    private interface Owner extends PaymentCommandTransactions {
        Facts facts();
        List<ApprovalWrite> attempts();
        long held();
        int reservations();
        int authorities();
        int acceptedCount();
        String state();
        long version();
        default String requestDigest(ExecuteCommand ignored){throw new AssertionError("approval must not call execute encoding");}
        default Result run(Session session,Work ignored){throw new AssertionError("approval must not open execute work");}
        default String approvalRequestDigest(ApproveCommand command){facts().digests++;return digest(command);}
    }
    private static String digest(ApproveCommand c){return PaymentRules.sha256(c.expectedVersion()+"|"+c.checkoutRevision()+"|"+c.checkoutDigest()+"|"+c.authorityReference());}
    private static List<String> journalKey(CommandIdentity id){return List.of(id.route(),id.resource(),id.key());}

    /** An implementation using the contract's typed facts and writes directly. */
    private static final class Direct implements Owner {
        final Facts facts=new Facts();
        final List<ApprovalWrite> attempts=new ArrayList<>();
        final Map<CommandIdentity,AcceptedCommand> journal=new HashMap<>();
        final Map<AcceptedCommand,Result> responses=new IdentityHashMap<>();
        long held=100,version=2;int reservations,authorities;String state="QUOTED";
        public Facts facts(){return facts;}
        public List<ApprovalWrite> attempts(){return List.copyOf(attempts);}
        public long held(){return held;}
        public int reservations(){return reservations;}
        public int authorities(){return authorities;}
        public int acceptedCount(){return journal.size();}
        public String state(){return state;}
        public long version(){return version;}
        public Result runApproval(Session session,ApprovalWork work){
            facts.runs++;
            var staged=new ArrayList<ApprovalWrite>();var stagedJournal=new HashMap<CommandIdentity,AcceptedCommand>();var stagedResponses=new IdentityHashMap<AcceptedCommand,Result>();
            int[] authority={0},reservation={0};boolean[] active={true};Thread thread=Thread.currentThread();
            ApprovalTransaction tx=new ApprovalTransaction(){
                private void check(){if(!active[0]||thread!=Thread.currentThread())throw new IllegalStateException("closed approval transaction");}
                public Instant now(){check();return facts.now;}
                public ApprovalIntent intent(UUID id){check();assertEquals(facts.id,id);facts.observations.add("intent");return facts.intent;}
                public ApprovalCheckout checkout(ApprovalIntent i){check();assertSame(facts.intent,i);facts.observations.add("checkout");return facts.checkout;}
                public ApprovalGrant grant(String id){check();assertEquals(facts.grant.id(),id);facts.observations.add("grant");return facts.grant;}
                public ApprovalBudget budget(String id){check();assertEquals(facts.budget.id(),id);facts.observations.add("budget");return facts.budget;}
                public Optional<AcceptedCommand> accepted(CommandIdentity id){check();facts.observations.add("accepted");return Optional.ofNullable(journal.get(id));}
                public Result replay(AcceptedCommand previous){check();facts.observations.add("replay");return responses.get(previous);}
                public void stageAttempt(ApprovalWrite w){check();facts.observations.add("attempt");staged.add(w);}
                public void issueAndVerifyNative(ApprovalIntent i,ApprovalCheckout q,ApprovalGrant g,ApprovalWrite w){check();assertSame(facts.intent,i);assertSame(facts.checkout,q);assertSame(facts.grant,g);assertEquals(List.of(w),staged);facts.observations.add("native");facts.nativeCalls++;authority[0]++;if(facts.nativeFailure!=null)throw facts.nativeFailure;}
                public void reserveAndApprove(ApprovalWrite w){check();assertEquals(List.of(w),staged);facts.observations.add("reserve");reservation[0]++;}
                public void accept(CommandIdentity id,String hash,Result result){check();facts.observations.add("accept");assertEquals(1,reservation[0]);if(facts.acceptFailure!=null)throw facts.acceptFailure;var previous=new AcceptedCommand(hash);stagedJournal.put(id,previous);stagedResponses.put(previous,result);}
            };
            facts.escaped=tx;
            try {
                Result result=work.run(tx);
                attempts.addAll(staged);journal.putAll(stagedJournal);responses.putAll(stagedResponses);authorities+=authority[0];reservations+=reservation[0];
                if(reservation[0]!=0){held+=staged.getFirst().minorUnits();state="APPROVED";version++;}
                return result;
            } catch(RuntimeException e){throw e;}catch(Exception e){throw new AssertionError(e);}finally{active[0]=false;}
        }
    }

    /** Deliberately foreign storage API: row vectors, string journal keys, envelopes and copy-on-commit. */
    private static final class ForeignLedger {
        record Envelope(String hash,int status,Map<String,Object> payload) {}
        static final class Revision {
            final List<List<Object>> attempts=new ArrayList<>();
            final Map<List<String>,Envelope> commands=new HashMap<>();
            final Map<String,List<Object>> rows=new HashMap<>();
            long held=100,version=2;int reservations,authorities;String state="QUOTED";
            Revision copy(){var next=new Revision();next.attempts.addAll(attempts);next.commands.putAll(commands);next.rows.putAll(rows);next.held=held;next.version=version;next.reservations=reservations;next.authorities=authorities;next.state=state;return next;}
        }
        Revision durable=new Revision();
        <T> T edit(Function<Revision,T> operation){Revision candidate=durable.copy();T result=operation.apply(candidate);durable=candidate;return result;}
    }

    /** A real translation adapter: the foreign backend never receives a payment transaction callback or write. */
    private static final class Adapted implements Owner {
        final Facts facts=new Facts();final ForeignLedger backend=new ForeignLedger();
        public Facts facts(){return facts;}
        public List<ApprovalWrite> attempts(){return backend.durable.attempts.stream().map(Adapted::write).toList();}
        public long held(){return backend.durable.held;}
        public int reservations(){return backend.durable.reservations;}
        public int authorities(){return backend.durable.authorities;}
        public int acceptedCount(){return backend.durable.commands.size();}
        public String state(){return backend.durable.state;}
        public long version(){return backend.durable.version;}
        private static List<Object> row(ApprovalWrite w){return List.of(w.intentId(),w.expectedVersion(),w.attemptId(),w.checkoutRevision(),w.grantId(),w.grantVersion(),w.subjectId(),w.budgetId(),w.minorUnits());}
        private static ApprovalWrite write(List<Object> row){return new ApprovalWrite((UUID)row.get(0),(Long)row.get(1),(UUID)row.get(2),(Integer)row.get(3),(String)row.get(4),(Long)row.get(5),(String)row.get(6),(String)row.get(7),(Long)row.get(8));}
        public Result runApproval(Session session,ApprovalWork work){
            facts.runs++;
            // Tests seed foreign rows as an existing backend would; reads reconstruct the port DTOs.
            var i=facts.intent;var q=facts.checkout;var g=facts.grant;var b=facts.budget;
            backend.durable.rows.put("purchase",List.of(i.id(),i.merchantId(),i.profile(),i.state(),i.version(),i.checkoutRevision()));
            backend.durable.rows.put("offer",List.of(q.revision(),q.digest(),q.currency(),q.minorUnits(),q.expiresAt().toEpochMilli()));
            backend.durable.rows.put("authority",List.of(g.id(),g.subjectId(),g.merchantId(),g.state(),g.version(),g.validFrom().toEpochMilli(),g.expiresAt().toEpochMilli(),g.budgetId()));
            backend.durable.rows.put("limit",List.of(b.id(),b.currency(),b.frozen(),b.held(),b.consumed(),b.limitMinor()));
            // The backend owns commit/rollback; the adapter translates its callback and lifetime.
            return backend.edit(candidate->{
                boolean[] active={true};Thread thread=Thread.currentThread();
                Map<AcceptedCommand,ForeignLedger.Envelope> envelopes=new IdentityHashMap<>();
                ApprovalTransaction tx=new ApprovalTransaction(){
                    private void check(){if(!active[0]||thread!=Thread.currentThread())throw new IllegalStateException("closed adapted transaction");}
                    public Instant now(){check();return facts.now;}
                    public ApprovalIntent intent(UUID id){check();facts.observations.add("intent");var r=candidate.rows.get("purchase");assertEquals(r.get(0),id);return new ApprovalIntent((UUID)r.get(0),(String)r.get(1),(String)r.get(2),(String)r.get(3),(Long)r.get(4),(Integer)r.get(5));}
                    public ApprovalCheckout checkout(ApprovalIntent intent){check();assertEquals(facts.intent,intent);facts.observations.add("checkout");var r=candidate.rows.get("offer");return new ApprovalCheckout((Integer)r.get(0),(String)r.get(1),(String)r.get(2),(Long)r.get(3),Instant.ofEpochMilli((Long)r.get(4)));}
                    public ApprovalGrant grant(String id){check();facts.observations.add("grant");var r=candidate.rows.get("authority");assertEquals(r.get(0),id);return new ApprovalGrant((String)r.get(0),(String)r.get(1),(String)r.get(2),(String)r.get(3),(Long)r.get(4),Instant.ofEpochMilli((Long)r.get(5)),Instant.ofEpochMilli((Long)r.get(6)),(String)r.get(7));}
                    public ApprovalBudget budget(String id){check();facts.observations.add("budget");var r=candidate.rows.get("limit");assertEquals(r.get(0),id);return new ApprovalBudget((String)r.get(0),(String)r.get(1),(Boolean)r.get(2),(Long)r.get(3),(Long)r.get(4),(Long)r.get(5));}
                    public Optional<AcceptedCommand> accepted(CommandIdentity id){check();facts.observations.add("accepted");var envelope=candidate.commands.get(journalKey(id));if(envelope==null)return Optional.empty();var accepted=new AcceptedCommand(envelope.hash());envelopes.put(accepted,envelope);return Optional.of(accepted);}
                    public Result replay(AcceptedCommand accepted){check();facts.observations.add("replay");var envelope=envelopes.get(accepted);return new Result(envelope.status(),envelope.payload(),false);}
                    public void stageAttempt(ApprovalWrite w){check();facts.observations.add("attempt");candidate.attempts.add(row(w));}
                    public void issueAndVerifyNative(ApprovalIntent i,ApprovalCheckout q,ApprovalGrant g,ApprovalWrite w){check();assertEquals(facts.intent,i);assertEquals(facts.checkout,q);assertEquals(facts.grant,g);assertEquals(row(w),candidate.attempts.getLast());facts.observations.add("native");facts.nativeCalls++;candidate.authorities++;if(facts.nativeFailure!=null)throw facts.nativeFailure;}
                    public void reserveAndApprove(ApprovalWrite w){check();assertEquals(row(w),candidate.attempts.getLast());facts.observations.add("reserve");candidate.held+=w.minorUnits();candidate.reservations++;candidate.state="APPROVED";candidate.version++;}
                    public void accept(CommandIdentity id,String hash,Result result){check();facts.observations.add("accept");assertEquals("APPROVED",candidate.state);if(facts.acceptFailure!=null)throw facts.acceptFailure;candidate.commands.put(journalKey(id),new ForeignLedger.Envelope(hash,result.status(),result.body()));}
                };
                facts.escaped=tx;
                try{return work.run(tx);}catch(RuntimeException e){throw e;}catch(Exception e){throw new AssertionError(e);}finally{active[0]=false;}
            });
        }
    }

    /** Ordinary delegation composes without introducing another owner or transaction. */
    private static final class Wrapper implements PaymentCommandTransactions {
        final PaymentCommandTransactions inner;final String name;final List<String> events;
        Wrapper(PaymentCommandTransactions inner,String name,List<String> events){this.inner=inner;this.name=name;this.events=events;}
        public String requestDigest(ExecuteCommand c){return inner.requestDigest(c);}
        public Result run(Session s,Work w){return inner.run(s,w);}
        public String approvalRequestDigest(ApproveCommand c){events.add(name+":digest");return inner.approvalRequestDigest(c);}
        public Result runApproval(Session s,ApprovalWork w){events.add(name+":enter");try{return inner.runApproval(s,w);}finally{events.add(name+":exit");}}
    }
    private record Fixture(Owner owner,PaymentCommandTransactions transactions,List<String> decorators) {
        Facts facts(){return owner.facts();}
        ApprovePayment useCase(){return new ApprovePayment(transactions,CapabilityOffer::localFake);}
        Result approve(){return useCase().approve(facts().session,facts().id,KEY,facts().command());}
    }
    private static Fixture fixture(boolean adapted,boolean decorated){
        Owner owner=adapted?new Adapted():new Direct();List<String> events=new ArrayList<>();PaymentCommandTransactions tx=owner;
        if(decorated)tx=new Wrapper(new Wrapper(tx,"inner",events),"outer",events);
        return new Fixture(owner,tx,events);
    }
    private static void unchanged(Fixture f){assertTrue(f.owner.attempts().isEmpty());assertEquals(0,f.owner.reservations());assertEquals(0,f.owner.authorities());assertEquals(0,f.owner.acceptedCount());assertEquals(100,f.owner.held());assertEquals("QUOTED",f.owner.state());assertEquals(2,f.owner.version());}
    private static PaymentException rejects(Fixture f,String code){var error=assertThrows(PaymentException.class,f::approve);assertEquals(code,error.code());unchanged(f);return error;}

    @Test void directAdaptedAndDecoratedOwnersCommitOneBoundApprovalForEveryPersistedProfile(){
        for(boolean adapted:List.of(false,true))for(boolean decorated:List.of(false,true))for(String profile:List.of(PaymentRules.PROFILE,NATIVE,EXTERNAL)){
            Fixture f=fixture(adapted,decorated);f.facts().profile(profile);Result result=f.approve();
            assertEquals(200,result.status());assertFalse(result.replayed());assertEquals("APPROVED",result.body().get("state"));assertEquals(3,((Number)result.body().get("version")).longValue());
            assertEquals(f.facts().id,result.body().get("intentId"));assertEquals("simulator-core",result.body().get("executionOwner"));
            assertEquals(profile.equals(PaymentRules.PROFILE)?"local-fake":"ap2-v0.2-direct-tap-synthetic-0.1",result.body().get("authorityProfile"));
            assertEquals(7,((Number)result.body().get("authorityVersion")).longValue());assertEquals(Map.of("currency","EUR","minorUnits","1250"),result.body().get("reserved"));
            assertEquals(Set.of("contractVersion","correlationId","intentId","state","version","attemptId","executionOwner","authorityProfile","authorityVersion","reserved"),result.body().keySet());
            assertEquals(1,f.owner.attempts().size());ApprovalWrite write=f.owner.attempts().getFirst();
            assertEquals(new ApprovalWrite(f.facts().id,2,(UUID)result.body().get("attemptId"),1,"fixture-grant-1",7,"customer-demo","budget-eur",1250),write);
            assertEquals(1,f.owner.reservations());assertEquals(1,f.owner.acceptedCount());assertEquals(1350,f.owner.held());assertEquals("APPROVED",f.owner.state());assertEquals(3,f.owner.version());
            assertEquals(profile.equals(PaymentRules.PROFILE)?0:1,f.owner.authorities());assertEquals(f.owner.authorities(),f.facts().nativeCalls);assertEquals(1,f.facts().runs);assertEquals(1,f.facts().digests);
            if(decorated)assertEquals(List.of("outer:digest","inner:digest","outer:enter","inner:enter","inner:exit","outer:exit"),f.decorators());
        }
    }

    @Test void acceptedReplayRetainsHistoricalApprovalAndSkipsChangedFactsButCurrentAccessStillApplies(){
        for(boolean adapted:List.of(false,true))for(boolean decorated:List.of(false,true)){
            Fixture f=fixture(adapted,decorated);f.facts().profile(NATIVE);Result first=f.approve();
            f.facts().state("UNKNOWN",90);f.facts().now=NOW.plusSeconds(900);f.facts().grant("wrong-subject","wrong-merchant","REVOKED",NOW,NOW);
            f.facts().budget=new ApprovalBudget("budget-eur","USD",true,9999,9999,1);f.facts().nativeFailure=new PaymentException(ApplicationFailure.TEMPORARILY_UNAVAILABLE_SAME_COMMAND);
            f.facts().observations.clear();Result replay=f.approve();assertTrue(replay.replayed());assertEquals(first.status(),replay.status());assertEquals(first.body(),replay.body());
            assertEquals(List.of("intent","accepted","replay"),f.facts().observations);assertEquals(1,f.owner.attempts().size());assertEquals(1,f.facts().nativeCalls);assertEquals(1350,f.owner.held());
            Session allowed=f.facts().session;
            for(Session denied:List.of(new Session(allowed.tenantId(),allowed.runId(),allowed.principalId(),allowed.roles(),Set.of(),allowed.allowedGrants()),new Session(allowed.tenantId(),allowed.runId(),allowed.principalId(),allowed.roles(),allowed.allowedMerchants(),Set.of()))){
                f.facts().observations.clear();assertEquals("RESOURCE_NOT_FOUND",assertThrows(PaymentException.class,()->f.useCase().approve(denied,f.facts().id,KEY,f.facts().command())).code());assertFalse(f.facts().observations.contains("accepted"));
            }
            ApproveCommand changed=new ApproveCommand(3,1,f.facts().checkout.digest(),"fixture-grant-1");
            assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(PaymentException.class,()->f.useCase().approve(allowed,f.facts().id,KEY,changed)).code());assertEquals(1,f.owner.acceptedCount());
        }
    }

    @Test void policyFailuresAndTheirPrecedenceAreIndependentOfStorageAndDelegation(){
        for(boolean adapted:List.of(false,true))for(boolean decorated:List.of(false,true)){
            Fixture version=fixture(adapted,decorated);version.facts().state("DRAFT",1);rejects(version,"VERSION_CONFLICT");
            Fixture state=fixture(adapted,decorated);state.facts().state("DRAFT",2);rejects(state,"INVALID_STATE");
            Fixture subject=fixture(adapted,decorated);subject.facts().grant("other","merchant-demo","REVOKED",NOW,NOW);rejects(subject,"AUTHORITY_BINDING_INVALID");
            Fixture merchant=fixture(adapted,decorated);merchant.facts().grant("customer-demo","other","REVOKED",NOW,NOW);rejects(merchant,"AUTHORITY_BINDING_INVALID");
            Fixture revoked=fixture(adapted,decorated);revoked.facts().grant("customer-demo","merchant-demo","REVOKED",NOW,NOW);rejects(revoked,"AUTHORITY_REVOKED");
            Fixture future=fixture(adapted,decorated);future.facts().grant("customer-demo","merchant-demo","ACTIVE",NOW.plusSeconds(1),NOW.plusSeconds(2));rejects(future,"AUTHORITY_EXPIRED");
            Fixture expired=fixture(adapted,decorated);expired.facts().grant("customer-demo","merchant-demo","ACTIVE",NOW.minusSeconds(1),NOW);rejects(expired,"AUTHORITY_EXPIRED");
            Fixture quote=fixture(adapted,decorated);quote.facts().checkout=new ApprovalCheckout(2,"changed","EUR",1250,NOW);rejects(quote,"AUTHORITY_EXPIRED");assertFalse(quote.facts().observations.contains("budget"));
            for(boolean revision:List.of(false,true)){
                Fixture changed=fixture(adapted,decorated);ApproveCommand c=changed.facts().command();var stale=new ApproveCommand(2,revision?2:1,revision?c.checkoutDigest():"changed",c.authorityReference());
                assertEquals("CHECKOUT_CHANGED",assertThrows(PaymentException.class,()->changed.useCase().approve(changed.facts().session,changed.facts().id,KEY,stale)).code());unchanged(changed);assertFalse(changed.facts().observations.contains("budget"));
            }
            Fixture currency=fixture(adapted,decorated);currency.facts().budget=new ApprovalBudget("budget-eur","USD",true,100,150,1);rejects(currency,"AUTHORITY_BINDING_INVALID");
            Fixture frozen=fixture(adapted,decorated);frozen.facts().budget=new ApprovalBudget("budget-eur","EUR",true,100,150,1500);rejects(frozen,"BUDGET_EXCEEDED");
            Fixture over=fixture(adapted,decorated);over.facts().budget=new ApprovalBudget("budget-eur","EUR",false,100,151,1500);rejects(over,"BUDGET_EXCEEDED");
        }
    }

    @Test void exactBudgetBoundaryAndLegacyNonRevokedStateRetainExistingPolicy(){
        for(boolean adapted:List.of(false,true)){
            Fixture f=fixture(adapted,true);f.facts().grant("customer-demo","merchant-demo","SUSPENDED",NOW,NOW.plusSeconds(1));
            assertEquals(200,f.approve().status());assertEquals(1350,f.owner.held());assertEquals(1,f.owner.reservations());
            assertEquals(1500,f.owner.held()+f.facts().budget.consumed());
        }
    }

    @Test void adaptedAndDirectOwnersUseCurrentExactMoneyAndGrantVersionRatherThanFixtureDefaults(){
        for(boolean adapted:List.of(false,true)){
            Fixture f=fixture(adapted,true);f.facts().profile(EXTERNAL);
            f.facts().checkout=new ApprovalCheckout(1,PaymentRules.sha256("different-checkout"),"USD",2719,NOW.plusSeconds(30));
            f.facts().grant=new ApprovalGrant("fixture-grant-1","customer-demo","merchant-demo","ACTIVE",19,NOW,NOW.plusSeconds(40),"budget-usd");
            f.facts().budget=new ApprovalBudget("budget-usd","USD",false,100,150,2969);
            Result result=f.approve();assertEquals(Map.of("currency","USD","minorUnits","2719"),result.body().get("reserved"));
            assertEquals(19,((Number)result.body().get("authorityVersion")).longValue());assertEquals(2819,f.owner.held());
            ApprovalWrite write=f.owner.attempts().getFirst();assertEquals(2719,write.minorUnits());assertEquals(19,write.grantVersion());assertEquals("budget-usd",write.budgetId());
        }
    }

    @Test void failuresAfterProvisionalAttemptAndAfterReservationRollbackTheWholeOwner(){
        for(boolean adapted:List.of(false,true))for(boolean decorated:List.of(false,true))for(boolean late:List.of(false,true)){
            Fixture f=fixture(adapted,decorated);f.facts().profile(EXTERNAL);var original=new PaymentException(ApplicationFailure.TEMPORARILY_UNAVAILABLE_SAME_COMMAND);
            if(late)f.facts().acceptFailure=original;else f.facts().nativeFailure=original;
            assertSame(original,assertThrows(PaymentException.class,f::approve));unchanged(f);assertEquals(1,f.facts().nativeCalls);assertTrue(f.facts().observations.contains("attempt"));
            assertEquals(late,f.facts().observations.contains("reserve"));assertEquals(ApplicationFailure.Retry.SAME_COMMAND,original.failure().retry());
            f.facts().nativeFailure=null;f.facts().acceptFailure=null;Result accepted=f.approve();assertFalse(accepted.replayed());assertEquals(1,f.owner.attempts().size());assertEquals(1,f.owner.reservations());assertEquals(1,f.owner.authorities());
            assertTrue(f.approve().replayed());assertEquals(2,f.facts().nativeCalls);assertEquals(1350,f.owner.held());
        }
    }

    @Test void authenticationCommandGrantCapabilityAndKeyValidationRemainBeforeTheAtomicOwner(){
        Fixture f=fixture(false,false);Session s=f.facts().session;
        assertEquals("UNAUTHENTICATED",assertThrows(PaymentException.class,()->f.useCase().approve(null,f.facts().id,KEY,null)).code());
        Session noRole=new Session(s.tenantId(),s.runId(),s.principalId(),Set.of(),Set.of(),Set.of());
        assertEquals("FORBIDDEN",assertThrows(PaymentException.class,()->f.useCase().approve(noRole,f.facts().id,KEY,null)).code());
        assertEquals("INVALID_REQUEST",assertThrows(PaymentException.class,()->f.useCase().approve(s,f.facts().id,KEY,null)).code());
        Session noGrant=new Session(s.tenantId(),s.runId(),s.principalId(),s.roles(),s.allowedMerchants(),Set.of());
        assertEquals("RESOURCE_NOT_FOUND",assertThrows(PaymentException.class,()->new ApprovePayment(f.transactions(),()->null).approve(noGrant,f.facts().id,"short",f.facts().command())).code());
        assertEquals("UNSUPPORTED_CAPABILITY",assertThrows(PaymentException.class,()->new ApprovePayment(f.transactions(),()->null).approve(s,f.facts().id,"short",f.facts().command())).code());
        for(String key:Arrays.asList(null,"short","x".repeat(129),"x".repeat(16)+"\n"))assertEquals("INVALID_REQUEST",assertThrows(PaymentException.class,()->f.useCase().approve(s,f.facts().id,key,f.facts().command())).code());
        assertEquals(0,f.facts().runs);assertEquals(0,f.facts().digests);unchanged(f);
    }

    @Test void executeOnlyImplementationRetainsItsOperationsAndExplicitlyRejectsApproval(){
        Result expected=new Result(202,Map.of("state","PREPARING"),false);int[] calls={0};
        PaymentCommandTransactions old=new PaymentCommandTransactions(){
            public String requestDigest(ExecuteCommand c){calls[0]++;return "existing-execute-digest";}
            public Result run(Session session,Work work){calls[0]++;return expected;}
        };
        assertEquals("existing-execute-digest",old.requestDigest(new ExecuteCommand(1,UUID.randomUUID())));
        assertEquals("existing-execute-digest",old.requestDigest(null),"Existing null calls must remain unambiguous at compile time");
        assertSame(expected,old.run(null,null));assertEquals(3,calls[0]);
        Facts facts=new Facts();assertEquals("UNSUPPORTED_CAPABILITY",assertThrows(PaymentException.class,()->old.approvalRequestDigest(facts.command())).code());
        assertEquals("UNSUPPORTED_CAPABILITY",assertThrows(PaymentException.class,()->old.runApproval(facts.session,tx->{throw new AssertionError("unsupported work ran");})).code());
        assertEquals(3,calls[0]);
    }

    @Test void typedFactsAndWritesArePrivacySafeAndTransactionViewsExpireOnBothOutcomes() throws Exception {
        for(boolean adapted:List.of(false,true))for(boolean failed:List.of(false,true)){
            Fixture f=fixture(adapted,true);if(failed)f.facts().acceptFailure=new PaymentException(ApplicationFailure.TEMPORARILY_UNAVAILABLE_SAME_COMMAND);
            if(failed)assertThrows(PaymentException.class,f::approve);else f.approve();
            assertThrows(IllegalStateException.class,()->f.facts().escaped.now());
            var values=new ArrayList<Object>(List.of(f.facts().intent,f.facts().checkout,f.facts().grant,f.facts().budget));values.addAll(f.owner.attempts());
            for(Object value:values){assertTrue(value.toString().endsWith("[redacted]"));assertFalse(value.toString().contains(f.facts().id.toString()));assertFalse(value.toString().contains("customer-demo"));}
        }
    }
}
