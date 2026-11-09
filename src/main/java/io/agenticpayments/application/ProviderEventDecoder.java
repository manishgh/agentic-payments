package io.agenticpayments.application;

import io.agenticpayments.sdk.execution.v1.*;
import java.util.Objects;

/** Interprets an already authenticated body under a pinned financial profile. */
@FunctionalInterface public interface ProviderEventDecoder {
    String CONTRACT="provider-event-decoder-1";
    Decoded decode(ExecutionOperation expected,byte[] authenticatedBody);
    record Decoded(String eventId,ExecutionEvidence evidence) {
        public Decoded {Objects.requireNonNull(evidence);new VerifiedEventReference("validation",eventId,evidence.operation().fingerprint(),evidence.operation().binding().digest());}
        @Override public String toString(){return "DecodedProviderEvent[redacted]";}
    }
}
