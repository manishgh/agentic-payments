package io.agenticpayments.infrastructure.provider;
import io.agenticpayments.application.*;
import io.agenticpayments.sdk.OperationIdentity;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Objects;
/** Protected retained route reads; absence/corruption cannot select the legacy adapter. */
public final class JdbcProviderExecutionBindings implements ProviderExecutionBindings {
    private final DataSource database;private final ProviderAdmissionCodec codec=new ProviderAdmissionCodec();
    public JdbcProviderExecutionBindings(DataSource database){this.database=Objects.requireNonNull(database);}
    @Override public ProviderExecutionBinding load(OperationIdentity id) {
        try(var c=database.getConnection();var p=c.prepareStatement("SELECT route,binding_json FROM payment_operation_routes WHERE tenant_id=? AND run_id=? AND operation_id=?")) {
            p.setString(1,id.scope().tenantId());p.setObject(2,id.scope().runId());p.setObject(3,id.operationId());
            try(var rs=p.executeQuery()) {
                if(!rs.next()||!SyntheticProviderRegistration.PROFILE.equals(rs.getString(1)))throw rejected();
                ProviderExecutionBinding binding;try{binding=codec.decode(rs.getString(2));}catch(RuntimeException invalid){throw rejected();}
                if(!binding.coreOperation().identity().equals(id))throw rejected();return binding;
            }
        }catch(SQLException unavailable){throw new io.agenticpayments.sdk.AdapterUnavailableException();}
    }
    private static ApplicationException rejected(){return new ApplicationException(ApplicationFailure.UNSUPPORTED_CAPABILITY);}
}
