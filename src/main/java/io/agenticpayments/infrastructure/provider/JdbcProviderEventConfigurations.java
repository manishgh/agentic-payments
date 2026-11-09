package io.agenticpayments.infrastructure.provider;

import com.fasterxml.jackson.databind.*;
import io.agenticpayments.application.*;
import io.agenticpayments.domain.PaymentRules;
import javax.sql.DataSource;
import java.sql.*;
import java.util.Objects;

/** Durable nonsecret route commitments. Secrets and executable delegates remain in trusted host configuration. */
public final class JdbcProviderEventConfigurations implements ProviderEventConfigurationStore {
    private final DataSource database;
    private final ObjectMapper json=new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY,true);
    public JdbcProviderEventConfigurations(DataSource database){this.database=Objects.requireNonNull(database);}
    public void register(Configuration configuration){check(configuration,true);}
    public void verify(Configuration configuration){check(configuration,false);}
    private void check(Configuration configuration,boolean enroll) {
        Objects.requireNonNull(configuration);String route;
        try{route=json.writeValueAsString(configuration.route());}catch(Exception invalid){throw new ApplicationException(ApplicationFailure.INVALID_REQUEST);}
        String key=PaymentRules.sha256(route);
        try(var c=database.getConnection()) {
            c.setAutoCommit(false);
            try {
                if(enroll)try(var p=c.prepareStatement("INSERT INTO provider_event_configurations(route_digest,route_json,descriptor_digest) VALUES(?,?,?) ON CONFLICT DO NOTHING")) {
                    p.setString(1,key);p.setString(2,route);p.setString(3,configuration.descriptorDigest());p.executeUpdate();
                }
                try(var p=c.prepareStatement("SELECT route_json,descriptor_digest FROM provider_event_configurations WHERE route_digest=?")) {
                    p.setString(1,key);try(var rows=p.executeQuery()) {
                        if(!rows.next())throw new ApplicationException(ApplicationFailure.UNSUPPORTED_CAPABILITY);
                        if(!route.equals(rows.getString(1))||!configuration.descriptorDigest().equals(rows.getString(2)))
                            throw new ApplicationException(ApplicationFailure.CONFIGURATION_CHANGED);
                    }
                }
                c.commit();
            }catch(Exception failure){c.rollback();throw failure;}
        }catch(ApplicationException safe){throw safe;}
        catch(Exception unavailable){throw new ApplicationException(ApplicationFailure.TEMPORARILY_UNAVAILABLE_SAME_COMMAND);}
    }
}
