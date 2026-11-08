package io.agenticpayments.replay;

import io.agenticpayments.application.*;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.infrastructure.*;
import io.agenticpayments.http.HttpFailureMapper;
import io.agenticpayments.sdk.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static io.agenticpayments.replay.ReplayPlan.*;
import static io.agenticpayments.replay.ReplaySql.*;

/** Trusted CLI/test composition only. Each plan execution receives a new isolated physical run. */
public final class ReplayRunner {
    public record Execution(UUID runId,UUID executionId) {public Execution{Objects.requireNonNull(runId);Objects.requireNonNull(executionId);}}
    private final ReplayRepository repository;
    public ReplayRunner(DataSource ds){repository=new ReplayRepository(Objects.requireNonNull(ds));}
    public Execution start(ReplayPlan plan,String tenantId){
        var data=repository.create(Objects.requireNonNull(plan),tenantId);service(data,new JdbcSimulatorAdapter(repository.dataSource())).seedRun(actor(data,"runner"),Instant.parse(plan.initialClock()),Long.parseLong(plan.configuration().budgetMinorUnits()));return new Execution(data.run(),data.execution());
    }
    public ReplayReport execute(ReplayPlan plan,String tenantId){return continueExecution(start(plan,tenantId));}
    public ReplayReport continueExecution(Execution execution){
        var candidate=repository.load(execution);
        try(var lease=lease(candidate)){
            var data=repository.load(execution);
            if(!"RUNNING".equals(data.state()))return storedOrCurrent(data);
            if(!ReplayArtifacts.current().equals(data.plan().artifacts()))return persistReport(data,"INCOMPLETE","ARTIFACT_IDENTITY_CHANGED");
            if(repository.steps(data).stream().anyMatch(row->"STARTED".equals(row.get("state"))))return persistReport(data,"INCOMPLETE","ORPHAN_STARTED_STEP");
            Context context=new Context(data);
            int completed=repository.steps(data).size();
            for(int n=completed;n<data.plan().steps().size();n++){lease.checkAlive();if(!step(context,data.plan().steps().get(n)))return context.incompleteReason==null?persistReport(data,"FAIL","STEP_EXPECTATION_FAILED"):incomplete(data,context.incompleteReason,context.snapshotSafe);}
            return persistReport(data,"PASS",null);
        }catch(SQLException e){throw new IllegalStateException("Replay owner lease could not be closed",e);}
    }
    /** Execute exactly one scheduled action, useful for bounded CLI sessions; never retries an orphan STARTED. */
    public ReplayReport advance(Execution execution){
        var candidate=repository.load(execution);try(var lease=lease(candidate)){
            var data=repository.load(execution);
            if(!"RUNNING".equals(data.state()))return storedOrCurrent(data);
            var rows=repository.steps(data);if(rows.stream().anyMatch(row->"STARTED".equals(row.get("state"))))return persistReport(data,"INCOMPLETE","ORPHAN_STARTED_STEP");
            if(!ReplayArtifacts.current().equals(data.plan().artifacts()))return persistReport(data,"INCOMPLETE","ARTIFACT_IDENTITY_CHANGED");
            lease.checkAlive();Context context=new Context(data);if(rows.size()<data.plan().steps().size()&&!step(context,data.plan().steps().get(rows.size())))return context.incompleteReason==null?persistReport(data,"FAIL","STEP_EXPECTATION_FAILED"):incomplete(data,context.incompleteReason,context.snapshotSafe);
            ReplayReport report=repository.steps(data).size()==data.plan().steps().size()?persistReport(data,"PASS",null):report(data,"RUNNING",null);if(report.outcome().equals("FAIL"))repository.state(data,"FAIL",report.json());return report;
        }catch(SQLException e){throw new IllegalStateException("Replay owner lease could not be closed",e);}
    }
    public ReplayReport inspect(Execution execution){var candidate=repository.load(execution);try(var lease=lease(candidate)){var data=repository.load(execution);ReplayReport stored=stored(data);if(stored!=null&&"INCOMPLETE".equals(stored.outcome())&&Boolean.TRUE.equals(stored.normalizedSnapshot().get("diagnosticOnly")))return stored;if("RUNNING".equals(data.state())&&repository.steps(data).stream().anyMatch(row->"STARTED".equals(row.get("state"))))return report(data,"INCOMPLETE","ORPHAN_STARTED_STEP");return report(data,data.state(),stored==null?null:stored.reason());}catch(SQLException e){throw new IllegalStateException("Replay snapshot lease failed",e);}}
    private PaymentService service(ReplayRepository.ExecutionData data,PaymentAdapter adapter){return new PaymentService(new JdbcPaymentStore(repository.dataSource(),adapter,data.owner()));}
    private Session actor(ReplayRepository.ExecutionData data,String alias){return ReplayActors.resolve(data.tenant(),data.run(),alias);}
    private final class Context {final ReplayRepository.ExecutionData data;final List<ReplayRepository.Alias> staged=new ArrayList<>();PaymentService service;String incompleteReason;boolean snapshotSafe=true;Context(ReplayRepository.ExecutionData data){this.data=data;service=service(data,new JdbcSimulatorAdapter(repository.dataSource()));}}
    private boolean step(Context context,Step step){
        var data=context.data;repository.begin(data,step);context.staged.clear();Object result;
        try{
            result=invoke(context,step);
        }catch(RecipeIncomplete e){context.incompleteReason=e.reason;context.snapshotSafe=e.snapshotSafe;var receipt=map("code","RECIPE_INCOMPLETE","reason",e.reason,"snapshotSafe",e.snapshotSafe);repository.finishRecorded(data,step,context.staged,ignored->receipt,false);return false;}
        // Retain the existing replay evidence projection; application control flow uses semantic failures.
        catch(ApplicationException e){var receipt=map("code",e.code(),"httpStatus",HttpFailureMapper.map(e).status(),"retry",HttpFailureMapper.map(e).retry());boolean expected=e.code().equals(step.expectedCode());repository.finishRecorded(data,step,context.staged,ignored->receipt,expected);return expected;}
        catch(ReplayNormalizer.InvariantViolation e){var receipt=map("code","RAW_INVARIANT_FAILED","checks",e.checks());repository.finishRecorded(data,step,context.staged,ignored->receipt,false);return false;}
        catch(Exception e){var receipt=map("code","RUNNER_STEP_FAILED","failureType",e.getClass().getSimpleName());repository.finishRecorded(data,step,context.staged,ignored->receipt,false);return false;}
        boolean expected=step.expectedCode().equals("OK");repository.finishRecorded(data,step,context.staged,names->{var receipt=map("code","OK","result",safeValue(result,names));if(!expected)receipt.put("expectedCode",step.expectedCode());return receipt;},expected);return expected;
    }
    private Object invoke(Context context,Step step)throws Exception{
        var data=context.data;Session session=actor(data,step.actor());String key=key(data,step);PaymentService service=context.service;
        return switch(step.parameters()){
            case Create p->{Result result=service.create(session,key,new CreateCommand("merchant-demo",p.scenario(),data.plan().profile(),p.requiredCapabilities()));context.staged.add(new ReplayRepository.Alias("INTENT",p.intent(),uuid(result.body().get("intentId"))));yield response(result);}
            case Quote p->response(service.quote(session,intent(data,p.intent()),key,new QuoteCommand(version(data,p.expectedVersion()),p.cartAlias().equals("cart-eur")?"cart-1250-eur":"cart-1250-usd",List.of(new Tender("simulator","synthetic-card","fixture-credential-1")))));
            case Approve p->{Map<String,Object> quote=priorResult(data,p.quoteResult());yield response(service.approve(session,intent(data,p.intent()),key,new ApproveCommand(version(data,p.expectedVersion()),((Number)quote.get("checkoutRevision")).intValue(),quote.get("checkoutDigest").toString(),grant(p.grantAlias()))));}
            case Execute p->response(service.execute(session,intent(data,p.intent()),key,new ExecuteCommand(version(data,p.expectedVersion()),repository.resolve(data,"ATTEMPT",p.intent()+"-attempt"))));
            case CompleteAction p->response(service.completeAction(session,repository.resolve(data,"ACTION",p.intent()+"-action"),key,new CompleteActionCommand(version(data,p.expectedVersion()),p.decision())));
            case Revoke p->response(service.revoke(session,grant(p.grantAlias()),key,new RevokeCommand(p.reason())));
            case AdvanceClock p->{service.advanceClock(session,Instant.parse(p.instant()));yield map("virtualTime",p.instant());}
            case RunNext ignored->worker(service.runNextRecorded(session));
            case Reconcile p->{service.reconcile(session,intent(data,p.intent()),p.sealIfAbsent());yield map("intent",p.intent(),"sealIfAbsent",p.sealIfAbsent(),"status",service.status(session,intent(data,p.intent())).body());}
            case Reconstruct ignored->{runner(step);context.service=service(data,new JdbcSimulatorAdapter(repository.dataSource()));yield map("reconstructed",true);}
            case AssertState p->{var status=service.status(session,intent(data,p.intent())).body();if(!p.expectedState().equals(status.get("state"))||!p.expectedExposure().equals(status.get("exposureState")))throw new IllegalStateException("Intent assertion failed");yield map("invariant",p.invariant(),"intent",p.intent(),"observedState",status.get("state"),"observedExposure",status.get("exposureState"),"passed",true);}
            case AssertBudget p->{runner(step);var b=repository.transaction(data,c->one(c,"SELECT * FROM budgets WHERE tenant_id=? AND run_id=? AND currency=?",data.tenant(),data.run(),p.currency()));if(!Long.toString(number(b,"held")).equals(p.heldMinorUnits())||!Long.toString(number(b,"consumed")).equals(p.consumedMinorUnits())||!Objects.equals(b.get("frozen"),p.frozen()))throw new IllegalStateException("Budget assertion failed");yield map("invariant",p.invariant(),"currency",p.currency(),"heldMinorUnits",p.heldMinorUnits(),"consumedMinorUnits",p.consumedMinorUnits(),"frozen",p.frozen(),"passed",true);}
            case AssertRaw p->{runner(step);yield map("invariant",p.invariant(),"checks",new ReplayNormalizer(repository).collect(data,false).checks(),"passed",true);}
            case Interleaving p->{runner(step);yield interleaving(context,step,p);}
        };
    }
    private Object interleaving(Context context,Step step,Interleaving parameters)throws Exception{
        var data=context.data;UUID intent=intent(data,parameters.intent());if(!"READY".equals(context.service.status(actor(data,"runner"),intent).body().get("state")))throw new IllegalStateException("Recipe requires READY target");
        // Verify the explicit target is actually next under the persisted priority/enqueue ordering.
        UUID next=repository.transaction(data,c->(UUID)one(c,"SELECT a.intent_id FROM outbox w JOIN financial_operations o USING(tenant_id,run_id,operation_id) JOIN payment_attempts a USING(tenant_id,run_id,attempt_id) WHERE w.tenant_id=? AND w.run_id=? AND NOT w.done ORDER BY CASE w.kind WHEN 'PREPARE' THEN 0 WHEN 'DISPATCH' THEN 1 ELSE 2 END,w.enqueue_sequence LIMIT 1",data.tenant(),data.run()).get("intent_id"));
        if(!intent.equals(next))throw new IllegalStateException("Recipe target is not next recorded work");
        var adapter=new BarrierAdapter(new JdbcSimulatorAdapter(repository.dataSource()),intent);PaymentService controlled=service(data,adapter);var executor=Executors.newSingleThreadExecutor();var boundaries=new ArrayList<String>();Future<WorkerReceipt> future=null;boolean complete=false;
        try{
            future=executor.submit(()->controlled.runNextRecorded(actor(data,"runner")));
            if(!adapter.entered.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Recipe entry timed out");boundaries.add("ADMISSION_COMMITTED");boundaries.add("SEND_ENTERED");Object middle;
            if(parameters.recipe()==Recipe.ADMISSION_REVOKE_SEND_0_1){String targetGrant=repository.transaction(data,c->text(one(c,"SELECT grant_id FROM payment_attempts WHERE tenant_id=? AND run_id=? AND intent_id=?",data.tenant(),data.run(),intent),"grant_id"));middle=response(context.service.revoke(actor(data,"customer"),targetGrant,key(data,step)+"-revoke",new RevokeCommand("test-revocation")));boundaries.add("REVOCATION_COMMITTED");}
            else{context.service.reconcile(actor(data,"runner"),intent,true);middle=map("sealed",true);boundaries.add("NO_EFFECT_SEAL_COMMITTED");}
            boundaries.add("SEND_RELEASED");adapter.resume.countDown();WorkerReceipt receipt=future.get(10,TimeUnit.SECONDS);boundaries.add("WORKER_FINISHED");complete=true;
            return map("recipe",parameters.recipe().name(),"intent",parameters.intent(),"barrierOrder",boundaries,"middle",middle,"worker",worker(receipt));
        }finally{
            if(complete)executor.shutdown();
            else {adapter.abort=true;adapter.resume.countDown();executor.shutdownNow();boolean stopped=false;try{stopped=executor.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}throw new RecipeIncomplete(stopped?"RECIPE_INTERRUPTED":"ACTIVE_WORKER_NOT_SNAPSHOT_SAFE",stopped);}
        }
    }
    private static final class RecipeIncomplete extends RuntimeException{final String reason;final boolean snapshotSafe;RecipeIncomplete(String reason,boolean snapshotSafe){this.reason=reason;this.snapshotSafe=snapshotSafe;}}
    private static final class BarrierAdapter implements PaymentAdapter{
        final JdbcSimulatorAdapter delegate;final UUID target;final CountDownLatch entered=new CountDownLatch(1),resume=new CountDownLatch(1);volatile boolean abort;
        BarrierAdapter(JdbcSimulatorAdapter delegate,UUID target){this.delegate=delegate;this.target=target;}
        public CapabilityOffer capabilities(){return delegate.capabilities();}public Preparation prepare(ChargeOperation op){return delegate.prepare(op);}
        public Fact execute(ChargeOperation op){if(!target.equals(op.identity().intentId()))throw new IllegalArgumentException("Unexpected recipe operation");entered.countDown();try{if(!resume.await(15,TimeUnit.SECONDS)||abort)throw new AdapterUnavailableException();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AdapterUnavailableException();}return delegate.execute(op);}
        public LookupResult lookup(OperationIdentity id){return delegate.lookup(id);}public Fact sealNoEffect(ChargeOperation op){return delegate.sealNoEffect(op);}
    }
    private ReplayLease lease(ReplayRepository.ExecutionData data){
        Connection connection=null;try{connection=repository.dataSource().getConnection();long key=data.execution().getMostSignificantBits()^data.execution().getLeastSignificantBits();try(var p=connection.prepareStatement("SELECT pg_try_advisory_lock(?)")){p.setLong(1,key);try(var r=p.executeQuery()){r.next();if(!r.getBoolean(1))throw new IllegalStateException("Replay execution is already active");}}return new ReplayLease(connection,key);}catch(Exception e){if(connection!=null)try{connection.close();}catch(SQLException ignored){}if(e instanceof RuntimeException runtime)throw runtime;throw new IllegalStateException("Replay execution lease failed",e);}
    }
    private static final class ReplayLease implements AutoCloseable{
        final Connection connection;final long key;ReplayLease(Connection connection,long key){this.connection=connection;this.key=key;}
        void checkAlive()throws SQLException{try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT 1")){if(!rows.next())throw new SQLException("Replay lease connection is not live");}}
        @Override public void close()throws SQLException{try(var statement=connection.prepareStatement("SELECT pg_advisory_unlock(?)")){statement.setLong(1,key);try(var rows=statement.executeQuery()){if(!rows.next()||!rows.getBoolean(1))throw new SQLException("Replay lease is not held");}}finally{connection.close();}}
    }
    private ReplayReport persistReport(ReplayRepository.ExecutionData data,String outcome,String reason){ReplayReport report=report(data,outcome,reason);repository.state(data,report.outcome(),report.json());return report;}
    private ReplayReport storedOrCurrent(ReplayRepository.ExecutionData data){ReplayReport stored=stored(data);return stored==null?report(data,data.state(),null):stored;}
    private ReplayReport stored(ReplayRepository.ExecutionData data){if(data.finalReport()==null)return null;try{return ReplayJson.JSON.readValue(data.finalReport(),ReplayReport.class);}catch(Exception e){throw new IllegalStateException("Stored replay report is invalid");}}
    private ReplayReport incomplete(ReplayRepository.ExecutionData data,String reason,boolean snapshotSafe){
        if(snapshotSafe)return persistReport(data,"INCOMPLETE",reason);
        var report=new ReplayReport("simulator-replay-report-0.1","INCOMPLETE",data.execution(),data.run(),data.plan().digest(),data.plan().configurationDigest(),data.plan().artifacts(),null,null,null,null,List.of("no-active-financial-call-0.1:FAIL"),map("normalizationVersion",NORMALIZATION,"diagnosticOnly",true),reason);repository.state(data,"INCOMPLETE",report.json());return report;
    }
    private ReplayReport report(ReplayRepository.ExecutionData data,String outcome,String reason){
        try{var snapshot=new ReplayNormalizer(repository).collect(data,"PASS".equals(outcome));return new ReplayReport("simulator-replay-report-0.1",outcome,data.execution(),data.run(),data.plan().digest(),data.plan().configurationDigest(),data.plan().artifacts(),snapshot.coreDigest(),snapshot.journalDigest(),snapshot.auditDigest(),snapshot.combinedDigest(),snapshot.checks(),snapshot.normalized(),reason);}
        catch(ReplayNormalizer.InvariantViolation e){return new ReplayReport("simulator-replay-report-0.1","INCOMPLETE".equals(outcome)?"INCOMPLETE":"FAIL",data.execution(),data.run(),data.plan().digest(),data.plan().configurationDigest(),data.plan().artifacts(),null,null,null,null,e.checks(),map("normalizationVersion",NORMALIZATION,"diagnosticOnly",true),reason==null?"RAW_INVARIANT_FAILED":reason);}
    }
    private Map<String,Object> priorResult(ReplayRepository.ExecutionData data,String label){
        var row=repository.steps(data).stream().filter(r->label.equals(r.get("label"))&&"DONE".equals(r.get("state"))).findFirst().orElseThrow(()->new IllegalArgumentException("Result reference is unavailable"));
        try{var receipt=ReplayJson.JSON.readTree(text(row,"safe_receipt"));return ReplayJson.JSON.convertValue(receipt.path("result").path("body"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});}catch(Exception e){throw new IllegalArgumentException("Result reference has no command body");}
    }
    private int version(ReplayRepository.ExecutionData data,VersionRef version){if(version.literal()!=null)return version.literal();Object value=priorResult(data,version.resultLabel()).get("version");if(!(value instanceof Number n))throw new IllegalArgumentException("Result reference has no resource version");return n.intValue();}
    private UUID intent(ReplayRepository.ExecutionData data,String alias){return repository.resolve(data,"INTENT",alias);}
    private static String grant(String alias){return alias.equals("grant-eur")?"fixture-grant-1":"fixture-grant-usd";}
    private static UUID uuid(Object value){return value instanceof UUID id?id:UUID.fromString(value.toString());}
    private static String key(ReplayRepository.ExecutionData data,Step step){return io.agenticpayments.domain.PaymentRules.sha256(data.plan().digest()+":"+step.ordinal()+":"+step.actor());}
    private static void runner(Step step){if(!step.actor().equals("runner"))throw new PaymentException(ApplicationFailure.FORBIDDEN);}
    private static Map<String,Object> response(Result result){return map("status",result.status(),"body",result.body(),"replayed",result.replayed());}
    private static Map<String,Object> worker(WorkerReceipt result){return map("operation",result.operationId(),"workKind",result.workKind()==null?"IDLE":result.workKind(),"fence",result.fence(),"firstAuditSequence",result.firstAuditSequence(),"lastAuditSequence",result.lastAuditSequence(),"progressed",result.progressed());}
    private Object safeValue(Object value,Map<UUID,String> aliases){
        if(value==null)return null;if(value instanceof UUID id){String alias=aliases.get(id);if(alias==null)throw new IllegalStateException("Unknown physical reference in replay receipt");return alias;}
        if(value instanceof Map<?,?> source){var result=new LinkedHashMap<String,Object>();for(var entry:source.entrySet()){String key=entry.getKey().toString();if(key.equals("correlationId"))continue;Object safe=safeValue(entry.getValue(),aliases);if(key.equals("alreadyAdmittedAttemptIds")&&safe instanceof List<?> list)safe=list.stream().map(Object::toString).sorted().toList();result.put(key,safe);}return result;}
        if(value instanceof Collection<?> values){var result=new ArrayList<Object>();for(Object item:values)result.add(safeValue(item,aliases));return result;}
        if(value instanceof String text&&text.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))return safeValue(UUID.fromString(text),aliases);
        return value;
    }
}
