package io.agenticpayments.application;

import io.agenticpayments.application.PaymentService.Session;
import io.agenticpayments.application.PaymentWorkerTransactions.*;
import io.agenticpayments.domain.PaymentRules;
import io.agenticpayments.sdk.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Independent policy examples; these in-memory owners do not establish database atomicity. */
class RunPaymentWorkTest {
    private static final Instant NOW=Instant.parse("2026-11-06T10:00:00Z");
    private static final UUID RUN=new UUID(0,1),INTENT=new UUID(0,2),ATTEMPT=new UUID(0,3),OP=new UUID(0,4),KEY=new UUID(0,5);
    private static final Session SESSION=new Session("worker-policy",RUN,"customer-demo",Set.of("runner:write"),Set.of("merchant-demo"),Set.of("fixture-grant-1"));
    private static final String DIGEST="sha256:"+"a".repeat(64),NATIVE="local-ap2-acp-single-tender-0.1",EXTERNAL="local-external-ap2-acp-single-tender-0.1";
    private static ChargeOperation operation(String scenario){return new ChargeOperation(new OperationIdentity(new ExecutionScope(SESSION.tenantId(),RUN,"merchant-demo"),INTENT,ATTEMPT,OP,KEY,DIGEST),new Money("USD",2719),scenario);}
    private static Snapshot initial(String scenario,String state){return new Snapshot(operation(scenario),PaymentRules.PROFILE,state,7,"customer-demo",DIGEST,new Grant("ACTIVE",7,"customer-demo","merchant-demo",NOW,NOW.plusSeconds(60)),"HELD",false,false,null);}
    private static Snapshot snapshot(Snapshot s,String state,String reservation,boolean admitted,Action action){return new Snapshot(s.operation(),s.profile(),state,s.grantVersion(),s.subjectId(),s.checkoutDigest(),s.grant(),reservation,s.budgetFrozen(),admitted,action);}
    private static Snapshot grant(Snapshot s,Grant g){return new Snapshot(s.operation(),s.profile(),s.state(),s.grantVersion(),s.subjectId(),s.checkoutDigest(),g,s.reservationState(),s.budgetFrozen(),s.admitted(),s.action());}
    private static Snapshot profile(Snapshot s,String p){return new Snapshot(s.operation(),p,s.state(),s.grantVersion(),s.subjectId(),s.checkoutDigest(),s.grant(),s.reservationState(),s.budgetFrozen(),s.admitted(),s.action());}
    private static Fact fact(Snapshot s,long sequence,Outcome outcome){return new Fact(new UUID(sequence,91),s.operation().identity(),s.operation().money(),sequence,outcome);}

