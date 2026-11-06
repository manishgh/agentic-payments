package io.agenticpayments.sdk.tck;
import java.util.Map;
public record TckCaseResult(String caseId, TckStatus status, String reason, Map<String, String> safeAssertions) {
    public TckCaseResult { safeAssertions = Map.copyOf(safeAssertions); }
}
