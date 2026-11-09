package io.agenticpayments.infrastructure.provider;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import io.agenticpayments.application.ProviderEventDecoder;
import io.agenticpayments.sdk.execution.v1.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/** Exact full-amount synthetic financial statuses only; no authentication/redirect completion meaning. */
public final class SyntheticProviderEventDecoder implements ProviderEventDecoder {
    public static final String VERSION="synthetic-provider-event-1";
    private static final Set<String> FIELDS=Set.of("version","eventId","operationFingerprint","bindingDigest","accountRef","currency","exponent","minorUnits","providerResourceRef","observedAt","state");
    private final ObjectMapper json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Function<ExecutionOperation,String> resource;
    public SyntheticProviderEventDecoder(Function<ExecutionOperation,String> expectedResource){resource=Objects.requireNonNull(expectedResource);}
    @Override public Decoded decode(ExecutionOperation expected,byte[] body){
        try {
            if(body==null||body.length==0||body.length>16384)throw invalid();
            JsonNode n=json.readTree(body);if(n==null||!n.isObject()||n.size()!=FIELDS.size())throw invalid();
            var names=new HashSet<String>();n.fieldNames().forEachRemaining(names::add);if(!names.equals(FIELDS))throw invalid();
            if(!VERSION.equals(text(n,"version"))||!expected.fingerprint().equals(text(n,"operationFingerprint"))
                    ||!expected.binding().digest().equals(text(n,"bindingDigest"))||!expected.binding().accountRef().equals(text(n,"accountRef"))
                    ||!expected.money().currency().equals(text(n,"currency"))||!n.get("exponent").isIntegralNumber()
                    ||!n.get("exponent").canConvertToInt()||expected.money().exponent()!=n.get("exponent").intValue()
                    ||!Long.toString(expected.money().minorUnits()).equals(text(n,"minorUnits"))
                    ||!resource.apply(expected).equals(text(n,"providerResourceRef")))throw invalid();
            var state=ExecutionEvidence.State.valueOf(text(n,"state"));
            if(!Set.of(ExecutionEvidence.State.UNKNOWN,ExecutionEvidence.State.PENDING,ExecutionEvidence.State.CAPTURED,ExecutionEvidence.State.NO_EFFECT_FENCED).contains(state)
                    ||expected.purpose()!=OperationPurpose.CHARGE)throw invalid();
            String eventId=text(n,"eventId");var evidence=new ExecutionEvidence(expected,"event-"+ProviderJournalCodec.digest(eventId).substring(7),
                    Optional.of(text(n,"providerResourceRef")),ExecutionEvidence.Source.VERIFIED_EVENT,Instant.parse(text(n,"observedAt")),state,Optional.empty());
            return new Decoded(eventId,evidence);
        }catch(ExecutionFailure safe){throw safe;}catch(Exception unsafe){throw invalid();}
    }
    private static String text(JsonNode node,String field){var value=node.get(field);if(value==null||!value.isTextual())throw invalid();return value.textValue();}
    private static ExecutionFailure invalid(){return new ExecutionFailure(ExecutionFailure.Code.INVALID_EVENT,ExecutionFailure.Certainty.NOT_STARTED);}
}