    /** Typed copy-on-write owner. Only a completed callback publishes its candidate. */
    private static final class Ledger {
        Snapshot snapshot=initial("success","PREPARING");
        final EnumMap<WorkKind,Long> counters=new EnumMap<>(WorkKind.class);
        final Set<WorkKind> done=EnumSet.noneOf(WorkKind.class);
        final List<Fact> facts=new ArrayList<>();
        long audit,evidence,conflicts;
        Ledger(){counters.put(WorkKind.PREPARE,0L);}
        Ledger copy(){Ledger c=new Ledger();c.snapshot=snapshot;c.counters.clear();c.counters.putAll(counters);c.done.addAll(done);c.facts.addAll(facts);c.audit=audit;c.evidence=evidence;c.conflicts=conflicts;return c;}
        Activity activity(){return new Activity(audit,done.size(),evidence,conflicts);}
        void state(String state){snapshot=snapshot(snapshot,state,snapshot.reservationState(),snapshot.admitted(),snapshot.action());audit++;}
    }
    private interface Owner extends PaymentWorkerTransactions {
        Ledger view();
        void configure(Consumer<Ledger> configure);
        Controls controls();
    }
    private static final class Controls {
        boolean active,expire,rejectNative;
        RuntimeException nativeFailure,commitFailure;
        int entries,nativeCalls,unknownWrites;
        Transaction escaped;
    }
    private static final class Direct implements Owner {
        Ledger durable=new Ledger();final Controls controls=new Controls();
        public Ledger view(){return durable;}
        public void configure(Consumer<Ledger> f){f.accept(durable);}
        public Controls controls(){return controls;}
        public <T>T run(Session session,Work<T> work){
            if(controls.active)throw new IllegalStateException("nested owner");controls.entries++;controls.active=true;
            Ledger candidate=durable.copy();Thread owner=Thread.currentThread();boolean[] live={true};
            Transaction tx=new Transaction(){
                private void check(){if(!live[0]||Thread.currentThread()!=owner)throw new IllegalStateException("closed scope");}
                public Instant now(){check();return NOW;}
                public Activity activity(){check();return candidate.activity();}
                public void expireEligible(){check();if(controls.expire){candidate.snapshot=snapshot(candidate.snapshot,"EXPIRED","RELEASED",false,null);candidate.audit++;candidate.done.addAll(candidate.counters.keySet());}}
                public Optional<WorkItem> next(){check();return candidate.counters.keySet().stream().filter(k->!candidate.done.contains(k)).findFirst().map(k->new WorkItem(OP,k,candidate.counters.get(k)));}
                public Snapshot load(UUID id){check();assertEquals(OP,id);return candidate.snapshot;}
                public Snapshot findAdmitted(UUID id){check();assertEquals(INTENT,id);if(!candidate.snapshot.admitted())throw new PaymentException(ApplicationFailure.INVALID_STATE);return candidate.snapshot;}
                public Claim claim(WorkItem item){check();assertEquals(candidate.counters.get(item.kind()).longValue(),item.fence());long n=item.fence()+1;candidate.counters.put(item.kind(),n);return new Claim(item.operationId(),item.kind(),n);}
                public Claim reconciliationClaim(Snapshot s){check();if(!candidate.done.contains(WorkKind.RECONCILE))candidate.counters.merge(WorkKind.RECONCILE,1L,Long::sum);return new Claim(OP,WorkKind.RECONCILE,candidate.counters.getOrDefault(WorkKind.RECONCILE,0L));}
                public boolean isCurrent(Claim c){check();return !candidate.done.contains(c.kind())&&Objects.equals(candidate.counters.get(c.kind()),c.fence());}
                public boolean nativeGate(Snapshot s){check();controls.nativeCalls++;if(controls.nativeFailure!=null)throw controls.nativeFailure;if(controls.rejectNative)terminateBeforeAdmission(s,Termination.CANCELLED);return !controls.rejectNative;}
                public void terminateBeforeAdmission(Snapshot s,Termination t){check();if(!s.admitted()&&!s.budgetFrozen()){candidate.snapshot=snapshot(s,t.name(),"RELEASED",false,s.action());candidate.audit++;candidate.done.addAll(candidate.counters.keySet());}}
                public void completeObsolete(WorkItem item){check();candidate.done.add(item.kind());}
                public void admit(Claim c,Snapshot s){check();assertTrue(isCurrent(c));assertFalse(s.admitted());assertEquals("READY",s.state());candidate.snapshot=snapshot(s,"ADMITTED","HELD",true,s.action());candidate.audit++;candidate.done.add(WorkKind.DISPATCH);candidate.counters.putIfAbsent(WorkKind.RECONCILE,0L);}
                public void completePreparation(Claim c,Snapshot s,ActionType type){check();assertTrue(isCurrent(c));assertEquals("PREPARING",s.state());if(type==ActionType.SYNTHETIC_CHALLENGE)candidate.snapshot=snapshot(s,"WAITING_ACTION","HELD",false,new Action("OPEN",s.subjectId(),s.checkoutDigest(),NOW.plusSeconds(600)));else {candidate.snapshot=snapshot(s,"READY","HELD",false,null);candidate.counters.put(WorkKind.DISPATCH,0L);}candidate.audit++;candidate.done.add(WorkKind.PREPARE);}
                public void markUnknown(Claim c,Snapshot s){check();controls.unknownWrites++;boolean match=Objects.equals(candidate.counters.get(c.kind()),c.fence());if(match&&s.admitted()&&s.state().equals("ADMITTED")&&(c.kind()==WorkKind.DISPATCH||isCurrent(c)))candidate.state("UNKNOWN");}
                public void applyVerifiedFact(ChargeOperation op,Fact f){check();assertEquals(candidate.snapshot.operation(),op);candidate.facts.add(f);candidate.evidence++;switch(f.outcome()){case PENDING->candidate.state("PENDING");case SUCCEEDED->{candidate.snapshot=snapshot(candidate.snapshot,"SUCCEEDED","CONSUMED",true,candidate.snapshot.action());candidate.audit++;candidate.done.add(WorkKind.RECONCILE);}case NO_EFFECT->{candidate.snapshot=snapshot(candidate.snapshot,"FAILED","RELEASED",true,candidate.snapshot.action());candidate.audit++;candidate.done.add(WorkKind.RECONCILE);}}}
                public WorkerReceipt receipt(Activity a,Selection s){check();return receiptOf(candidate,a,s);}
            };
            controls.escaped=tx;
            try {T result=work.run(tx);if(controls.commitFailure!=null)throw controls.commitFailure;durable=candidate;return result;}
            catch(RuntimeException e){throw e;}catch(Exception e){throw new AssertionError(e);}finally{live[0]=false;controls.active=false;}
        }
    }

