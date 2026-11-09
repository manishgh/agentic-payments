package io.agenticpayments.infrastructure.provider;

import io.agenticpayments.application.*;
import io.agenticpayments.sdk.execution.v1.ExecutionBinding;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Durable local synthetic peer. Its decisions are separate from financial journals and core transactions. */
public final class JdbcSyntheticAuthentication implements ProviderActionRegistry,ProviderAuthenticationResolver,SyntheticAuthenticationDecisions {
    private final DataSource database;
    private final String tenant,merchant,run;
    private final ExecutionBinding configuration;
    private final ProviderAuthenticationCodec codec=new ProviderAuthenticationCodec();
    public JdbcSyntheticAuthentication(DataSource database,String tenant,String merchant,String run,ExecutionBinding configuration) {
        this.database=Objects.requireNonNull(database);this.tenant=Objects.requireNonNull(tenant);this.merchant=Objects.requireNonNull(merchant);
        this.run=Objects.requireNonNull(run);this.configuration=Objects.requireNonNull(configuration);
        if(!configuration.providerId().equals("simulator"))throw new IllegalArgumentException("Unsupported synthetic authentication configuration.");
    }
    public JdbcSyntheticAuthentication(DataSource database,ProviderJournalConfiguration configuration) {
        this(database,configuration.tenantId(),configuration.merchantId(),configuration.runId(),configuration.binding());
    }
    @Override public void register(ProviderActionBinding binding) {
        scoped(binding);
        transaction(connection->{
            try(var statement=connection.prepareStatement("INSERT INTO provider_authentication_actions(tenant_id,merchant_id,run_id,caller_key,operation_id,action_ref,binding_json) VALUES(?,?,?,?,?,?,?) ON CONFLICT DO NOTHING")) {
                values(statement,tenant,merchant,run,binding.operation().identity().callerIdempotencyKey(),binding.operation().identity().operationId(),binding.action().actionRef(),codec.encodeBinding(binding));statement.executeUpdate();
            }
            read(connection,binding,true);return null;
        });
    }
    @Override public void decide(ProviderActionBinding binding,String subjectId,UUID requestId,String decision,Instant trustedNow) {
        scoped(binding);
        if(subjectId==null||subjectId.isBlank()||subjectId.length()>128||requestId==null||trustedNow==null
                ||decision==null||!Set.of("approve","deny").contains(decision))throw failure(ApplicationFailure.INVALID_REQUEST);
        transaction(connection->{
            var row=read(connection,binding,true);
            if(row.observation()!=null) {
                if(!row.subject().equals(subjectId)||!row.decision().equals(decision)||!row.requestId().equals(requestId))throw failure(ApplicationFailure.IDEMPOTENCY_CONFLICT);
                return null;
            }
            if(!binding.action().usableFor(binding.operation(),trustedNow))throw failure(ApplicationFailure.ACTION_EXPIRED);
            var observation=new AuthenticationObservation(binding,subjectId,UUID.randomUUID(),
                    decision.equals("approve")?AuthenticationObservation.Outcome.VERIFIED:AuthenticationObservation.Outcome.DENIED,trustedNow);
            try(var statement=connection.prepareStatement("UPDATE provider_authentication_actions SET request_id=?,subject_id=?,decision=?,observation_json=? WHERE tenant_id=? AND merchant_id=? AND run_id=? AND caller_key=?")) {
                values(statement,requestId,subjectId,decision,codec.encodeObservation(observation),tenant,merchant,run,binding.operation().identity().callerIdempotencyKey());
                if(statement.executeUpdate()!=1)throw failure(ApplicationFailure.TRUSTED_RESOLUTION_REQUIRED);
            }
            return null;
        });
    }
    @Override public Optional<AuthenticationObservation> resolve(ProviderActionBinding binding) {
        scoped(binding);return transaction(connection->Optional.ofNullable(read(connection,binding,false).observation()));
    }
    private record Row(UUID requestId,String subject,String decision,AuthenticationObservation observation){}
    private Row read(Connection connection,ProviderActionBinding binding,boolean lock)throws SQLException {
        try(var statement=connection.prepareStatement("SELECT operation_id,action_ref,binding_json,request_id,subject_id,decision,observation_json FROM provider_authentication_actions WHERE tenant_id=? AND merchant_id=? AND run_id=? AND caller_key=?"+(lock?" FOR UPDATE":""))) {
            values(statement,tenant,merchant,run,binding.operation().identity().callerIdempotencyKey());
            try(var rows=statement.executeQuery()) {
                if(!rows.next())throw failure(ApplicationFailure.TRUSTED_RESOLUTION_REQUIRED);
                var stored=codec.decodeBinding(rows.getString("binding_json"));
                if(!stored.equals(binding)||!stored.operation().identity().operationId().equals(rows.getString("operation_id"))
                        ||!stored.action().actionRef().equals(rows.getString("action_ref")))throw failure(ApplicationFailure.AUTHORITY_BINDING_INVALID);
                String encoded=rows.getString("observation_json"),subject=rows.getString("subject_id"),decision=rows.getString("decision");
                Object request=rows.getObject("request_id");
                if(encoded==null) {
                    if(subject!=null||decision!=null||request!=null)throw failure(ApplicationFailure.AUTHORITY_BINDING_INVALID);
                    return new Row(null,null,null,null);
                }
                var observation=codec.decodeObservation(encoded);
                if(subject==null||decision==null||request==null||!Set.of("approve","deny").contains(decision)
                        ||!observation.binding().equals(stored)||!observation.subjectId().equals(subject)
                        ||!observation.observedAt().isBefore(stored.action().expiresAt())
                        ||observation.outcome()!=(decision.equals("approve")?AuthenticationObservation.Outcome.VERIFIED:AuthenticationObservation.Outcome.DENIED))
                    throw failure(ApplicationFailure.AUTHORITY_BINDING_INVALID);
                return new Row((UUID)request,subject,decision,observation);
            }
        }
    }
    private void scoped(ProviderActionBinding binding) {
        Objects.requireNonNull(binding);var identity=binding.operation().identity();
        if(!tenant.equals(identity.tenantId())||!merchant.equals(identity.merchantId())||!run.equals(identity.runId())
                ||!configuration.equals(binding.operation().binding()))throw failure(ApplicationFailure.AUTHORITY_BINDING_INVALID);
    }
    @FunctionalInterface private interface Work<T>{T run(Connection connection)throws SQLException;}
    private <T>T transaction(Work<T> work) {
        try(var connection=database.getConnection()) {
            connection.setAutoCommit(false);
            try {T value=work.run(connection);connection.commit();return value;}
            catch(SQLException|RuntimeException error) {
                try{connection.rollback();}catch(SQLException ignored){}
                throw error;
            }
        }catch(ApplicationException safe){throw safe;}
        catch(SQLException error){throw failure("23505".equals(error.getSQLState())?ApplicationFailure.IDEMPOTENCY_CONFLICT:ApplicationFailure.TEMPORARILY_UNAVAILABLE_SAME_COMMAND);}
        catch(RuntimeException unsafe){throw failure(ApplicationFailure.TEMPORARILY_UNAVAILABLE_SAME_COMMAND);}
    }
    private static void values(PreparedStatement statement,Object... values)throws SQLException {
        for(int index=0;index<values.length;index++)statement.setObject(index+1,values[index]);
    }
    private static PaymentException failure(ApplicationFailure failure){return new PaymentException(failure);}
}
