package io.agenticpayments.checkout;

import io.agenticpayments.application.*;
import io.agenticpayments.application.CheckoutConfigurationResolver.ConfigurationIdentity;
import io.agenticpayments.application.CheckoutLifecycle.*;
import io.agenticpayments.application.CheckoutLifecycleTransactions.*;
import io.agenticpayments.checkout.CheckoutCommandTransport.Prepared;
import java.util.*;
import static io.agenticpayments.checkout.CheckoutSessions.*;

/** Independently authored deterministic contract double; no JDBC, SQL, JSON or network dependency. */
final class MemoryCheckoutTransactions implements CheckoutLifecycleTransactions {
    private record Scope(String tenant,UUID run,String principal) {}
    private record Resource(Scope scope,UUID id) {}
    private record Replay(Resource purchase,String key) {}
    private record Config(Scope scope,String merchant,String id) {}
    private record Version(Config config,String version) {}
    private record Outcome(Resource command,UUID token) {}
    private Map<Resource,Purchase> purchases=new HashMap<>();
    private Map<Resource,StoredCommand> commands=new HashMap<>();
    private Map<Replay,UUID> replays=new HashMap<>();
    private Map<Config,ConfigurationIdentity> current=new HashMap<>();
    private Map<Version,ConfigurationIdentity> versions=new HashMap<>();
    private Map<Outcome,Result> outcomes=new HashMap<>();
    private boolean entered;
    private static Scope scope(PaymentService.Session s){return new Scope(s.tenantId(),s.runId(),s.principalId());}
    @Override public synchronized <T>T run(PaymentService.Session session,Work<T> work){
        check(!entered,"NESTED_TRANSACTION");entered=true;
        var oldPurchases=new HashMap<>(purchases);var oldCommands=new HashMap<>(commands);var oldReplays=new HashMap<>(replays);var oldOutcomes=new HashMap<>(outcomes);
        Unit unit=new Unit(scope(session));
        try{return work.run(unit);}catch(RuntimeException failure){purchases=oldPurchases;commands=oldCommands;replays=oldReplays;outcomes=oldOutcomes;throw failure;}
        finally{unit.active=false;entered=false;}
    }
    @Override public synchronized void enroll(PaymentService.Session session,ConfigurationIdentity identity){
        check(identity.binding().tenantId().equals(session.tenantId())&&identity.binding().principalId().equals(session.principalId()),"CHECKOUT_BINDING_CONFLICT");
        var config=new Config(scope(session),identity.binding().merchantId(),identity.binding().configurationId());var version=new Version(config,identity.configurationVersion());
        check(!versions.containsKey(version)||versions.get(version).equals(identity),"CONFIGURATION_IDENTITY_CONFLICT");
        versions.putIfAbsent(version,identity);current.put(config,identity);
    }
    private final class Unit implements Transaction {
        final Scope scope;final Thread thread=Thread.currentThread();boolean active=true;Unit(Scope scope){this.scope=scope;}
        Resource resource(UUID id){check(active&&thread==Thread.currentThread(),"INACTIVE_TRANSACTION");return new Resource(scope,id);}
        public Purchase purchase(UUID id){return purchases.get(resource(id));}
        public StoredCommand command(UUID purchase,UUID id){var c=commands.get(resource(id));return c!=null&&c.purchaseId().equals(purchase)?c:null;}
        public StoredCommand replay(UUID purchase,String key){UUID id=replays.get(new Replay(resource(purchase),key));return id==null?null:command(purchase,id);}
        public ConfigurationIdentity currentConfiguration(String merchant,String id){resource(null);return current.get(new Config(scope,merchant,id));}
        public StoredCommand stage(UUID purchaseId,String key,String fingerprint,StageCommand input,ConfigurationIdentity configuration,Prepared prepared){
            Purchase p=purchase(purchaseId);check(replay(purchaseId,key)==null,"IDEMPOTENCY_CONFLICT");
            check(configuration.equals(currentConfiguration(input.merchantId(),input.configurationId())),"CONFIGURATION_CHANGED");
            check(configuration.binding().equals(prepared.request().binding())&&input.currency().equals(prepared.request().currency())
                    &&input.operation()==prepared.request().operation()&&input.itemIds().equals(prepared.request().items().stream().map(ItemReference::id).toList()),"CHECKOUT_BINDING_CONFLICT");
            if(p==null)check(input.operation()==Operation.CREATE&&input.expectedRevision()==0&&prepared.request().sessionId()==null,"CHECKOUT_REVISION_CONFLICT");
            else{
                check(p.revision()==input.expectedRevision()&&p.configuration().equals(configuration)&&p.currency().equals(input.currency())&&Objects.equals(p.sessionId(),prepared.request().sessionId()),"CHECKOUT_REVISION_CONFLICT");
                check(input.operation()!=Operation.CREATE&&p.sessionId()!=null,"CHECKOUT_ALREADY_EXISTS");
                check(!p.mutationBlocked()||input.operation()==Operation.RETRIEVE,"CHECKOUT_MUTATION_BLOCKED");
                StoredCommand old=command(purchaseId,p.currentCommandId());check(old.state()!=CommandState.STAGED&&old.state()!=CommandState.CLAIMED,"CHECKOUT_COMMAND_IN_FLIGHT");
            }
            UUID id=UUID.randomUUID();int revision=p==null?1:p.revision()+1;
            StoredCommand c=new StoredCommand(id,purchaseId,key,fingerprint,input,configuration,prepared,revision,CommandState.STAGED,null,"NONE",Observation.NOT_SENT,0,null);
            purchases.put(resource(purchaseId),new Purchase(purchaseId,configuration,input.currency(),p==null?null:p.sessionId(),revision,p==null?null:p.remoteStatus(),p==null?null:p.documentDigest(),id,p!=null&&p.mutationBlocked()));
            commands.put(resource(id),c);replays.put(new Replay(resource(purchaseId),key),id);return c;
        }
        public Claim claim(UUID purchaseId,UUID id,UUID token){
            StoredCommand c=command(purchaseId,id);check(c!=null,"NOT_FOUND");if(c.state()!=CommandState.STAGED)return null;
            Purchase p=purchase(purchaseId);check(p.currentCommandId().equals(id)&&p.revision()==c.stagedRevision(),"CHECKOUT_REVISION_CONFLICT");
            check(c.configuration().equals(currentConfiguration(c.input().merchantId(),c.input().configurationId())),"CONFIGURATION_CHANGED");
            check(!p.mutationBlocked()||c.input().operation()==Operation.RETRIEVE,"CHECKOUT_MUTATION_BLOCKED");
            StoredCommand claimed=change(c,CommandState.CLAIMED,token,"NONE",Observation.UNKNOWN,0,null);commands.put(resource(id),claimed);return new Claim(claimed,token);
        }
        public StoredCommand record(Claim claim,Result result,boolean resolverCurrent){
            StoredCommand original=claim.command(),c=command(original.purchaseId(),original.id());
            check(c!=null&&claim.token().equals(original.claimToken())&&c.fingerprint().equals(original.fingerprint())&&same(c.prepared(),original.prepared()),"CHECKOUT_CORRELATION_CONFLICT");
            var outcome=new Outcome(resource(c.id()),claim.token());if(outcomes.putIfAbsent(outcome,result)!=null)return c;
            if(c.state()!=CommandState.CLAIMED||!claim.token().equals(c.claimToken()))return c;
            Purchase p=purchase(c.purchaseId());
            boolean currentFence=resolverCurrent&&p.currentCommandId().equals(c.id())&&p.revision()==c.stagedRevision()
                    &&c.configuration().equals(currentConfiguration(c.input().merchantId(),c.input().configurationId()));
            boolean unknown=result.observation()==Observation.UNKNOWN,mutation=c.input().operation()!=Operation.RETRIEVE;
            var state=!currentFence?CommandState.STALE:unknown?CommandState.UNKNOWN:CommandState.RECORDED;
            var next=change(c,state,null,currentFence?result.code().name():"STALE_OUTCOME",currentFence?result.observation():Observation.UNKNOWN,result.httpStatus(),UUID.randomUUID());
            commands.put(resource(c.id()),next);boolean promote=currentFence&&result.code()==Code.OK;
            purchases.put(resource(p.id()),new Purchase(p.id(),p.configuration(),p.currency(),promote?result.document().session().id():p.sessionId(),p.revision()+1,
                    promote?result.document().status():p.remoteStatus(),promote?"protected-memory-digest":p.documentDigest(),p.currentCommandId(),p.mutationBlocked()||mutation&&(!currentFence||unknown)));
            return next;
        }
        public StoredCommand recoverClaim(UUID purchaseId,UUID id){
            StoredCommand c=command(purchaseId,id);check(c!=null,"NOT_FOUND");if(c.state()!=CommandState.CLAIMED)return c;
            var next=change(c,CommandState.UNKNOWN,null,"WORKER_INTERRUPTED",Observation.UNKNOWN,0,null);commands.put(resource(id),next);
            Purchase p=purchase(purchaseId);purchases.put(resource(purchaseId),new Purchase(p.id(),p.configuration(),p.currency(),p.sessionId(),p.revision()+1,p.remoteStatus(),p.documentDigest(),p.currentCommandId(),p.mutationBlocked()||c.input().operation()!=Operation.RETRIEVE));return next;
        }
    }
    private static StoredCommand change(StoredCommand c,CommandState state,UUID token,String code,Observation observation,int http,UUID response){
        return new StoredCommand(c.id(),c.purchaseId(),c.localKey(),c.fingerprint(),c.input(),c.configuration(),c.prepared(),c.stagedRevision(),state,token,code,observation,http,response);
    }
    private static boolean same(Prepared a,Prepared b){return a.request().equals(b.request())&&a.profile().equals(b.profile())&&a.encodingVersion().equals(b.encodingVersion())&&a.method().equals(b.method())&&a.path().equals(b.path())&&Objects.equals(a.contentType(),b.contentType())&&Arrays.equals(a.body(),b.body());}
    private static void check(boolean valid,String code){if(!valid)throw new PaymentException(code.equals("NOT_FOUND")?404:409,code);}
}
