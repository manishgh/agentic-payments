package io.agenticpayments.application;

import java.time.Instant;
import java.util.*;

/** A configured resolver's retained authentication result, never financial evidence. */
public record AuthenticationObservation(ProviderActionBinding binding,String subjectId,UUID resultId,Outcome outcome,Instant observedAt) {
    public enum Outcome { VERIFIED,DENIED }
    public AuthenticationObservation {
        Objects.requireNonNull(binding);Objects.requireNonNull(subjectId);Objects.requireNonNull(resultId);
        Objects.requireNonNull(outcome);Objects.requireNonNull(observedAt);
        if(subjectId.isBlank()||subjectId.length()>128)throw new IllegalArgumentException("Invalid authentication subject.");
    }
    @Override public String toString(){return "AuthenticationObservation[redacted]";}
}
