package io.agenticpayments.infrastructure.provider;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import io.agenticpayments.application.ProviderExecutionBinding;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Exact, bounded route codec; embedded operation codecs retain their strict validation. */
public final class ProviderAdmissionCodec {
    private final ObjectMapper json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ProviderJournalCodec values=new ProviderJournalCodec();
    private static final Set<String> FIELDS=Set.of("version","profile","mappingPolicy","scenarioPolicyReference","scenarioPolicyVersion","core","credentialReference","operation","observedAt","actionExpiresAt");
    public String encode(ProviderExecutionBinding b) {
        try {var n=json.createObjectNode();n.put("version","provider-admission-json-1");n.put("profile",b.profile());n.put("mappingPolicy",b.mappingPolicy());
            n.put("scenarioPolicyReference",b.scenarioPolicyReference());n.put("scenarioPolicyVersion",b.scenarioPolicyVersion());
            n.put("core",values.encodeLegacy(b.coreOperation()));n.put("credentialReference",b.credentialReference());n.put("operation",values.encodeOperation(b.operation()));
            n.put("observedAt",b.observedAt().toString());n.put("actionExpiresAt",b.actionExpiresAt().toString());String encoded=json.writeValueAsString(n);bounded(encoded);return encoded;
        }catch(Exception e){throw invalid();}
    }
    public ProviderExecutionBinding decode(String value) {
        try {bounded(value);var n=json.readTree(value);var keys=new HashSet<String>();n.fieldNames().forEachRemaining(keys::add);
            if(!n.isObject()||!keys.equals(FIELDS))throw invalid();for(String key:FIELDS)if(!n.get(key).isTextual())throw invalid();
            if(!"provider-admission-json-1".equals(n.get("version").textValue()))throw invalid();
            return new ProviderExecutionBinding(n.get("profile").textValue(),n.get("mappingPolicy").textValue(),n.get("scenarioPolicyReference").textValue(),n.get("scenarioPolicyVersion").textValue(),
                    values.decodeLegacy(n.get("core").textValue()),n.get("credentialReference").textValue(),values.decodeOperation(n.get("operation").textValue()),
                    instant(n.get("observedAt").textValue()),instant(n.get("actionExpiresAt").textValue()));
        }catch(Exception e){throw invalid();}
    }
    private static Instant instant(String text){var value=Instant.parse(text);if(!value.toString().equals(text))throw invalid();return value;}
    private static void bounded(String value){if(value==null||value.getBytes(StandardCharsets.UTF_8).length>131072)throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid retained provider admission binding.");}
}
