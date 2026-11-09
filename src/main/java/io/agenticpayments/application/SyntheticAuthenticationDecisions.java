package io.agenticpayments.application;

import java.time.Instant;
import java.util.UUID;

/** Explicit local synthetic interaction only; not a real-provider authentication contract. */
public interface SyntheticAuthenticationDecisions {
    String PROFILE="local-synthetic-authentication-1";
    /** Caller first durably reserves an authorized complete-action request. No financial effects. */
    void decide(ProviderActionBinding binding,String subjectId,UUID requestId,String decision,Instant trustedNow);
}
