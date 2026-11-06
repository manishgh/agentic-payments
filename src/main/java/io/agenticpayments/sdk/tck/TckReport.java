package io.agenticpayments.sdk.tck;
import java.util.List;
public record TckReport(String suiteVersion, String profile, String descriptorDigest,
                        List<TckCaseResult> results, boolean requiredCasesPassed, List<String> exclusions, TckPlanEvidence plan) {
    public TckReport { results = List.copyOf(results); exclusions = List.copyOf(exclusions); }
}
