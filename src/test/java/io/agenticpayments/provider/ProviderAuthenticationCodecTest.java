package io.agenticpayments.provider;

import io.agenticpayments.application.*;
import io.agenticpayments.infrastructure.provider.*;
import io.agenticpayments.sdk.execution.v1.*;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static io.agenticpayments.provider.ProviderJournalTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class ProviderAuthenticationCodecTest {
    @Test void completeBindingAndObservationRoundTripStrictly() {
        var configuration=configuration("codec-auth","merchant","run");var operation=operation(configuration,"caller","operation");
        var binding=new ProviderActionBinding(operation,new TrustedActionDescriptor(ExecutionOffer.ActionType.SYNTHETIC_CHALLENGE,"action",operation.fingerprint(),BINDING.digest(),EXPIRY));
        var codec=new ProviderAuthenticationCodec();String encoded=codec.encodeBinding(binding);
        assertEquals(binding,codec.decodeBinding(encoded));
        var observation=new AuthenticationObservation(binding,"customer",UUID.randomUUID(),AuthenticationObservation.Outcome.VERIFIED,NOW);
        assertEquals(observation,codec.decodeObservation(codec.encodeObservation(observation)));
        assertThrows(IllegalArgumentException.class,()->codec.decodeBinding(encoded.replace("\"version\":","\"unknown\":\"x\",\"version\":")));
        assertThrows(IllegalArgumentException.class,()->codec.decodeBinding(encoded.replace("\"version\":","\"version\":\"duplicate\",\"version\":")));
        assertThrows(IllegalArgumentException.class,()->codec.decodeBinding(encoded+" {}"));
        assertThrows(IllegalArgumentException.class,()->codec.decodeBinding(" ".repeat(262145)));
        assertFalse(binding.toString().contains(operation.fingerprint()));assertFalse(observation.toString().contains("customer"));
    }
}
