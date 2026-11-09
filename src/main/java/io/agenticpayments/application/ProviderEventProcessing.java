package io.agenticpayments.application;

import io.agenticpayments.sdk.Fact;
import java.util.Optional;

/** Configured verifier -> provenance-resolving normalizer -> journal -> financial projection. */
@FunctionalInterface public interface ProviderEventProcessing {
    String CONTRACT="provider-event-processing-1";
    Optional<Fact> process(ProviderExecutionBinding binding,String envelopeReference);
}
