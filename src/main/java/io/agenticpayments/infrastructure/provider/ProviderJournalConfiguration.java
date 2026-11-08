package io.agenticpayments.infrastructure.provider;

import io.agenticpayments.sdk.*;
import io.agenticpayments.sdk.execution.v1.*;
import io.agenticpayments.sdk.execution.v1.compat.LocalFakeExecutionAdapter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/** Exact trusted synthetic policy. Resolver implementations must retain their immutable revision. */
public record ProviderJournalConfiguration(String tenantId, String merchantId, String runId,
        ExecutionBinding binding, String mappingPolicyVersion, String scenarioPolicyReference,
        String scenarioPolicyVersion, Function<ExecutionOperation, String> scenarioResolver) {
    public static final String MAPPING_POLICY_VERSION = "local-fake-uuid-map-1";
    public ProviderJournalConfiguration {
        token(tenantId); token(merchantId); token(runId); token(scenarioPolicyReference); token(scenarioPolicyVersion);
        if (!tenantId.matches("[a-z0-9][a-z0-9-]{0,63}") || !merchantId.matches("[a-z0-9][a-z0-9-]{0,63}")
                || !MAPPING_POLICY_VERSION.equals(mappingPolicyVersion) || !"simulator".equals(Objects.requireNonNull(binding).providerId()))
            throw new IllegalArgumentException("Invalid trusted compatibility policy.");
        Objects.requireNonNull(scenarioResolver);
    }
    public ExecutionOffer offer() { return LocalFakeExecutionAdapter.offer(); }
    public void validate(ExecutionOperation operation) {
        Objects.requireNonNull(operation); var id = operation.identity();
        if (!tenantId.equals(id.tenantId()) || !merchantId.equals(id.merchantId()) || !runId.equals(id.runId()))
            throw new ExecutionFailure(ExecutionFailure.Code.INVALID_SELECTION, ExecutionFailure.Certainty.NOT_STARTED);
        operation.selection().validate(offer(), binding);
    }
    public String scenarioFor(ExecutionOperation operation) {
        validate(operation);
        String scenario;
        try { scenario = scenarioResolver.apply(operation); }
        catch (RuntimeException unsafe) { throw new ExecutionFailure(ExecutionFailure.Code.CONFIGURATION_UNAVAILABLE, ExecutionFailure.Certainty.NOT_STARTED); }
        if (scenario == null || !ChargeOperation.SCENARIOS.contains(scenario))
            throw new ExecutionFailure(ExecutionFailure.Code.UNSUPPORTED_COMBINATION, ExecutionFailure.Certainty.NOT_STARTED);
        return scenario;
    }
    public ChargeOperation expectedLegacy(ExecutionOperation operation) {
        validate(operation); var id = operation.identity();
        return new ChargeOperation(new OperationIdentity(new ExecutionScope(tenantId, uuid("run", runId), merchantId),
                uuid("intent", id.intentId()), uuid("attempt", id.attemptId()), uuid("operation", id.operationId()),
                uuid("key", String.join("\n", tenantId, merchantId, runId, id.callerIdempotencyKey())), operation.fingerprint()),
                new Money(operation.money().currency(), operation.money().minorUnits()), scenarioFor(operation));
    }
    private static UUID uuid(String tag, String value) {
        return UUID.nameUUIDFromBytes(("local-fake-execution-1\n" + tag + "\n" + value).getBytes(StandardCharsets.UTF_8));
    }
    private static void token(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            throw new IllegalArgumentException("Invalid trusted policy identifier.");
    }
    @Override public String toString() { return "ProviderJournalConfiguration[redacted]"; }
}