    /** Foreign API stores rows and executes a mutation against a private revision. */
    private static final class ForeignBackend {
        Map<String,Object> rows=new HashMap<>();boolean editing;
        ForeignBackend(){save(new Ledger());}
        static Object[] encode(Snapshot s){Grant g=s.grant();Action a=s.action();return new Object[]{s.operation(),s.profile(),s.state(),s.grantVersion(),s.subjectId(),s.checkoutDigest(),new Object[]{g.state(),g.version(),g.subjectId(),g.merchantId(),g.validFrom(),g.expiresAt()},s.reservationState(),s.budgetFrozen(),s.admitted(),a==null?null:new Object[]{a.state(),a.subjectId(),a.checkoutDigest(),a.expiresAt()}};}
        static Snapshot decode(Object[] r){Object[] g=(Object[])r[6],a=(Object[])r[10];return new Snapshot((ChargeOperation)r[0],(String)r[1],(String)r[2],(long)r[3],(String)r[4],(String)r[5],new Grant((String)g[0],(long)g[1],(String)g[2],(String)g[3],(Instant)g[4],(Instant)g[5]),(String)r[7],(boolean)r[8],(boolean)r[9],a==null?null:new Action((String)a[0],(String)a[1],(String)a[2],(Instant)a[3]));}
        void save(Ledger l){rows.put("purchase",encode(l.snapshot));rows.put("jobs",new EnumMap<>(l.counters));rows.put("closed",new HashSet<>(l.done));rows.put("observations",new ArrayList<>(l.facts));rows.put("revision",l.audit);rows.put("facts",l.evidence);rows.put("disputes",l.conflicts);}
        @SuppressWarnings("unchecked") Ledger read(){Ledger l=new Ledger();l.snapshot=decode((Object[])rows.get("purchase"));l.counters.clear();l.counters.putAll((Map<WorkKind,Long>)rows.get("jobs"));l.done.addAll((Set<WorkKind>)rows.get("closed"));l.facts.addAll((List<Fact>)rows.get("observations"));l.audit=(long)rows.get("revision");l.evidence=(long)rows.get("facts");l.conflicts=(long)rows.get("disputes");return l;}
        <T>T edit(java.util.function.Function<Map<String,Object>,T> action){if(editing)throw new IllegalStateException("revision already open");editing=true;Map<String,Object> candidate=new HashMap<>(rows);try{T value=action.apply(candidate);rows=candidate;return value;}finally{editing=false;}}
    }
    /** Translates foreign revision operations, instead of forwarding to the typed implementation. */
    private static final class Adapted implements Owner {
        final ForeignBackend backend=new ForeignBackend();final Controls controls=new Controls();
        public Ledger view(){return backend.read();}
        public void configure(Consumer<Ledger> f){Ledger l=view();f.accept(l);backend.save(l);}
        public Controls controls(){return controls;}
        public <T>T run(Session session,Work<T> work){
            controls.entries++;
            return backend.edit(rows->{
                Ledger candidate=view();boolean[] live={true};Thread owner=Thread.currentThread();controls.active=true;
                Transaction tx=new Transaction(){
                    private void check(){if(!live[0]||owner!=Thread.currentThread())throw new IllegalStateException("closed foreign revision");}
                    public Instant now(){check();return NOW;}
                    public Activity activity(){check();return candidate.activity();}
                    public void expireEligible(){check();if(controls.expire){candidate.snapshot=snapshot(candidate.snapshot,"EXPIRED","RELEASED",false,null);candidate.audit++;candidate.done.addAll(candidate.counters.keySet());}}
                    public Optional<WorkItem> next(){check();return Arrays.stream(WorkKind.values()).filter(k->candidate.counters.containsKey(k)&&!candidate.done.contains(k)).findFirst().map(k->new WorkItem(OP,k,candidate.counters.get(k)));}
                    public Snapshot load(UUID id){check();assertEquals(OP,id);return candidate.snapshot;}
                    public Snapshot findAdmitted(UUID id){check();assertEquals(INTENT,id);if(!candidate.snapshot.admitted())throw new PaymentException(ApplicationFailure.INVALID_STATE);return candidate.snapshot;}
                    public Claim claim(WorkItem w){check();long next=candidate.counters.get(w.kind())+1;candidate.counters.put(w.kind(),next);return new Claim(w.operationId(),w.kind(),next);}
                    public Claim reconciliationClaim(Snapshot s){check();long counter=candidate.counters.getOrDefault(WorkKind.RECONCILE,0L);if(!candidate.done.contains(WorkKind.RECONCILE))candidate.counters.put(WorkKind.RECONCILE,++counter);return new Claim(OP,WorkKind.RECONCILE,counter);}
                    public boolean isCurrent(Claim c){check();return candidate.counters.getOrDefault(c.kind(),-1L)==c.fence()&&!candidate.done.contains(c.kind());}
                    public boolean nativeGate(Snapshot s){check();controls.nativeCalls++;if(controls.nativeFailure!=null)throw controls.nativeFailure;if(controls.rejectNative)terminateBeforeAdmission(s,Termination.CANCELLED);return !controls.rejectNative;}
                    public void terminateBeforeAdmission(Snapshot s,Termination t){check();if(s.admitted()||s.budgetFrozen())return;candidate.snapshot=snapshot(s,t.name(),"RELEASED",false,s.action());candidate.audit++;candidate.done.addAll(candidate.counters.keySet());}
                    public void completeObsolete(WorkItem w){check();candidate.done.add(w.kind());}
                    public void admit(Claim c,Snapshot s){check();assertTrue(isCurrent(c));assertEquals("READY",s.state());candidate.state("ADMITTED");candidate.snapshot=snapshot(candidate.snapshot,"ADMITTED","HELD",true,s.action());candidate.done.add(c.kind());candidate.counters.putIfAbsent(WorkKind.RECONCILE,0L);}
                    public void completePreparation(Claim c,Snapshot s,ActionType t){check();assertTrue(isCurrent(c));assertEquals("PREPARING",s.state());Action action=t==ActionType.SYNTHETIC_CHALLENGE?new Action("OPEN",s.subjectId(),s.checkoutDigest(),NOW.plusSeconds(600)):null;candidate.snapshot=snapshot(s,action==null?"READY":"WAITING_ACTION","HELD",false,action);candidate.audit++;candidate.done.add(c.kind());if(action==null)candidate.counters.put(WorkKind.DISPATCH,0L);}
                    public void markUnknown(Claim c,Snapshot s){check();controls.unknownWrites++;if(!s.admitted()||!s.state().equals("ADMITTED"))return;if(candidate.counters.getOrDefault(c.kind(),-1L)!=c.fence())return;if(c.kind()==WorkKind.RECONCILE&&candidate.done.contains(c.kind()))return;candidate.state("UNKNOWN");}
                    public void applyVerifiedFact(ChargeOperation op,Fact f){check();assertEquals(candidate.snapshot.operation(),op);candidate.facts.add(f);candidate.evidence++;String state=f.outcome()==Outcome.PENDING?"PENDING":f.outcome()==Outcome.SUCCEEDED?"SUCCEEDED":"FAILED";String reservation=f.outcome()==Outcome.PENDING?"HELD":f.outcome()==Outcome.SUCCEEDED?"CONSUMED":"RELEASED";candidate.snapshot=snapshot(candidate.snapshot,state,reservation,true,candidate.snapshot.action());candidate.audit++;if(f.outcome()!=Outcome.PENDING)candidate.done.add(WorkKind.RECONCILE);}
                    public WorkerReceipt receipt(Activity a,Selection s){check();return receiptOf(candidate,a,s);}
                };
                controls.escaped=tx;
                try{T result=work.run(tx);if(controls.commitFailure!=null)throw controls.commitFailure;rows.put("purchase",ForeignBackend.encode(candidate.snapshot));rows.put("jobs",new EnumMap<>(candidate.counters));rows.put("closed",new HashSet<>(candidate.done));rows.put("observations",new ArrayList<>(candidate.facts));rows.put("revision",candidate.audit);rows.put("facts",candidate.evidence);rows.put("disputes",candidate.conflicts);return result;}
                catch(RuntimeException e){throw e;}catch(Exception e){throw new AssertionError(e);}finally{live[0]=false;controls.active=false;}
            });
        }
    }
    private static WorkerReceipt receiptOf(Ledger l,Activity before,Selection selection){boolean progressed=!l.activity().equals(before);return new WorkerReceipt(selection.operationId(),selection.kind()==null?null:selection.kind().name(),selection.fence(),l.audit>before.auditSequence()?before.auditSequence()+1:0,l.audit>before.auditSequence()?l.audit:0,progressed);}
    private static final class Wrapper implements PaymentWorkerTransactions {
        final PaymentWorkerTransactions inner;final String name;final List<String> events;
        Wrapper(PaymentWorkerTransactions inner,String name,List<String> events){this.inner=inner;this.name=name;this.events=events;}
        public <T>T run(Session s,Work<T> w){events.add(name+":enter");try{return inner.run(s,w);}finally{events.add(name+":exit");}}
    }
    private static final class Adapter implements PaymentAdapter {
        final Owner owner;CapabilityOffer offer=CapabilityOffer.localFake();
        Consumer<ChargeOperation> prepareHook=op->{},executeHook=op->{};Consumer<OperationIdentity> lookupHook=id->{};
        Preparation preparation=new Preparation(ActionType.NONE);LookupResult lookup=LookupResult.notFound();RuntimeException prepareFailure,executeFailure,lookupFailure;
        final List<ChargeOperation> prepared=new ArrayList<>(),sent=new ArrayList<>(),sealed=new ArrayList<>();final List<OperationIdentity> lookedUp=new ArrayList<>();
        Adapter(Owner owner){this.owner=owner;}
        private void outside(){assertFalse(owner.controls().active,"provider work must observe a completed callback");}
        public CapabilityOffer capabilities(){return offer;}
        public Preparation prepare(ChargeOperation op){outside();prepared.add(op);prepareHook.accept(op);if(prepareFailure!=null)throw prepareFailure;return preparation;}
        public Fact execute(ChargeOperation op){outside();Ledger l=owner.view();assertTrue(l.snapshot.admitted());assertEquals("ADMITTED",l.snapshot.state());assertEquals("HELD",l.snapshot.reservationState());assertTrue(l.done.contains(WorkKind.DISPATCH));assertTrue(l.counters.containsKey(WorkKind.RECONCILE));sent.add(op);executeHook.accept(op);if(executeFailure!=null)throw executeFailure;return fact(l.snapshot,1,Outcome.SUCCEEDED);}
        public LookupResult lookup(OperationIdentity id){outside();lookedUp.add(id);lookupHook.accept(id);if(lookupFailure!=null)throw lookupFailure;return lookup;}
        public Fact sealNoEffect(ChargeOperation op){outside();sealed.add(op);return fact(owner.view().snapshot,3,Outcome.NO_EFFECT);}
    }
    private record Fixture(Owner owner,Adapter adapter,RunPaymentWork useCase,List<String> wrappers,List<String> evidence) {}
    private static Fixture fixture(boolean foreign,boolean wrapped){
        Owner owner=foreign?new Adapted():new Direct();Adapter adapter=new Adapter(owner);List<String> wrappers=new ArrayList<>(),evidence=new ArrayList<>();PaymentWorkerTransactions transactions=owner;
        if(wrapped)transactions=new Wrapper(new Wrapper(transactions,"inner",wrappers),"outer",wrappers);
        RunPaymentWork useCase=new RunPaymentWork(transactions,adapter,(op,f)->{assertFalse(owner.controls().active);assertEquals(owner.view().snapshot.operation(),op);evidence.add("verify");return f;},(op,f)->{assertFalse(owner.controls().active);evidence.add("progress");return f;});
        return new Fixture(owner,adapter,useCase,wrappers,evidence);
    }
    private static List<Fixture> fixtures(){return List.of(fixture(false,false),fixture(false,true),fixture(true,false),fixture(true,true));}
    private static void selected(Fixture f,String scenario,String state,WorkKind kind,boolean admitted){f.owner.configure(l->{l.snapshot=snapshot(initial(scenario,state),state,"HELD",admitted,null);l.counters.clear();l.counters.put(kind,0L);l.done.clear();});}
    private static void unchanged(Fixture f){assertEquals("PREPARING",f.owner.view().snapshot.state());assertEquals("HELD",f.owner.view().snapshot.reservationState());assertEquals(0,f.owner.view().audit);assertTrue(f.adapter.sent.isEmpty());}

