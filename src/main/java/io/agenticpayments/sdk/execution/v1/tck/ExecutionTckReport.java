package io.agenticpayments.sdk.execution.v1.tck;

import java.util.*;

/** Fixed safe findings only; never store raw adapter exceptions or provider payloads. */
public record ExecutionTckReport(String suiteVersion, String contractVersion, String profileVersion, String offerDigest,
                                 String selectedCombinationDigest,
                                 String fixtureId, ExecutionTckFixture.RestartModel restartModel,
                                 List<CaseResult> results, boolean requiredCasesPassed, List<String> exclusions) {
    public enum Status { PASS, FAIL, INCOMPLETE, UNSUPPORTED }
    public record CaseResult(String caseId, Status status, String safeReason) {
        public CaseResult { Objects.requireNonNull(caseId); Objects.requireNonNull(status); Objects.requireNonNull(safeReason); }
    }
    public ExecutionTckReport {
        Objects.requireNonNull(suiteVersion); Objects.requireNonNull(contractVersion); Objects.requireNonNull(profileVersion);
        Objects.requireNonNull(offerDigest); Objects.requireNonNull(selectedCombinationDigest); Objects.requireNonNull(fixtureId); Objects.requireNonNull(restartModel);
        results = List.copyOf(results); exclusions = List.copyOf(exclusions);
    }
}
