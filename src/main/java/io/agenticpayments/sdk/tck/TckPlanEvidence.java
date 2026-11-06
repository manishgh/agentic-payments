package io.agenticpayments.sdk.tck;

import java.util.List;
import java.util.Set;

/** Safe, serializer-independent provenance for exactly the selected TCK assertions. */
public record TckPlanEvidence(String formatVersion, String suiteVersion, String selectedProfile,
                              Set<String> requiredCapabilities, Set<String> requiredCaseIds,
                              List<String> caseIds, String seed, String initialClock, String digest) {
    public TckPlanEvidence {
        requiredCapabilities = Set.copyOf(requiredCapabilities);
        requiredCaseIds = Set.copyOf(requiredCaseIds);
        caseIds = List.copyOf(caseIds);
    }
}
