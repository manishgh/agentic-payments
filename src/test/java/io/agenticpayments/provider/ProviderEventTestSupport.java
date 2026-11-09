package io.agenticpayments.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agenticpayments.infrastructure.provider.*;
import io.agenticpayments.sdk.execution.v1.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Test-only independent signed-wire author. No signing endpoint is exposed by production composition. */
final class ProviderEventTestSupport {
    static final byte[] KEY="synthetic-test-event-key-32-bytes-only".getBytes(StandardCharsets.US_ASCII);
    static final Instant NOW=Instant.parse("2026-11-09T10:00:00.123456789Z");
    static final ExecutionBinding BINDING=new ExecutionBinding("simulator","event-account","eu-test","event-config","1","fixture-credential-1","1","owner");
    static final ExecutionOffer OFFER=new ExecutionOffer(ExecutionOffer.VERSION,"local-fake-interactions-execution-1","simulator","1",List.of(
            new ExecutionOffer.Combination("synthetic-card","synthetic-reference",Set.of(ExecutionOffer.ActionType.SYNTHETIC_CHALLENGE),Set.of(OperationPurpose.CHARGE),
                    ExecutionOffer.PreparationEffect.EFFECT_FREE,Set.of(ExecutionOffer.Capability.VERIFIED_EVENTS,ExecutionOffer.Capability.ATOMIC_NO_EFFECT_FENCE),List.of(new ExecutionOffer.CurrencyLimit("EUR",2,1,999999)))));
    static ProviderEventConfiguration configuration(String tenant){return new ProviderEventConfiguration(tenant,"merchant","run",BINDING,OFFER,"event-policy","1","event-key","1");}
    static ExecutionOperation operation(ProviderEventConfiguration config,String suffix){
        var binding=config.binding();var money=new ExactMoney("EUR",2,1250);var selection=CapabilitySelection.select(config.offer(),new CapabilitySelection.Requirement(ExecutionOffer.VERSION,config.offer().profileVersion(),"simulator","1",binding.accountRef(),binding.region(),
                "synthetic-card","synthetic-reference",Set.of(),OperationPurpose.CHARGE,ExecutionOffer.PreparationEffect.EFFECT_FREE,Set.of(ExecutionOffer.Capability.VERIFIED_EVENTS),money),binding);
        return ExecutionOperation.create(new ExecutionIdentity(config.tenantId(),config.merchantId(),config.runId(),"intent-"+suffix,"attempt-"+suffix,"operation-"+suffix,"key-"+suffix),binding,selection,money,OperationPurpose.CHARGE);
    }
    static JdbcProviderEventInbox inbox(DataSource database,ProviderEventConfiguration config){return new JdbcProviderEventInbox(database,config,new HmacProviderEventAuthenticator(KEY),new SyntheticProviderEventDecoder(ProviderEventTestSupport::resource));}
    static String resource(ExecutionOperation operation){return "resource-"+operation.identity().operationId();}
    static byte[] body(ExecutionOperation operation,String id,ExecutionEvidence.State state){
        var json=new ObjectMapper();var n=json.createObjectNode();n.put("version","synthetic-provider-event-1");n.put("eventId",id);
        n.put("operationFingerprint",operation.fingerprint());n.put("bindingDigest",operation.binding().digest());n.put("accountRef",operation.binding().accountRef());
        n.put("currency",operation.money().currency());n.put("exponent",operation.money().exponent());n.put("minorUnits",Long.toString(operation.money().minorUnits()));
        n.put("providerResourceRef",resource(operation));n.put("observedAt",NOW.toString());n.put("state",state.name());
        try{return json.writeValueAsBytes(n);}catch(Exception failure){throw new AssertionError(failure);}
    }
    static String sign(byte[] body){try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(KEY,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(body));}catch(Exception failure){throw new AssertionError(failure);}}
    static String retain(JdbcProviderEventInbox inbox,ExecutionOperation operation,String id,ExecutionEvidence.State state){var bytes=body(operation,id,state);return inbox.retain(operation,bytes,sign(bytes));}
    static String tenant(){return "events-"+UUID.randomUUID().toString().replace("-","");}
    private ProviderEventTestSupport(){}
}