    @Test void directAdaptedAndWrappedOwnersPrepareThenAdmitOneOriginalExactMoneyOperation(){
        for(Fixture f:fixtures()){
            WorkerReceipt prepare=f.useCase.runNextRecorded(SESSION);assertEquals(OP,prepare.operationId());assertEquals("PREPARE",prepare.workKind());assertEquals(1,prepare.fence());assertTrue(prepare.progressed());assertEquals("READY",f.owner.view().snapshot.state());
            assertTrue(f.useCase.runNext(SESSION));assertEquals("SUCCEEDED",f.owner.view().snapshot.state());assertEquals("CONSUMED",f.owner.view().snapshot.reservationState());assertEquals(List.of(operation("success")),f.adapter.prepared);assertEquals(f.adapter.prepared,f.adapter.sent);assertEquals(new Money("USD",2719),f.adapter.sent.getFirst().money());assertEquals(List.of("verify"),f.evidence);
            assertFalse(f.useCase.runNext(SESSION));assertEquals(1,f.adapter.sent.size());
            if(!f.wrappers.isEmpty())for(int i=0;i<f.wrappers.size();i+=4)assertEquals(List.of("outer:enter","inner:enter","inner:exit","outer:exit"),f.wrappers.subList(i,i+4));
        }
    }
    @Test void trustedChallengeAndUnexpectedPreparationNeverDispatchWithoutBoundApproval(){
        for(Fixture f:fixtures()){
            selected(f,"challenge-then-success","PREPARING",WorkKind.PREPARE,false);f.adapter.preparation=new Preparation(ActionType.SYNTHETIC_CHALLENGE);f.useCase.runNext(SESSION);Snapshot s=f.owner.view().snapshot;assertEquals("WAITING_ACTION",s.state());assertEquals(new Action("OPEN","customer-demo",DIGEST,NOW.plusSeconds(600)),s.action());assertFalse(f.owner.view().counters.containsKey(WorkKind.DISPATCH));assertTrue(f.adapter.sent.isEmpty());
            Fixture bad=fixture(f.owner instanceof Adapted,!f.wrappers.isEmpty());selected(bad,"challenge-then-success","PREPARING",WorkKind.PREPARE,false);bad.useCase.runNext(SESSION);assertEquals("FAILED",bad.owner.view().snapshot.state());assertEquals("RELEASED",bad.owner.view().snapshot.reservationState());assertNull(bad.owner.view().snapshot.action());assertTrue(bad.adapter.sent.isEmpty());
        }
    }
    @Test void nativePreparationGatesRunBeforeProviderAndAgainOnFreshCompletion(){
        for(Fixture f:fixtures())for(String profile:List.of(NATIVE,EXTERNAL)){
            f.owner.configure(l->{l.snapshot=profile(initial("success","PREPARING"),profile);l.counters.put(WorkKind.PREPARE,0L);l.done.clear();});f.useCase.runNext(SESSION);assertEquals("READY",f.owner.view().snapshot.state());assertEquals(2,f.owner.controls().nativeCalls);f.owner.controls().nativeCalls=0;
        }
        for(Fixture f:fixtures()){
            f.owner.configure(l->l.snapshot=profile(l.snapshot,NATIVE));RuntimeException outage=new PaymentException(ApplicationFailure.TEMPORARILY_UNAVAILABLE_SAME_COMMAND);f.owner.controls().nativeFailure=outage;assertSame(outage,assertThrows(PaymentException.class,()->f.useCase.runNext(SESSION)));unchanged(f);assertEquals(0L,f.owner.view().counters.get(WorkKind.PREPARE));assertTrue(f.adapter.prepared.isEmpty());
        }
    }
    @Test void capabilityAndRunnerValidationPrecedeAnyOwnerEntry(){
        for(Fixture f:fixtures()){
            assertEquals("UNAUTHENTICATED",assertThrows(PaymentException.class,()->f.useCase.runNext(null)).code());Session denied=new Session(SESSION.tenantId(),RUN,"agent",Set.of(),Set.of(),Set.of());assertEquals("FORBIDDEN",assertThrows(PaymentException.class,()->f.useCase.runNext(denied)).code());f.adapter.offer=null;assertEquals("UNSUPPORTED_CAPABILITY",assertThrows(PaymentException.class,()->f.useCase.runNext(SESSION)).code());assertEquals(0,f.owner.controls().entries);unchanged(f);
        }
    }
    @Test void admissionUsesCurrentGrantEpochDatesBindingAndFrozenBudgetBeforeProvider(){
        List<Grant> invalid=List.of(new Grant("REVOKED",7,"customer-demo","merchant-demo",NOW,NOW.plusSeconds(60)),new Grant("ACTIVE",8,"customer-demo","merchant-demo",NOW,NOW.plusSeconds(60)),new Grant("ACTIVE",7,"other","merchant-demo",NOW,NOW.plusSeconds(60)),new Grant("ACTIVE",7,"customer-demo","other",NOW,NOW.plusSeconds(60)),new Grant("ACTIVE",7,"customer-demo","merchant-demo",NOW.plusSeconds(1),NOW.plusSeconds(60)),new Grant("ACTIVE",7,"customer-demo","merchant-demo",NOW.minusSeconds(1),NOW));
        for(boolean foreign:List.of(false,true))for(Grant grant:invalid){Fixture f=fixture(foreign,true);selected(f,"success","READY",WorkKind.DISPATCH,false);f.owner.configure(l->l.snapshot=grant(l.snapshot,grant));f.useCase.runNext(SESSION);assertEquals(grant.state().equals("REVOKED")?"CANCELLED":"EXPIRED",f.owner.view().snapshot.state());assertEquals("RELEASED",f.owner.view().snapshot.reservationState());assertTrue(f.adapter.sent.isEmpty());}
        for(Fixture f:fixtures()){selected(f,"success","READY",WorkKind.DISPATCH,false);f.owner.configure(l->{Snapshot s=l.snapshot;l.snapshot=new Snapshot(s.operation(),NATIVE,s.state(),s.grantVersion(),s.subjectId(),s.checkoutDigest(),new Grant("REVOKED",8,"other","other",NOW,NOW),s.reservationState(),true,false,null);});f.owner.controls().nativeFailure=new AssertionFailure();f.useCase.runNext(SESSION);assertEquals("READY",f.owner.view().snapshot.state());assertEquals("HELD",f.owner.view().snapshot.reservationState());assertEquals(0,f.owner.controls().nativeCalls);assertTrue(f.adapter.sent.isEmpty());}
    }
    private static final class AssertionFailure extends RuntimeException {}
    @Test void admissionRejectsMissingUnverifiedExpiredAndChangedTrustedAction(){
        List<Action> invalid=Arrays.asList(null,new Action("OPEN","customer-demo",DIGEST,NOW.plusSeconds(1)),new Action("VERIFIED","other",DIGEST,NOW.plusSeconds(1)),new Action("VERIFIED","customer-demo","changed",NOW.plusSeconds(1)),new Action("VERIFIED","customer-demo",DIGEST,NOW));
        for(boolean foreign:List.of(false,true))for(Action action:invalid){Fixture f=fixture(foreign,true);selected(f,"challenge-then-success","READY",WorkKind.DISPATCH,false);f.owner.configure(l->l.snapshot=snapshot(l.snapshot,"READY","HELD",false,action));f.useCase.runNext(SESSION);assertEquals("FAILED",f.owner.view().snapshot.state());assertEquals("RELEASED",f.owner.view().snapshot.reservationState());assertTrue(f.adapter.sent.isEmpty());}
        for(Fixture f:fixtures()){selected(f,"challenge-then-success","READY",WorkKind.DISPATCH,false);f.owner.configure(l->l.snapshot=snapshot(l.snapshot,"READY","HELD",false,new Action("VERIFIED","customer-demo",DIGEST,NOW.plusSeconds(1))));f.useCase.runNext(SESSION);assertEquals("SUCCEEDED",f.owner.view().snapshot.state());assertEquals(1,f.adapter.sent.size());}
    }
    @Test void preparationFailureAndUncertainAdmissionCommitNeverSend(){
        for(Fixture f:fixtures()){
            f.adapter.prepareFailure=new AdapterUnavailableException();assertSame(f.adapter.prepareFailure,assertThrows(AdapterUnavailableException.class,()->f.useCase.runNext(SESSION)));unchanged(f);assertTrue(f.owner.view().facts.isEmpty());
            selected(f,"success","READY",WorkKind.DISPATCH,false);RuntimeException failedCommit=new PaymentException(ApplicationFailure.TEMPORARILY_UNAVAILABLE_SAME_COMMAND);f.owner.controls().commitFailure=failedCommit;assertSame(failedCommit,assertThrows(PaymentException.class,()->f.useCase.runNext(SESSION)));assertEquals("READY",f.owner.view().snapshot.state());assertFalse(f.owner.view().snapshot.admitted());assertTrue(f.adapter.sent.isEmpty());
        }
    }
    @Test void missingExposureAndDefinitiveNativeRejectionNeverReachPhysicalSend(){
        for(Fixture f:fixtures()){
            selected(f,"success","READY",WorkKind.DISPATCH,false);f.owner.configure(l->l.snapshot=snapshot(l.snapshot,"READY","RELEASED",false,null));assertThrows(IllegalStateException.class,()->f.useCase.runNext(SESSION));assertEquals("READY",f.owner.view().snapshot.state());assertEquals(0L,f.owner.view().counters.get(WorkKind.DISPATCH));assertTrue(f.adapter.sent.isEmpty());
            f.owner.configure(l->l.snapshot=profile(snapshot(l.snapshot,"READY","HELD",false,null),NATIVE));f.owner.controls().rejectNative=true;f.useCase.runNext(SESSION);assertEquals("CANCELLED",f.owner.view().snapshot.state());assertEquals("RELEASED",f.owner.view().snapshot.reservationState());assertEquals(1,f.owner.controls().nativeCalls);assertTrue(f.adapter.sent.isEmpty());
        }
    }
    @Test void verificationRefusalAndUnrelatedProviderFailureRetainTheirIdentityWithoutInventingEvidence(){
        for(Fixture f:fixtures()){
            selected(f,"success","READY",WorkKind.DISPATCH,false);RuntimeException failure=new IllegalStateException("synthetic-provider-fault");f.adapter.executeFailure=failure;assertSame(failure,assertThrows(IllegalStateException.class,()->f.useCase.runNext(SESSION)));assertEquals("ADMITTED",f.owner.view().snapshot.state());assertEquals("HELD",f.owner.view().snapshot.reservationState());assertTrue(f.owner.view().facts.isEmpty());assertEquals(0,f.owner.controls().unknownWrites);
            f.adapter.executeFailure=null;f.adapter.lookup=LookupResult.found(fact(f.owner.view().snapshot,1,Outcome.SUCCEEDED));RunPaymentWork rejecting=new RunPaymentWork(f.owner,f.adapter,(op,delivered)->fact(f.owner.view().snapshot,2,Outcome.SUCCEEDED),(op,observed)->observed);assertThrows(IllegalArgumentException.class,()->rejecting.reconcile(SESSION,INTENT,false));assertTrue(f.owner.view().facts.isEmpty());assertEquals("HELD",f.owner.view().snapshot.reservationState());
        }
    }
    @Test void responseLossRetainsExposureAndReconcilesOriginalIdentityWithoutSendingAgain(){
        for(Fixture f:fixtures()){
            selected(f,"response-loss-after-acceptance","READY",WorkKind.DISPATCH,false);f.adapter.executeFailure=new AdapterUnavailableException();f.useCase.runNext(SESSION);assertEquals("UNKNOWN",f.owner.view().snapshot.state());assertEquals("HELD",f.owner.view().snapshot.reservationState());assertEquals(1,f.adapter.sent.size());
            f.adapter.lookup=LookupResult.found(fact(f.owner.view().snapshot,2,Outcome.SUCCEEDED));f.useCase.runNext(SESSION);assertEquals("SUCCEEDED",f.owner.view().snapshot.state());assertEquals("CONSUMED",f.owner.view().snapshot.reservationState());assertEquals(List.of(f.adapter.sent.getFirst().identity()),f.adapter.lookedUp);assertEquals(1,f.adapter.sent.size());assertEquals(List.of("verify"),f.evidence);
        }
    }
    @Test void absenceAndUnavailableCannotEraseKnownPendingOrReleaseHeldExposure(){
        for(Fixture f:fixtures())for(boolean unavailable:List.of(false,true)){
            selected(f,"delayed-success","PENDING",WorkKind.RECONCILE,true);f.adapter.lookup=unavailable?LookupResult.unavailable():LookupResult.notFound();f.useCase.runNext(SESSION);assertEquals("PENDING",f.owner.view().snapshot.state());assertEquals("HELD",f.owner.view().snapshot.reservationState());assertEquals(0,f.owner.controls().unknownWrites);assertTrue(f.owner.view().facts.isEmpty());assertTrue(f.adapter.sent.isEmpty());
        }
        for(Fixture f:fixtures()){selected(f,"success","ADMITTED",WorkKind.RECONCILE,true);f.adapter.lookupFailure=new AdapterUnavailableException();WorkerReceipt receipt=f.useCase.runNextRecorded(SESSION);assertEquals("UNKNOWN",f.owner.view().snapshot.state());assertEquals("HELD",f.owner.view().snapshot.reservationState());assertTrue(receipt.progressed());}
    }
    @Test void supersededPrepareIsDiscardedAndLateFinancialFactStillPassesVerification(){
        for(Fixture f:fixtures()){
            f.adapter.prepareHook=op->f.owner.configure(l->l.counters.put(WorkKind.PREPARE,2L));WorkerReceipt stale=f.useCase.runNextRecorded(SESSION);assertEquals(1,stale.fence());assertFalse(stale.progressed());assertEquals("PREPARING",f.owner.view().snapshot.state());assertFalse(f.owner.view().done.contains(WorkKind.PREPARE));
            selected(f,"success","ADMITTED",WorkKind.RECONCILE,true);f.adapter.lookup=LookupResult.found(fact(f.owner.view().snapshot,2,Outcome.SUCCEEDED));f.adapter.lookupHook=id->f.owner.configure(l->l.counters.put(WorkKind.RECONCILE,9L));f.useCase.runNext(SESSION);assertEquals("SUCCEEDED",f.owner.view().snapshot.state());assertEquals("CONSUMED",f.owner.view().snapshot.reservationState());assertEquals(List.of("verify"),f.evidence);
        }
    }
    @Test void staleAbsentOrUnavailableObservationDoesNotProjectFinancialUncertainty(){
        for(Fixture f:fixtures())for(boolean unavailable:List.of(false,true)){
            selected(f,"success","ADMITTED",WorkKind.RECONCILE,true);f.adapter.lookupHook=id->f.owner.configure(l->l.counters.put(WorkKind.RECONCILE,10L));f.adapter.lookupFailure=unavailable?new AdapterUnavailableException():null;f.useCase.runNext(SESSION);assertEquals("ADMITTED",f.owner.view().snapshot.state());assertEquals("HELD",f.owner.view().snapshot.reservationState());assertEquals(0,f.owner.controls().unknownWrites);assertTrue(f.owner.view().facts.isEmpty());
        }
    }
    @Test void manualReconcileHasNoCapabilityGateAndOnlyExplicitNotFoundMaySeal(){
        for(Fixture f:fixtures()){
            selected(f,"success","ADMITTED",WorkKind.RECONCILE,true);f.adapter.offer=null;f.useCase.reconcile(SESSION,INTENT,false);assertEquals("UNKNOWN",f.owner.view().snapshot.state());assertTrue(f.adapter.sealed.isEmpty());assertEquals("HELD",f.owner.view().snapshot.reservationState());f.useCase.reconcile(SESSION,INTENT,true);assertEquals("FAILED",f.owner.view().snapshot.state());assertEquals("RELEASED",f.owner.view().snapshot.reservationState());assertEquals(List.of(operation("success")),f.adapter.sealed);assertEquals(List.of("verify"),f.evidence);assertFalse(f.evidence.contains("progress"));
        }
        for(Fixture f:fixtures()){selected(f,"success","ADMITTED",WorkKind.RECONCILE,true);f.adapter.lookup=LookupResult.unavailable();f.useCase.reconcile(SESSION,INTENT,true);assertTrue(f.adapter.sealed.isEmpty());assertEquals("HELD",f.owner.view().snapshot.reservationState());}
    }
    @Test void explicitProgressionIsScheduledOnlyAndAllFactsVerifyBeforePersistence(){
        for(Fixture f:fixtures()){
            selected(f,"delayed-success","ADMITTED",WorkKind.RECONCILE,true);f.adapter.lookup=LookupResult.found(fact(f.owner.view().snapshot,1,Outcome.PENDING));f.useCase.runNext(SESSION);assertEquals(List.of("progress","verify"),f.evidence);assertEquals("PENDING",f.owner.view().snapshot.state());f.evidence.clear();f.useCase.reconcile(SESSION,INTENT,false);assertEquals(List.of("verify"),f.evidence);
        }
    }
    @Test void idleExpiryAndTerminalReceiptsRetainSelectionAndProgressSemantics(){
        for(Fixture f:fixtures()){
            f.owner.configure(l->l.counters.clear());WorkerReceipt idle=f.useCase.runNextRecorded(SESSION);assertNull(idle.operationId());assertNull(idle.workKind());assertEquals(0,idle.fence());assertFalse(idle.progressed());f.owner.controls().expire=true;assertTrue(f.useCase.runNext(SESSION));assertEquals("EXPIRED",f.owner.view().snapshot.state());assertTrue(f.adapter.prepared.isEmpty());
            f.owner.controls().expire=false;selected(f,"success","SUCCEEDED",WorkKind.RECONCILE,true);f.owner.configure(l->l.counters.put(WorkKind.RECONCILE,8L));WorkerReceipt terminal=f.useCase.runNextRecorded(SESSION);assertEquals(OP,terminal.operationId());assertEquals("RECONCILE",terminal.workKind());assertEquals(8,terminal.fence());assertTrue(terminal.progressed());assertTrue(f.adapter.lookedUp.isEmpty());
        }
    }
    @Test void transactionScopesExpireAndTypedDiagnosticsRedactBindings() throws Exception {
        for(Fixture f:fixtures()){
            f.useCase.runNext(SESSION);assertThrows(IllegalStateException.class,()->f.owner.controls().escaped.now());
            f.owner.run(SESSION,tx->{var result=new java.util.concurrent.FutureTask<Void>(()->{assertThrows(IllegalStateException.class,tx::now);return null;});Thread thread=new Thread(result);thread.start();result.get(5,java.util.concurrent.TimeUnit.SECONDS);assertThrows(IllegalStateException.class,()->f.owner.run(SESSION,nested->null));return null;});
            for(Object value:List.of(initial("success","READY"),initial("success","READY").grant(),new Action("VERIFIED","customer-demo",DIGEST,NOW),new Claim(OP,WorkKind.PREPARE,1),new WorkItem(OP,WorkKind.PREPARE,0),new Selection(OP,WorkKind.PREPARE,1))){assertTrue(value.toString().endsWith("[redacted]"));assertFalse(value.toString().contains(OP.toString()));assertFalse(value.toString().contains("customer-demo"));}
        }
    }
}
