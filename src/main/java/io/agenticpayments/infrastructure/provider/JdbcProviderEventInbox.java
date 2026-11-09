package io.agenticpayments.infrastructure.provider;

import io.agenticpayments.application.*;
import io.agenticpayments.sdk.execution.v1.*;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** Protected independent inbox transactions. No provider activity or core financial writes occur here. */
public final class JdbcProviderEventInbox implements ProviderEventIngress,ProviderEventDeliveryIngress,ProviderEventDisputeVerifier,ProviderEventHealth,ProviderEventVerifier,ProviderEventNormalizer {
    private final DataSource database;
    private final ProviderEventConfiguration configuration;
    private final ProviderEventAuthenticator authenticator;
    private final ProviderEventDecoder decoder;
    private final ProviderJournalCodec codec=new ProviderJournalCodec();
    public JdbcProviderEventInbox(DataSource database,ProviderEventConfiguration configuration,
                                  ProviderEventAuthenticator authenticator,ProviderEventDecoder decoder){
        this.database=Objects.requireNonNull(database);this.configuration=Objects.requireNonNull(configuration);
        this.authenticator=Objects.requireNonNull(authenticator);this.decoder=Objects.requireNonNull(decoder);
    }
    @Override public String retain(ExecutionOperation expected,byte[] body,String signature){
        return retain(expected,body,signature,false);
    }
    @Override public String accept(ExecutionOperation expected,byte[] body,String signature){
        return retain(expected,body,signature,true);
    }
    private String retain(ExecutionOperation expected,byte[] body,String signature,boolean automatic){
        configuration.validate(expected);
        if(body==null||body.length==0||body.length>16384||signature==null||!signature.matches("[0-9a-f]{64}"))throw invalid();
        byte[] retained=body.clone();String reference=UUID.randomUUID().toString();
        var policy=List.of(configuration.policyReference(),configuration.policyVersion(),configuration.keyReference(),configuration.keyVersion());
        if(automatic)authenticated(expected,new Envelope(retained,signature,policy));
        return transaction(c->{
            if(automatic)deliveryLock(c,expected.fingerprint());
            healthy(c,expected);
            write(c,"INSERT INTO provider_event_envelopes(envelope_ref,tenant_id,merchant_id,run_id,operation_fingerprint,operation_json,raw_body,signature,policy_ref,policy_version,key_ref,key_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    reference,configuration.tenantId(),configuration.merchantId(),configuration.runId(),expected.fingerprint(),codec.encodeOperation(expected),retained,signature,
                    configuration.policyReference(),configuration.policyVersion(),configuration.keyReference(),configuration.keyVersion());
            if(automatic)enqueue(c,expected,"EVENT",reference,policy);
            return reference;
        });
    }
    @Override public void requireQuarantined(ExecutionOperation expected){
        Objects.requireNonNull(expected);
        transaction(c->{
            var row=one(c,"SELECT * FROM provider_event_quarantine WHERE tenant_id=? AND merchant_id=? AND run_id=? AND operation_fingerprint=?",
                    configuration.tenantId(),configuration.merchantId(),configuration.runId(),expected.fingerprint());
            // Resolve exact durable provenance without requiring the prior owner's key/config revision.
            if(row==null||!expected.equals(operation(row,false)))throw invalid();return null;
        });
    }
    @Override public void requireHealthy(ExecutionOperation expected){configuration.validate(expected);transaction(c->{healthy(c,expected);return null;});}
    @Override public VerifiedEventReference verify(EventVerificationRequest request){
        var expected=request.operation();configuration.validate(expected);
        Envelope envelope=transaction(c->{healthy(c,expected);return envelope(c,expected,request.eventEnvelopeRef());});
        // This order is part of the contract: no JSON/parser invocation precedes authentication.
        var decoded=authenticated(expected,envelope);
        var decision=transaction(c->{
            healthy(c,expected);String verifiedReference=UUID.randomUUID().toString();
            write(c,"INSERT INTO provider_verified_events(tenant_id,merchant_id,run_id,provider_id,account_ref,event_id,verified_ref,envelope_ref,operation_fingerprint,operation_json,evidence_json) VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                    configuration.tenantId(),configuration.merchantId(),configuration.runId(),configuration.binding().providerId(),configuration.binding().accountRef(),decoded.eventId(),
                    verifiedReference,request.eventEnvelopeRef(),expected.fingerprint(),codec.encodeOperation(expected),codec.encodeEvidence(decoded.evidence()));
            var row=one(c,"SELECT * FROM provider_verified_events WHERE tenant_id=? AND merchant_id=? AND run_id=? AND provider_id=? AND account_ref=? AND event_id=? FOR UPDATE",
                    configuration.tenantId(),configuration.merchantId(),configuration.runId(),configuration.binding().providerId(),configuration.binding().accountRef(),decoded.eventId());
            if(row==null)throw invalid();
            // The prior owner may use another retained configuration/key revision in this same
            // event namespace. Its already verified provenance must not be rebound to ours.
            ExecutionOperation original=operation(row,false);var prior=envelope(c,original,string(row,"envelope_ref"),false);
            var originalEvidence=codec.decodeEvidence(string(row,"evidence_json"));
            if(!originalEvidence.operation().equals(original))throw invalid();
            boolean same=original.equals(expected)&&Arrays.equals(prior.body,envelope.body)&&prior.signature.equals(envelope.signature)&&prior.policy.equals(envelope.policy)
                    &&originalEvidence.equals(decoded.evidence());
            if(!same){
                // Serialize with automatic acceptance so a concurrent manual conflict cannot miss
                // newly accepted work after its healthy check. Sorted owner locks avoid inversion.
                for(String fingerprint:new TreeSet<>(List.of(original.fingerprint(),expected.fingerprint())))deliveryLock(c,fingerprint);
                write(c,"INSERT INTO provider_event_conflicts(conflict_ref,original_verified_ref,disputed_envelope_ref,disputed_evidence_json,reason) VALUES(?,?,?,?,'EVENT_ID_CONFLICT') ON CONFLICT DO NOTHING",
                        UUID.randomUUID(),string(row,"verified_ref"),request.eventEnvelopeRef(),codec.encodeEvidence(decoded.evidence()));
                quarantine(c,original);quarantine(c,expected);
                if(automatic(c,original)||automatic(c,expected)){
                    enqueue(c,original,"QUARANTINE",null,prior.policy);enqueue(c,expected,"QUARANTINE",null,envelope.policy);
                }
                return new Verification(null,true);
            }
            return new Verification(new VerifiedEventReference(string(row,"verified_ref"),decoded.eventId(),expected.fingerprint(),expected.binding().digest()),false);
        });
        if(decision.conflict)throw new ProviderEvidenceConflict();return decision.reference;
    }
    @Override public ExecutionEvidence normalize(ExecutionOperation expected,VerifiedEventReference reference){
        configuration.validate(expected);if(reference==null||!reference.boundTo(expected))throw invalid();
        var retained=transaction(c->{
            healthy(c,expected);
            var row=one(c,"SELECT * FROM provider_verified_events WHERE tenant_id=? AND merchant_id=? AND run_id=? AND provider_id=? AND account_ref=? AND verified_ref=?",
                    configuration.tenantId(),configuration.merchantId(),configuration.runId(),configuration.binding().providerId(),configuration.binding().accountRef(),reference.verifiedEventRef());
            if(row==null||!reference.eventId().equals(string(row,"event_id"))||!expected.equals(operation(row)))throw invalid();
            var evidence=codec.decodeEvidence(string(row,"evidence_json"));if(!expected.equals(evidence.operation()))throw invalid();
            return new Retained(envelope(c,expected,string(row,"envelope_ref")),evidence);
        });
        var decoded=authenticated(expected,retained.envelope);
        if(!reference.eventId().equals(decoded.eventId())||!decoded.evidence().equals(retained.evidence))throw invalid();
        requireHealthy(expected);return retained.evidence;
    }
    private ProviderEventDecoder.Decoded authenticated(ExecutionOperation expected,Envelope envelope){
        try{
            authenticator.authenticate(envelope.body.clone(),envelope.signature);
            var decoded=decoder.decode(expected,envelope.body.clone());
            if(decoded==null||!expected.equals(decoded.evidence().operation())||decoded.evidence().source()!=ExecutionEvidence.Source.VERIFIED_EVENT)throw invalid();
            return decoded;
        }catch(ExecutionFailure safe){throw safe;}catch(RuntimeException unsafe){throw invalid();}
    }
    private Envelope envelope(Connection c,ExecutionOperation expected,String reference)throws SQLException{
        return envelope(c,expected,reference,true);
    }
    private Envelope envelope(Connection c,ExecutionOperation expected,String reference,boolean currentConfiguration)throws SQLException{
        var row=one(c,"SELECT * FROM provider_event_envelopes WHERE envelope_ref=? AND tenant_id=? AND merchant_id=? AND run_id=?",
                reference,configuration.tenantId(),configuration.merchantId(),configuration.runId());
        if(row==null||!expected.equals(operation(row,currentConfiguration)))throw invalid();
        if(currentConfiguration&&(!configuration.policyReference().equals(string(row,"policy_ref"))
                ||!configuration.policyVersion().equals(string(row,"policy_version"))||!configuration.keyReference().equals(string(row,"key_ref"))
                ||!configuration.keyVersion().equals(string(row,"key_version"))))throw invalid();
        byte[] body=(byte[])row.get("raw_body");String signature=string(row,"signature");
        if(body==null||body.length==0||body.length>16384||!signature.matches("[0-9a-f]{64}"))throw invalid();
        return new Envelope(body,signature,List.of(string(row,"policy_ref"),string(row,"policy_version"),string(row,"key_ref"),string(row,"key_version")));
    }
    private ExecutionOperation operation(Map<String,Object> row){
        return operation(row,true);
    }
    private ExecutionOperation operation(Map<String,Object> row,boolean currentConfiguration){
        var result=codec.decodeOperation(string(row,"operation_json"));if(currentConfiguration)configuration.validate(result);
        if(!configuration.tenantId().equals(result.identity().tenantId())||!configuration.merchantId().equals(result.identity().merchantId())
                ||!configuration.runId().equals(result.identity().runId())||!configuration.binding().providerId().equals(result.binding().providerId())
                ||!configuration.binding().accountRef().equals(result.binding().accountRef()))throw invalid();
        if(!result.fingerprint().equals(string(row,"operation_fingerprint"))||!result.identity().tenantId().equals(string(row,"tenant_id"))
                ||!result.identity().merchantId().equals(string(row,"merchant_id"))||!result.identity().runId().equals(string(row,"run_id")))throw invalid();
        return result;
    }
    private void healthy(Connection c,ExecutionOperation expected)throws SQLException{
        var row=one(c,"SELECT * FROM provider_event_quarantine WHERE tenant_id=? AND merchant_id=? AND run_id=? AND operation_fingerprint=?",
                configuration.tenantId(),configuration.merchantId(),configuration.runId(),expected.fingerprint());
        if(row!=null){if(!expected.equals(operation(row)))throw invalid();throw new ProviderEvidenceConflict();}
    }
    private void quarantine(Connection c,ExecutionOperation expected)throws SQLException{
        write(c,"INSERT INTO provider_event_quarantine(tenant_id,merchant_id,run_id,operation_fingerprint,operation_json) VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",
                configuration.tenantId(),configuration.merchantId(),configuration.runId(),expected.fingerprint(),codec.encodeOperation(expected));
    }
    private boolean automatic(Connection c,ExecutionOperation operation)throws SQLException{
        var id=operation.identity();
        return one(c,"SELECT delivery_ref FROM provider_event_delivery WHERE kind='EVENT' AND tenant_id=? AND merchant_id=? AND run_id=? AND operation_fingerprint=?",
                id.tenantId(),id.merchantId(),id.runId(),operation.fingerprint())!=null;
    }
    private void deliveryLock(Connection c,String fingerprint)throws SQLException{
        try(var p=c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")){
            p.setString(1,"provider-event-delivery:"+fingerprint);try(var result=p.executeQuery()){result.next();}
        }
    }
    private void enqueue(Connection c,ExecutionOperation operation,String kind,String envelope,List<String> policy)throws SQLException{
        var id=operation.identity();
        write(c,"INSERT INTO provider_event_delivery(delivery_ref,kind,tenant_id,merchant_id,run_id,operation_fingerprint,operation_json,envelope_ref,policy_ref,policy_version,key_ref,key_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                UUID.randomUUID().toString(),kind,id.tenantId(),id.merchantId(),id.runId(),operation.fingerprint(),codec.encodeOperation(operation),envelope,policy.get(0),policy.get(1),policy.get(2),policy.get(3));
    }
    private <T>T transaction(Work<T> work){
        try(var c=database.getConnection()){
            c.setAutoCommit(false);
            try{T result=work.run(c);c.commit();return result;}
            catch(Exception failure){try{c.rollback();}catch(SQLException ignored){}if(failure instanceof ProviderEvidenceConflict conflict)throw conflict;if(failure instanceof ExecutionFailure safe)throw safe;throw unavailable();}
        }catch(ProviderEvidenceConflict|ExecutionFailure safe){throw safe;}catch(Exception unsafe){throw unavailable();}
    }
    private static Map<String,Object> one(Connection c,String sql,Object...values)throws SQLException{
        try(var p=c.prepareStatement(sql)){bind(p,values);try(var rs=p.executeQuery()){if(!rs.next())return null;var result=new HashMap<String,Object>();var meta=rs.getMetaData();for(int n=1;n<=meta.getColumnCount();n++)result.put(meta.getColumnLabel(n),rs.getObject(n));return result;}}
    }
    private static void write(Connection c,String sql,Object...values)throws SQLException{try(var p=c.prepareStatement(sql)){bind(p,values);p.executeUpdate();}}
    private static void bind(PreparedStatement p,Object[] values)throws SQLException{for(int n=0;n<values.length;n++){if(values[n] instanceof byte[] bytes)p.setBytes(n+1,bytes);else p.setObject(n+1,values[n]);}}
    private static String string(Map<String,Object> row,String key){var value=row.get(key);if(!(value instanceof String result))throw invalid();return result;}
    private record Envelope(byte[] body,String signature,List<String> policy){}
    private record Retained(Envelope envelope,ExecutionEvidence evidence){}
    private record Verification(VerifiedEventReference reference,boolean conflict){}
    @FunctionalInterface private interface Work<T>{T run(Connection connection)throws Exception;}
    private static ExecutionFailure invalid(){return new ExecutionFailure(ExecutionFailure.Code.INVALID_EVENT,ExecutionFailure.Certainty.NOT_STARTED);}
    private static ExecutionFailure unavailable(){return new ExecutionFailure(ExecutionFailure.Code.CONFIGURATION_UNAVAILABLE,ExecutionFailure.Certainty.MAY_HAVE_OCCURRED);}
}
