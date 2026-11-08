package io.agenticpayments.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agenticpayments.infrastructure.DatabaseMigrator;
import io.agenticpayments.infrastructure.JdbcSimulatorAdapter;
import io.agenticpayments.infrastructure.provider.*;
import io.agenticpayments.sdk.*;
import io.agenticpayments.sdk.execution.v1.*;
import io.agenticpayments.sdk.execution.v1.compat.*;
import org.postgresql.ds.PGSimpleDataSource;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

/** Independent operation/mapping oracles, SQL inspection and test-only process boundaries. */
final class ProviderJournalTestSupport {
    static final Instant NOW=Instant.parse("2026-11-08T10:00:00.123456789Z"), EXPIRY=NOW.plusSeconds(600);
    static final ExecutionBinding BINDING=new ExecutionBinding("simulator","account-ref","eu-test","config-ref","1","credential-ref","1","platform-owner");
    static final List<String> IMMUTABLE_COLUMNS=List.of("tenant_id","merchant_id","run_id","caller_key","operation_id","operation_fingerprint","operation_json",
            "legacy_tenant_id","legacy_run_id","legacy_adapter_key","legacy_json","codec_version","mapping_policy_version","scenario_policy_ref","scenario_policy_version");
    private ProviderJournalTestSupport() {}
    static PGSimpleDataSource database(){
        var database=new PGSimpleDataSource();String url=System.getenv("PAYMENTS_DB_URL");
        if(url==null)throw new IllegalStateException("PostgreSQL integration-test environment is required");
        database.setURL(url);database.setUser(System.getenv("PAYMENTS_DB_USER"));database.setPassword(System.getenv("PAYMENTS_DB_PASSWORD"));return database;
    }
    static ProviderJournalConfiguration configuration(String tenant,String merchant,String run){
        return new ProviderJournalConfiguration(tenant,merchant,run,BINDING,ProviderJournalConfiguration.MAPPING_POLICY_VERSION,"independent-scenario-policy","1",ignored->"success");
    }
    static ExecutionOperation operation(ProviderJournalConfiguration config,String key,String operation){
        return operation(config,new ExecutionIdentity(config.tenantId(),config.merchantId(),config.runId(),"intent-"+operation,"attempt-"+operation,operation,key),new ExactMoney("EUR",2,1250));
    }
    static ExecutionOperation operation(ProviderJournalConfiguration config,ExecutionIdentity identity,ExactMoney money){
        ExecutionOffer offer=LocalFakeExecutionAdapter.offer();var c=offer.combinations().getFirst();
        var requirement=new CapabilitySelection.Requirement(ExecutionOffer.VERSION,offer.profileVersion(),offer.providerId(),offer.implementationRevision(),
                config.binding().accountRef(),config.binding().region(),c.methodId(),c.credentialFormatId(),c.actions(),OperationPurpose.CHARGE,c.preparationEffect(),c.capabilities(),money);
        return ExecutionOperation.create(identity,config.binding(),CapabilitySelection.select(offer,requirement,config.binding()),money,OperationPurpose.CHARGE);
    }
    /** Independent oracle for the frozen published compatibility mapping, never calls author helpers. */
    static ChargeOperation legacy(ExecutionOperation op,String scenario){
        var id=op.identity();return new ChargeOperation(new OperationIdentity(new ExecutionScope(id.tenantId(),uuid("run",id.runId()),id.merchantId()),
                uuid("intent",id.intentId()),uuid("attempt",id.attemptId()),uuid("operation",id.operationId()),
                uuid("key",String.join("\n",id.tenantId(),id.merchantId(),id.runId(),id.callerIdempotencyKey())),op.fingerprint()),new Money(op.money().currency(),op.money().minorUnits()),scenario);
    }
    private static UUID uuid(String tag,String value){return UUID.nameUUIDFromBytes(("local-fake-execution-1\n"+tag+"\n"+value).getBytes(StandardCharsets.UTF_8));}
    static final class Fixture {
        final DataSource database;final ProviderJournalConfiguration config;final ExecutionOperation operation;final ChargeOperation legacy;
        Fixture(){this(database(),"provider-journal-"+UUID.randomUUID().toString().replace("-",""),"merchant-demo","run-"+UUID.randomUUID());DatabaseMigrator.migrate(database);observations(database);}
        Fixture(DataSource database,String tenant,String merchant,String run){this.database=database;this.config=configuration(tenant,merchant,run);this.operation=operation(config,"caller-primary","operation-primary");this.legacy=legacy(operation,"success");}
        JdbcCompatibilityBindingJournal journal(){return new JdbcCompatibilityBindingJournal(database,config);}
        LocalFakeExecutionAdapter bridge(){return bridge(journal(),new Observer(database,config,"normal"));}
        LocalFakeExecutionAdapter bridge(CompatibilityBindingJournal journal,PaymentAdapter delegate){return new LocalFakeExecutionAdapter(delegate,journal,config.binding(),config.tenantId(),config.merchantId(),config.runId(),NOW,EXPIRY,ignored->"success");}
        Map<String,Object> binding(){return row(database,"SELECT * FROM provider_compatibility_bindings WHERE tenant_id=? AND merchant_id=? AND run_id=? AND caller_key=?",namespace(operation));}
        long bindings(){return count(database,"SELECT count(*) FROM provider_compatibility_bindings WHERE tenant_id=? AND merchant_id=? AND run_id=?",config.tenantId(),config.merchantId(),config.runId());}
        long evidence(){return count(database,"SELECT count(*) FROM provider_compatibility_evidence WHERE tenant_id=? AND merchant_id=? AND run_id=? AND caller_key=?",namespace(operation));}
        long conflicts(){return count(database,"SELECT count(*) FROM provider_compatibility_conflicts WHERE requested_tenant_id=? AND requested_merchant_id=? AND requested_run_id=? AND requested_caller_key=?",namespace(operation));}
        long peerRows(){return count(database,"SELECT count(*) FROM simulator_operation_journal WHERE tenant_id=? AND run_id=? AND adapter_key=?",legacy.identity().scope().tenantId(),legacy.identity().scope().runId(),legacy.identity().adapterKey());}
        long peerEvents(){return count(database,"SELECT count(*) FROM simulator_journal_events WHERE tenant_id=? AND run_id=? AND adapter_key=?",legacy.identity().scope().tenantId(),legacy.identity().scope().runId(),legacy.identity().adapterKey());}
        String peerState(){var row=row(database,"SELECT outcome FROM simulator_operation_journal WHERE tenant_id=? AND run_id=? AND adapter_key=?",legacy.identity().scope().tenantId(),legacy.identity().scope().runId(),legacy.identity().adapterKey());return row==null?null:row.get("outcome").toString();}
        long invocations(String kind){return count(database,"SELECT count(*) FROM provider_test_invocations WHERE tenant_id=? AND merchant_id=? AND run_id=? AND kind=?",config.tenantId(),config.merchantId(),config.runId(),kind);}
        long cache(){return count(database,"SELECT count(*) FROM provider_test_finite_cache WHERE tenant_id=? AND merchant_id=? AND run_id=?",config.tenantId(),config.merchantId(),config.runId());}
        void clearCache(){execute(database,"DELETE FROM provider_test_finite_cache WHERE tenant_id=? AND merchant_id=? AND run_id=?",config.tenantId(),config.merchantId(),config.runId());}
    }
    static void observations(DataSource database){
        execute(database,"CREATE TABLE IF NOT EXISTS provider_test_invocations(tenant_id text NOT NULL,merchant_id text NOT NULL,run_id text NOT NULL,kind text NOT NULL)");
        execute(database,"CREATE TABLE IF NOT EXISTS provider_test_finite_cache(tenant_id text NOT NULL,merchant_id text NOT NULL,run_id text NOT NULL,adapter_key uuid NOT NULL,response_json text NOT NULL,PRIMARY KEY(tenant_id,merchant_id,run_id,adapter_key))");
    }
    static class Observer implements PaymentAdapter {
        final DataSource database;final ProviderJournalConfiguration config;final JdbcSimulatorAdapter delegate;final String lookup;
        Observer(DataSource database,ProviderJournalConfiguration config,String lookup){this.database=database;this.config=config;this.lookup=lookup;delegate=new JdbcSimulatorAdapter(database);}
        public CapabilityOffer capabilities(){return delegate.capabilities();}
        public Preparation prepare(ChargeOperation operation){return delegate.prepare(operation);}
        void observed(String kind){ProviderJournalTestSupport.execute(database,"INSERT INTO provider_test_invocations VALUES(?,?,?,?)",config.tenantId(),config.merchantId(),config.runId(),kind);}
        public Fact execute(ChargeOperation operation){
            observed("EXECUTE");
            var retained=row(database,"SELECT response_json FROM provider_test_finite_cache WHERE tenant_id=? AND merchant_id=? AND run_id=? AND adapter_key=?",config.tenantId(),config.merchantId(),config.runId(),operation.identity().adapterKey());
            var json=new ObjectMapper();
            try{
                if(retained!=null){observed("CACHE_HIT");Fact fact=json.readValue(retained.get("response_json").toString(),Fact.class);
                    if(!fact.identity().equals(operation.identity())||!fact.money().equals(operation.money()))throw new IllegalStateException("Invalid synthetic cached response");return fact;}
                observed("CACHE_MISS");observed("DELEGATE_EXECUTE");Fact fact=delegate.execute(operation);
                ProviderJournalTestSupport.execute(database,"INSERT INTO provider_test_finite_cache(tenant_id,merchant_id,run_id,adapter_key,response_json) VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",config.tenantId(),config.merchantId(),config.runId(),operation.identity().adapterKey(),json.writeValueAsString(fact));return fact;
            }catch(java.io.IOException failure){throw new IllegalStateException("Synthetic cached response unavailable");}
        }
        public LookupResult lookup(OperationIdentity identity){observed("LOOKUP");return lookup.equals("absent")?LookupResult.notFound():lookup.equals("unavailable")?LookupResult.unavailable():delegate.lookup(identity);}
        public Fact sealNoEffect(ChargeOperation operation){observed("FENCE");return delegate.sealNoEffect(operation);}
    }
    static CompatibilityBindingJournal crashJournal(CompatibilityBindingJournal journal,String phase){return new CompatibilityBindingJournal(){
        public ChargeOperation bind(ExecutionOperation operation,ChargeOperation candidate){var result=journal.bind(operation,candidate);if(phase.equals("bind"))Runtime.getRuntime().halt(73);return result;}
        public DispatchClaim claim(SubmissionRequest request){var result=journal.claim(request);if(phase.equals("claim"))Runtime.getRuntime().halt(73);return result;}
        public ExecutionEvidence record(ExecutionEvidence evidence){try{var result=journal.record(evidence);if(phase.equals("record"))Runtime.getRuntime().halt(73);return result;}
            catch(ExecutionFailure failure){if(phase.equals("conflict")&&failure.code()==ExecutionFailure.Code.EVIDENCE_CONFLICT)Runtime.getRuntime().halt(73);throw failure;}}
        public Optional<ExecutionEvidence> read(ExecutionOperation operation){return journal.read(operation);}
    };}
    static DataSource haltBeforeCommit(DataSource base){return new ForwardingDataSource(base){
        @Override Connection wrap(Connection connection){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            if(method.getName().equals("commit"))Runtime.getRuntime().halt(73);
            return invoke(method,connection,args);
        });}
    };}
    abstract static class ForwardingDataSource implements DataSource {
        final DataSource base;ForwardingDataSource(DataSource base){this.base=base;}Connection wrap(Connection c){return c;}
        public Connection getConnection()throws SQLException{return wrap(base.getConnection());}public Connection getConnection(String u,String p)throws SQLException{return wrap(base.getConnection(u,p));}
        public PrintWriter getLogWriter()throws SQLException{return base.getLogWriter();}public void setLogWriter(PrintWriter p)throws SQLException{base.setLogWriter(p);}
        public void setLoginTimeout(int s)throws SQLException{base.setLoginTimeout(s);}public int getLoginTimeout()throws SQLException{return base.getLoginTimeout();}
        public Logger getParentLogger(){return Logger.getLogger("provider-journal-test");}public <T>T unwrap(Class<T> type)throws SQLException{return base.unwrap(type);}public boolean isWrapperFor(Class<?> type)throws SQLException{return base.isWrapperFor(type);}
    }
    static Object invoke(Method method,Object target,Object[] args)throws Throwable{try{return method.invoke(target,args);}catch(InvocationTargetException error){throw error.getCause();}}
    static int child(Fixture fixture,String mode){
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        try{
            Process process=new ProcessBuilder(java,"-cp",classpath,ProviderJournalCrashProcess.class.getName(),mode,fixture.config.tenantId(),fixture.config.merchantId(),fixture.config.runId()).redirectErrorStream(true).start();
            try{
                assertTrue(process.waitFor(30,TimeUnit.SECONDS),"Child process must complete within its deadline");
                String safe=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
                if(process.exitValue()==73)assertTrue(safe.isEmpty(),"Crash child must emit no configuration or payload");
                else assertEquals("PROVIDER_JOURNAL_OK",safe,"Child emits only a fixed safe result marker");
                return process.exitValue();
            }finally{if(process.isAlive())process.destroyForcibly().waitFor(5,TimeUnit.SECONDS);}
        }catch(Exception failure){throw new AssertionError("Provider journal child unavailable",failure);}
    }
    static ExecutionEvidence evidence(ExecutionOperation op,String id,ExecutionEvidence.State state){return new ExecutionEvidence(op,id,
            state==ExecutionEvidence.State.UNKNOWN?Optional.empty():Optional.of("resource-"+op.fingerprint()),state==ExecutionEvidence.State.NO_EFFECT_FENCED?ExecutionEvidence.Source.FENCE:ExecutionEvidence.Source.RECONCILIATION,NOW,state,Optional.empty());}
    static Object[] namespace(ExecutionOperation operation){var id=operation.identity();return new Object[]{id.tenantId(),id.merchantId(),id.runId(),id.callerIdempotencyKey()};}
    static Map<String,Object> immutable(Map<String,Object> row){var result=new LinkedHashMap<String,Object>();for(String column:IMMUTABLE_COLUMNS)result.put(column,row.get(column));return result;}
    static Map<String,Object> row(DataSource database,String sql,Object... arguments){
        try(var connection=database.getConnection();var statement=connection.prepareStatement(sql)){
            for(int i=0;i<arguments.length;i++)statement.setObject(i+1,arguments[i]);
            try(var rows=statement.executeQuery()){if(!rows.next())return null;var result=new LinkedHashMap<String,Object>();var meta=rows.getMetaData();for(int i=1;i<=meta.getColumnCount();i++)result.put(meta.getColumnLabel(i),rows.getObject(i));return result;}
        }catch(SQLException failure){throw new AssertionError("Independent provider SQL observation failed");}
    }
    static long count(DataSource database,String sql,Object... arguments){var row=row(database,sql,arguments);assertNotNull(row);return ((Number)row.values().iterator().next()).longValue();}
    static void execute(DataSource database,String sql,Object... arguments){
        try(var connection=database.getConnection();var statement=connection.prepareStatement(sql)){for(int i=0;i<arguments.length;i++)statement.setObject(i+1,arguments[i]);statement.execute();}
        catch(SQLException failure){throw new AssertionError("Independent provider SQL setup failed");}
    }
}
