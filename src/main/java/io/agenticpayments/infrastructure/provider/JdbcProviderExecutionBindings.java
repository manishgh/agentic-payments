package io.agenticpayments.infrastructure.provider;
import io.agenticpayments.application.*;
import io.agenticpayments.sdk.OperationIdentity;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Objects;
/** Protected retained route reads; absence/corruption cannot select the legacy adapter. */
public final class JdbcProviderExecutionBindings implements ProviderExecutionBindings,ProviderEventDeliveryTargets {
    private final DataSource database;private final ProviderAdmissionCodec codec=new ProviderAdmissionCodec();
    public JdbcProviderExecutionBindings(DataSource database){this.database=Objects.requireNonNull(database);}
    @Override public ProviderExecutionBinding load(io.agenticpayments.sdk.execution.v1.ExecutionOperation expected) {
        try {
            var identity=expected.identity();String operation=identity.operationId();
            if(operation.length()<38||operation.charAt(36)!=':')throw rejected();
            var operationId=java.util.UUID.fromString(operation.substring(0,36));var run=java.util.UUID.fromString(identity.runId());
            try(var c=database.getConnection();var p=c.prepareStatement("SELECT route,binding_json FROM payment_operation_routes WHERE tenant_id=? AND run_id=? AND operation_id=?")) {
                p.setString(1,identity.tenantId());p.setObject(2,run);p.setObject(3,operationId);
                try(var rows=p.executeQuery()) {
                    if(!rows.next()||!SyntheticProviderRegistration.INTERACTIONS_PROFILE.equals(rows.getString(1)))throw rejected();
                    var retained=codec.decode(rows.getString(2));
                    if(!retained.profile().equals(rows.getString(1))||!expected.equals(retained.operation())||!operationId.equals(retained.coreOperation().identity().operationId()))throw rejected();
                    return retained;
                }
            }
        }catch(SQLException unavailable){throw new io.agenticpayments.sdk.AdapterUnavailableException();}
        catch(ApplicationException safe){throw safe;}
        catch(RuntimeException invalid){throw rejected();}
    }
    @Override public ProviderExecutionBinding load(OperationIdentity id) {
        try(var c=database.getConnection();var p=c.prepareStatement("SELECT route,binding_json FROM payment_operation_routes WHERE tenant_id=? AND run_id=? AND operation_id=?")) {
            p.setString(1,id.scope().tenantId());p.setObject(2,id.scope().runId());p.setObject(3,id.operationId());
            try(var rs=p.executeQuery()) {
                if(!rs.next()||!java.util.Set.of(SyntheticProviderRegistration.PROFILE,SyntheticProviderRegistration.INTERACTIONS_PROFILE).contains(rs.getString(1)))throw rejected();
                ProviderExecutionBinding binding;try{binding=codec.decode(rs.getString(2));}catch(RuntimeException invalid){throw rejected();}
                if(!binding.profile().equals(rs.getString(1))||!binding.coreOperation().identity().equals(id))throw rejected();return binding;
            }
        }catch(SQLException unavailable){throw new io.agenticpayments.sdk.AdapterUnavailableException();}
    }
    private static ApplicationException rejected(){return new ApplicationException(ApplicationFailure.UNSUPPORTED_CAPABILITY);}
}
