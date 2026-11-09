package io.agenticpayments.infrastructure.provider;

import io.agenticpayments.application.*;
import io.agenticpayments.application.ProviderEventDeliveryRoutes.Namespace;
import io.agenticpayments.sdk.execution.v1.ExecutionOperation;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Objects;

/** Keyless resolution of protected authenticated dispute provenance within its immutable ownership namespace. */
public final class JdbcProviderEventDisputes implements ProviderEventDisputeVerifier {
    private final DataSource database;private final Namespace namespace;private final ProviderJournalCodec codec=new ProviderJournalCodec();
    public JdbcProviderEventDisputes(DataSource database,Namespace namespace){this.database=Objects.requireNonNull(database);this.namespace=Objects.requireNonNull(namespace);}
    @Override public void requireQuarantined(ExecutionOperation expected) {
        if(expected==null||!namespace.equals(Namespace.from(expected)))throw invalid();
        try(var c=database.getConnection();var p=c.prepareStatement("SELECT operation_json FROM provider_event_quarantine WHERE tenant_id=? AND merchant_id=? AND run_id=? AND operation_fingerprint=?")) {
            p.setString(1,namespace.tenantId());p.setString(2,namespace.merchantId());p.setString(3,namespace.runId());p.setString(4,expected.fingerprint());
            try(var rows=p.executeQuery()) {
                if(!rows.next())throw invalid();
                ExecutionOperation retained;try{retained=codec.decodeOperation(rows.getString(1));}catch(RuntimeException malformed){throw invalid();}
                if(!expected.equals(retained))throw invalid();
            }
        }catch(SQLException unavailable){throw new ApplicationException(ApplicationFailure.TEMPORARILY_UNAVAILABLE_SAME_COMMAND);}
    }
    private static ApplicationException invalid(){return new ApplicationException(ApplicationFailure.AUTHORITY_BINDING_INVALID);}
}
