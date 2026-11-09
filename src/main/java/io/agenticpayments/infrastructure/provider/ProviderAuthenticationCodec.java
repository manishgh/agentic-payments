package io.agenticpayments.infrastructure.provider;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import io.agenticpayments.application.*;
import io.agenticpayments.sdk.execution.v1.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Strict bounded full-value storage codec; retained references never become UI payloads. */
public final class ProviderAuthenticationCodec {
    private final ObjectMapper json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ProviderJournalCodec operations=new ProviderJournalCodec();
    public String encodeBinding(ProviderActionBinding binding) {
        try {
            var n=json.createObjectNode();n.put("version","provider-action-json-1");n.put("operation",operations.encodeOperation(binding.operation()));
            var a=binding.action();n.put("type",a.type().name());n.put("reference",a.actionRef());n.put("operationFingerprint",a.operationFingerprint());
            n.put("bindingDigest",a.bindingDigest());n.put("expiresAt",a.expiresAt().toString());return bounded(json.writeValueAsString(n));
        }catch(Exception failure){throw invalid();}
    }
    public ProviderActionBinding decodeBinding(String value) {
        try {
            var n=object(value,Set.of("version","operation","type","reference","operationFingerprint","bindingDigest","expiresAt"));
            if(!n.get("version").textValue().equals("provider-action-json-1"))throw invalid();
            return new ProviderActionBinding(operations.decodeOperation(n.get("operation").textValue()),new TrustedActionDescriptor(
                    ExecutionOffer.ActionType.valueOf(n.get("type").textValue()),n.get("reference").textValue(),n.get("operationFingerprint").textValue(),
                    n.get("bindingDigest").textValue(),instant(n.get("expiresAt").textValue())));
        }catch(Exception failure){throw invalid();}
    }
    public String encodeObservation(AuthenticationObservation observation) {
        try {
            var n=json.createObjectNode();n.put("version","provider-authentication-json-1");n.put("binding",encodeBinding(observation.binding()));
            n.put("subject",observation.subjectId());n.put("resultId",observation.resultId().toString());n.put("outcome",observation.outcome().name());
            n.put("observedAt",observation.observedAt().toString());return bounded(json.writeValueAsString(n));
        }catch(Exception failure){throw invalid();}
    }
    public AuthenticationObservation decodeObservation(String value) {
        try {
            var n=object(value,Set.of("version","binding","subject","resultId","outcome","observedAt"));
            if(!n.get("version").textValue().equals("provider-authentication-json-1"))throw invalid();
            String id=n.get("resultId").textValue();UUID result=UUID.fromString(id);if(!result.toString().equals(id))throw invalid();
            return new AuthenticationObservation(decodeBinding(n.get("binding").textValue()),n.get("subject").textValue(),result,
                    AuthenticationObservation.Outcome.valueOf(n.get("outcome").textValue()),instant(n.get("observedAt").textValue()));
        }catch(Exception failure){throw invalid();}
    }
    private JsonNode object(String value,Set<String> fields)throws Exception {
        var n=json.readTree(bounded(value));var keys=new HashSet<String>();n.fieldNames().forEachRemaining(keys::add);
        if(!n.isObject()||!keys.equals(fields))throw invalid();for(String field:fields)if(!n.get(field).isTextual())throw invalid();return n;
    }
    private static Instant instant(String value){Instant parsed=Instant.parse(value);if(!parsed.toString().equals(value))throw invalid();return parsed;}
    private static String bounded(String value){if(value==null||value.getBytes(StandardCharsets.UTF_8).length>262144)throw invalid();return value;}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid retained provider authentication.");}
}
