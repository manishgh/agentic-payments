package io.agenticpayments.sdk.tck;
import java.time.Instant;
public record TckCaseContext(String caseId, String fixtureNamespace, String seed, Instant initialClock) { }
