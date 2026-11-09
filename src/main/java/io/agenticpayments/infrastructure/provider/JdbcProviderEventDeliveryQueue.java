package io.agenticpayments.infrastructure.provider;

import io.agenticpayments.application.ProviderEventDeliveryQueue;
import io.agenticpayments.sdk.AdapterUnavailableException;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Duration;
import java.util.*;

/** PostgreSQL owns queue time, atomic claims and lease fencing; no external work runs in a transaction. */
public final class JdbcProviderEventDeliveryQueue implements ProviderEventDeliveryQueue {
    private final DataSource database;
    private final String tenant,merchant,run;
    private final ProviderJournalCodec codec=new ProviderJournalCodec();
    public JdbcProviderEventDeliveryQueue(DataSource database,String tenant,String merchant,String run){
        this.database=Objects.requireNonNull(database);this.tenant=token(tenant);this.merchant=token(merchant);this.run=token(run);
    }
    @Override public Optional<Claim> claim(Duration lease){
        Objects.requireNonNull(lease);if(lease.compareTo(Duration.ofMillis(1))<0||lease.compareTo(Duration.ofHours(1))>0)throw new IllegalArgumentException("Invalid delivery lease.");
        return transaction(c->{
            // Reap final-attempt process deaths too: they never require an acknowledgement to become visible.
            try(var p=c.prepareStatement("UPDATE provider_event_delivery SET status='DEAD',lease_token=NULL,lease_until=NULL,failure_code='ATTEMPTS_EXHAUSTED' WHERE tenant_id=? AND merchant_id=? AND run_id=? AND attempts>=max_attempts AND ((status='LEASED' AND lease_until<=clock_timestamp()) OR status='READY')")){
                scope(p);p.executeUpdate();
            }
            String id;
            try(var p=c.prepareStatement("SELECT delivery_ref FROM provider_event_delivery WHERE tenant_id=? AND merchant_id=? AND run_id=? AND attempts<max_attempts AND ((status='READY' AND available_at<=clock_timestamp()) OR (status='LEASED' AND lease_until<=clock_timestamp())) ORDER BY CASE WHEN kind='QUARANTINE' THEN 0 ELSE 1 END,available_at,created_at,delivery_ref FOR UPDATE SKIP LOCKED LIMIT 1")){
                scope(p);try(var rs=p.executeQuery()){if(!rs.next())return Optional.empty();id=rs.getString(1);}
            }
            UUID leaseToken=UUID.randomUUID();
            try(var p=c.prepareStatement("UPDATE provider_event_delivery SET status='LEASED',attempts=attempts+1,lease_token=?,lease_until=clock_timestamp()+(? * interval '1 millisecond') WHERE delivery_ref=? RETURNING *")){
                p.setObject(1,leaseToken);p.setLong(2,lease.toMillis());p.setString(3,id);
                try(var rs=p.executeQuery()){
                    if(!rs.next())throw new SQLException("Delivery claim unavailable.");
                    var operation=codec.decodeOperation(rs.getString("operation_json"));var identity=operation.identity();
                    if(!tenant.equals(identity.tenantId())||!merchant.equals(identity.merchantId())||!run.equals(identity.runId())
                            ||!operation.fingerprint().equals(rs.getString("operation_fingerprint")))throw new SQLException("Invalid delivery binding.");
                    var policy=new Policy(rs.getString("policy_ref"),rs.getString("policy_version"),rs.getString("key_ref"),rs.getString("key_version"));
                    var delivery=new Delivery(id,Kind.valueOf(rs.getString("kind")),operation,Optional.ofNullable(rs.getString("envelope_ref")),policy);
                    return Optional.of(new Claim(delivery,leaseToken,rs.getInt("attempts"),rs.getInt("max_attempts")));
                }
            }
        });
    }
    @Override public boolean settle(Claim claim,Resolution resolution){
        Objects.requireNonNull(claim);Objects.requireNonNull(resolution);
        var identity=claim.delivery().operation().identity();
        if(!tenant.equals(identity.tenantId())||!merchant.equals(identity.merchantId())||!run.equals(identity.runId()))return false;
        // A direct queue adapter caller cannot bypass the persisted retry budget.
        String status=resolution.disposition()==Disposition.RETRY?(claim.attempt()>=claim.maxAttempts()?"DEAD":"READY"):resolution.disposition().name();
        return transaction(c->{try(var p=c.prepareStatement("UPDATE provider_event_delivery SET status=?,failure_code=?,available_at=clock_timestamp()+(? * interval '1 millisecond'),lease_token=NULL,lease_until=NULL WHERE delivery_ref=? AND tenant_id=? AND merchant_id=? AND run_id=? AND status='LEASED' AND lease_token=? AND lease_until>clock_timestamp() AND attempts=? AND max_attempts=?")){
            p.setString(1,status);p.setString(2,resolution.failure().name());p.setLong(3,resolution.retryAfter().toMillis());p.setString(4,claim.delivery().deliveryReference());
            p.setString(5,tenant);p.setString(6,merchant);p.setString(7,run);p.setObject(8,claim.leaseToken());p.setInt(9,claim.attempt());p.setInt(10,claim.maxAttempts());return p.executeUpdate()==1;
        }});
    }
    private void scope(PreparedStatement p)throws SQLException{p.setString(1,tenant);p.setString(2,merchant);p.setString(3,run);}
    private <T>T transaction(Work<T> work){
        try(var c=database.getConnection()){
            c.setAutoCommit(false);try{T result=work.run(c);c.commit();return result;}
            catch(Exception failure){try{c.rollback();}catch(SQLException ignored){}throw new AdapterUnavailableException();}
        }catch(Exception unsafe){throw new AdapterUnavailableException();}
    }
    private static String token(String value){if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))throw new IllegalArgumentException("Invalid delivery scope.");return value;}
    @FunctionalInterface private interface Work<T>{T run(Connection connection)throws Exception;}
}
