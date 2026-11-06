package io.agenticpayments.replay;

import io.agenticpayments.infrastructure.DatabaseMigrator;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static io.agenticpayments.replay.ReplaySql.*;

final class ReplayRepository {
    record ExecutionData(String tenant,UUID run,UUID execution,UUID owner,ReplayPlan plan,String state,String finalReport){}
    record Alias(String kind,String name,UUID physical){}
    private final DataSource ds;
    ReplayRepository(DataSource ds){this.ds=ds;}
    DataSource dataSource(){return ds;}
    @FunctionalInterface interface Work<T>{T run(Connection c)throws Exception;}
    <T>T transaction(ExecutionData data,Work<T> work){
        try(var c=ds.getConnection()){c.setAutoCommit(false);try{var run=one(c,"SELECT * FROM simulation_runs WHERE tenant_id=? AND run_id=? FOR UPDATE",data.tenant,data.run);if(!"REPLAY".equals(run.get("execution_mode"))||!data.owner.equals(run.get("replay_owner")))throw new IllegalStateException("Replay ownership changed");T result=work.run(c);c.commit();return result;}catch(Exception e){c.rollback();throw e;}}
        catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("Replay metadata transaction failed",e);}
    }
    ExecutionData create(ReplayPlan plan,String tenant){
        if(tenant==null||!tenant.matches("[a-z][a-z0-9-]{0,63}"))throw new IllegalArgumentException("Invalid physical tenant scope");
        if(!ReplayArtifacts.current().equals(plan.artifacts())||!ReplayArtifacts.policyDigest().equals(plan.configuration().descriptorPolicyDigest()))throw new IllegalArgumentException("Replay artifact/configuration identity differs from runtime");
        DatabaseMigrator.migrate(ds);UUID run=UUID.randomUUID(),execution=UUID.randomUUID(),owner=UUID.randomUUID();
        try(var c=ds.getConnection()){c.setAutoCommit(false);
            update(c,"INSERT INTO simulation_runs(tenant_id,run_id,virtual_time,execution_mode,replay_owner,sequence_origin) VALUES(?,?,?,'REPLAY',?,'RECORDED')",tenant,run,Instant.parse(plan.initialClock()),owner);
            update(c,"INSERT INTO replay_runs(tenant_id,run_id,execution_id,logical_name,seed,canonical_plan,plan_digest,configuration_digest,core_digest,adapter_digest,catalog_digest,normalizer_digest,initial_clock,owner_id,state) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,'RUNNING')",tenant,run,execution,plan.logicalRunName(),plan.seed(),plan.canonicalBytes(),plan.digest(),plan.configurationDigest(),plan.artifacts().coreDigest(),plan.artifacts().adapterDigest(),plan.artifacts().catalogDigest(),plan.artifacts().normalizerDigest(),Instant.parse(plan.initialClock()),owner);c.commit();
        }catch(SQLException e){throw new IllegalStateException("Replay execution could not be created",e);}
        return new ExecutionData(tenant,run,execution,owner,plan,"RUNNING",null);
    }
    ExecutionData load(ReplayRunner.Execution execution){
        try(var c=ds.getConnection()){var row=one(c,"SELECT * FROM replay_runs WHERE execution_id=? AND run_id=?",execution.executionId(),execution.runId());ReplayPlan plan=ReplayPlanJson.parse(text(row,"canonical_plan"));if(!plan.digest().equals(row.get("plan_digest")))throw new IllegalStateException("Stored replay plan digest changed");return new ExecutionData(text(row,"tenant_id"),(UUID)row.get("run_id"),(UUID)row.get("execution_id"),(UUID)row.get("owner_id"),plan,text(row,"state"),text(row,"final_report"));}
        catch(SQLException e){throw new IllegalStateException("Replay execution could not be read",e);}
    }
    List<Map<String,Object>> steps(ExecutionData data){return transaction(data,c->rows(c,"SELECT * FROM replay_steps WHERE tenant_id=? AND run_id=? ORDER BY ordinal",data.tenant,data.run));}
    void begin(ExecutionData data,ReplayPlan.Step step){transaction(data,c->{running(c,data);update(c,"INSERT INTO replay_steps(tenant_id,run_id,ordinal,label,action,request_digest,state) VALUES(?,?,?,?,?,?,'STARTED')",data.tenant,data.run,step.ordinal(),step.label(),step.action().name(),ReplayJson.digest(step));return null;});}
    void finish(ExecutionData data,ReplayPlan.Step step,Map<String,Object> receipt,boolean success){transaction(data,c->{update(c,"UPDATE replay_steps SET state=?,safe_receipt=? WHERE tenant_id=? AND run_id=? AND ordinal=? AND state='STARTED'",success?"DONE":"FAILED",ReplayJson.write(receipt),data.tenant,data.run,step.ordinal());if(!success)update(c,"UPDATE replay_runs SET state='FAIL' WHERE tenant_id=? AND run_id=?",data.tenant,data.run);return null;});}
    void finishRecorded(ExecutionData data,ReplayPlan.Step step,List<Alias> staged,java.util.function.Function<Map<UUID,String>,Map<String,Object>> receipt,boolean success){
        transaction(data,c->{running(c,data);for(Alias a:staged)alias(c,data,a.kind,a.name,a.physical);discover(c,data);Map<String,Object> safe=receipt.apply(safeAliases(c,data));
            if(update(c,"UPDATE replay_steps SET state=?,safe_receipt=? WHERE tenant_id=? AND run_id=? AND ordinal=? AND state='STARTED'",success?"DONE":"FAILED",ReplayJson.write(safe),data.tenant,data.run,step.ordinal())!=1)throw new IllegalStateException("Recorded step is not STARTED");
            if(!success)update(c,"UPDATE replay_runs SET state='FAIL' WHERE tenant_id=? AND run_id=?",data.tenant,data.run);return null;});
    }
    void state(ExecutionData data,String state,String report){transaction(data,c->{var previous=one(c,"SELECT state,final_report FROM replay_runs WHERE tenant_id=? AND run_id=?",data.tenant,data.run);if(previous.get("final_report")!=null||("FAIL".equals(previous.get("state"))&&"PASS".equals(state))||("INCOMPLETE".equals(previous.get("state"))&&!"INCOMPLETE".equals(state)))throw new IllegalStateException("Terminal replay evidence cannot be replaced");update(c,"UPDATE replay_runs SET state=?,final_report=? WHERE tenant_id=? AND run_id=?",state,report,data.tenant,data.run);return null;});}
    private void running(Connection c,ExecutionData data)throws SQLException{if(!"RUNNING".equals(one(c,"SELECT state FROM replay_runs WHERE tenant_id=? AND run_id=?",data.tenant,data.run).get("state")))throw new IllegalStateException("Replay execution is no longer RUNNING");}
    void alias(ExecutionData data,String kind,String name,UUID physical){transaction(data,c->{alias(c,data,kind,name,physical);return null;});}
    private void alias(Connection c,ExecutionData data,String kind,String name,UUID physical)throws SQLException{
        var current=rows(c,"SELECT * FROM replay_aliases WHERE tenant_id=? AND run_id=? AND kind=? AND alias=?",data.tenant,data.run,kind,name);
        if(!current.isEmpty()){if(!physical.equals(current.getFirst().get("physical_id")))throw new IllegalStateException("Replay alias binding changed");return;}
        update(c,"INSERT INTO replay_aliases VALUES(?,?,?,?,?)",data.tenant,data.run,kind,name,physical);
    }
    UUID resolve(ExecutionData data,String kind,String alias){return transaction(data,c->(UUID)one(c,"SELECT physical_id FROM replay_aliases WHERE tenant_id=? AND run_id=? AND kind=? AND alias=?",data.tenant,data.run,kind,alias).get("physical_id"));}
    Map<String,UUID> aliases(ExecutionData data,String kind){return transaction(data,c->{var out=new LinkedHashMap<String,UUID>();for(var row:rows(c,"SELECT * FROM replay_aliases WHERE tenant_id=? AND run_id=? AND kind=? ORDER BY alias",data.tenant,data.run,kind))out.put(text(row,"alias"),(UUID)row.get("physical_id"));return out;});}
    void discover(ExecutionData data){
        transaction(data,c->{discover(c,data);return null;});
    }
    private void discover(Connection c,ExecutionData data)throws SQLException{
        for(var named:rows(c,"SELECT alias,physical_id FROM replay_aliases WHERE tenant_id=? AND run_id=? AND kind='INTENT' ORDER BY alias",data.tenant,data.run)){
            List<Map<String,Object>> related=rows(c,"SELECT a.attempt_id,o.operation_id,c.action_id FROM payment_attempts a LEFT JOIN financial_operations o USING(tenant_id,run_id,attempt_id) LEFT JOIN customer_actions c USING(tenant_id,run_id,operation_id) WHERE a.tenant_id=? AND a.run_id=? AND a.intent_id=?",data.tenant,data.run,named.get("physical_id"));
            if(related.size()>1)throw new IllegalStateException("Multiple local execution bindings");
            if(!related.isEmpty()){var row=related.getFirst();for(String kind:List.of("ATTEMPT","OPERATION","ACTION")){String field=kind.toLowerCase(Locale.ROOT)+"_id";if(row.get(field)!=null)alias(c,data,kind,text(named,"alias")+"-"+kind.toLowerCase(Locale.ROOT),(UUID)row.get(field));}}
        }
    }
    private Map<UUID,String> safeAliases(Connection c,ExecutionData data)throws SQLException{
        var names=new HashMap<UUID,String>();names.put(data.run,data.plan.logicalRunName());for(var row:rows(c,"SELECT * FROM replay_aliases WHERE tenant_id=? AND run_id=?",data.tenant,data.run))names.put((UUID)row.get("physical_id"),text(row,"alias"));
        for(var e:rows(c,"SELECT event_id,adapter_key,sequence FROM simulator_journal_events WHERE tenant_id=? AND run_id=?",data.tenant,data.run)){String operation=names.get(e.get("adapter_key"));if(operation==null)throw new IllegalStateException("Unknown journal reference in receipt");names.put((UUID)e.get("event_id"),operation+"-fact-"+number(e,"sequence"));}return names;
    }
}
