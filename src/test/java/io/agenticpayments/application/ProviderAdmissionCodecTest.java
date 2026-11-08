package io.agenticpayments.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agenticpayments.infrastructure.provider.ProviderAdmissionCodec;
import io.agenticpayments.infrastructure.provider.SyntheticProviderRegistration;
import io.agenticpayments.sdk.*;
import io.agenticpayments.sdk.execution.v1.ExecutionBinding;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Corrupt retained registration cannot be silently repaired or defaulted during recovery. */
class ProviderAdmissionCodecTest {
    private final ProviderAdmissionCodec codec=new ProviderAdmissionCodec();
    private final ObjectMapper json=new ObjectMapper();
    private static final Instant OBSERVED=Instant.parse("2026-11-08T10:00:00.123456789Z"),EXPIRY=Instant.parse("2026-11-08T10:10:00.987654321Z");
    private static final List<String> FIELDS=List.of("version","profile","mappingPolicy","scenarioPolicyReference","scenarioPolicyVersion","core","credentialReference","operation","observedAt","actionExpiresAt");
    private ProviderExecutionBinding binding() {
        var scope=new ExecutionScope("admission-codec",new UUID(0,1),"merchant-demo");
        var core=new ChargeOperation(new OperationIdentity(scope,new UUID(0,2),new UUID(0,3),new UUID(0,4),new UUID(0,5),"sha256:"+"a".repeat(64)),new Money("EUR",1250),"response-loss-after-acceptance");
        var provider=new ExecutionBinding("simulator","codec-account","eu-test","codec-config","version-7","fixture-credential-1","version-9","platform-owner");
        return new SyntheticProviderRegistration(scope.tenantId(),scope.merchantId(),scope.runId(),provider,OBSERVED,EXPIRY).binding(core);
    }
    private ObjectNode encoded()throws Exception{return (ObjectNode)json.readTree(codec.encode(binding()));}
    private void rejected(String payload) {
        var failure=assertThrows(IllegalArgumentException.class,()->codec.decode(payload));
        assertEquals("Invalid retained provider admission binding.",failure.getMessage());assertNull(failure.getCause());assertFalse(failure.toString().contains("CANARY_PRIVATE"));
    }

    @Test void completeBindingAndBothNanosecondInstantsRoundTripWithoutChangingAnyIdentity() throws Exception {
        var original=binding();var encoded=codec.encode(original);var decoded=codec.decode(encoded);
        assertEquals(original,decoded);assertEquals(OBSERVED,decoded.observedAt());assertEquals(EXPIRY,decoded.actionExpiresAt());
        assertEquals(123456789,decoded.observedAt().getNano());assertEquals(987654321,decoded.actionExpiresAt().getNano());
        assertEquals(original.coreOperation(),decoded.coreOperation());assertEquals(original.operation().selection(),decoded.operation().selection());
        assertEquals(original.operation().fingerprint(),decoded.operation().fingerprint());assertEquals(encoded,codec.encode(decoded));
        var root=(ObjectNode)json.readTree(encoded);var keys=new HashSet<String>();root.fieldNames().forEachRemaining(keys::add);
        assertEquals(new HashSet<>(FIELDS),keys);assertEquals("provider-admission-json-1",root.get("version").textValue());
        assertTrue(encoded.contains(OBSERVED.toString()));assertTrue(encoded.contains(EXPIRY.toString()));
    }

    @Test void duplicateEnvelopeFieldsAreRejectedEvenWhenBothValuesAreIdentical() throws Exception {
        var root=encoded();String valid=root.toString();
        for(String field:FIELDS){String duplicate="{"+json.writeValueAsString(field)+":"+json.writeValueAsString(root.get(field).textValue())+","+valid.substring(1);rejected(duplicate);}
    }

    @Test void everyMissingNullAndNontextualEnvelopeFieldIsRejected() throws Exception {
        var root=encoded();
        for(String field:FIELDS){
            var missing=root.deepCopy();missing.remove(field);rejected(missing.toString());
            var explicitNull=root.deepCopy();explicitNull.putNull(field);rejected(explicitNull.toString());
            var wrongType=root.deepCopy();wrongType.put(field,7);rejected(wrongType.toString());
        }
    }

    @Test void unknownFieldsVersionsMalformedAndOversizedPayloadsHaveOneSafeFailure() throws Exception {
        var unknown=encoded();unknown.put("CANARY_PRIVATE_UNKNOWN_FIELD","CANARY_PRIVATE_VALUE");rejected(unknown.toString());
        var version=encoded();version.put("version","CANARY_PRIVATE_FUTURE_VERSION");rejected(version.toString());
        rejected(null);
        for(String malformed:List.of(""," ","{","{}","[]","null","true","7","\"CANARY_PRIVATE\""," ".repeat(131073),encoded()+" {}"))rejected(malformed);
        var failure=assertThrows(IllegalArgumentException.class,()->codec.encode(null));assertNull(failure.getCause());assertEquals("Invalid retained provider admission binding.",failure.getMessage());
    }

    @Test void strictEmbeddedCoreAndProviderCodecsCannotBeBypassedByTheOuterEnvelope() throws Exception {
        for(String field:List.of("core","operation")){
            var root=encoded();String embedded=root.get(field).textValue();
            root.put(field,embedded.replace("\"kind\":", "\"kind\":\"CANARY_PRIVATE_DUPLICATE\",\"kind\":"));rejected(root.toString());
            root=encoded();var inner=(ObjectNode)json.readTree(root.get(field).textValue());inner.put("unknown","CANARY_PRIVATE");root.put(field,inner.toString());rejected(root.toString());
            root=encoded();inner=(ObjectNode)json.readTree(root.get(field).textValue());inner.remove("value");root.put(field,inner.toString());rejected(root.toString());
            root=encoded();root.put(field,root.get(field).textValue()+" {}");rejected(root.toString());
        }
        var root=encoded();var operation=(ObjectNode)json.readTree(root.get("operation").textValue());
        ((ObjectNode)operation.path("value")).put("fingerprint","sha256:"+"0".repeat(64));root.put("operation",operation.toString());rejected(root.toString());
    }

    @Test void invalidInstantsAndInconsistentMoneyOrCredentialBindingsCannotBecomeRecoveryDefaults() throws Exception {
        for(String field:List.of("observedAt","actionExpiresAt")){
            var invalid=encoded();invalid.put(field,"CANARY_PRIVATE_INVALID_TIME");rejected(invalid.toString());
            var alternate=encoded();alternate.put(field,alternate.get(field).textValue().replace("Z","+00:00"));rejected(alternate.toString());
        }
        var reversed=encoded();reversed.put("actionExpiresAt",OBSERVED.toString());rejected(reversed.toString());
        var missingCredential=encoded();missingCredential.put("credentialReference","different-credential");rejected(missingCredential.toString());
        var root=encoded();var core=(ObjectNode)json.readTree(root.get("core").textValue());
        ((ObjectNode)core.path("value").path("money")).put("minorUnits",1251);root.put("core",core.toString());rejected(root.toString());
    }

    @Test void retainedPolicyReferencesRemainBoundedAsciiTokens() throws Exception {
        for(String field:List.of("profile","mappingPolicy","scenarioPolicyReference","scenarioPolicyVersion","credentialReference")){
            for(String value:List.of("","CANARY_PRIVATE has spaces","x".repeat(129),"\u00e9".repeat(70000))){
                var root=encoded();root.put(field,value);rejected(root.toString());
            }
        }
    }
}
