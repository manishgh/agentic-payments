package io.agenticpayments.sdk.execution.v1;

import java.time.Instant;
import java.util.*;

final class FixtureOperations {
    static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");
    static final ExecutionBinding BINDING = new ExecutionBinding("synthetic-peer", "account-ref", "eu-test", "config-ref", "1", "credential-ref", "1", "platform-owner");
    static ExecutionOffer offer(String profile, ExecutionOffer.PreparationEffect effect, Set<ExecutionOffer.Capability> capabilities) {
        return new ExecutionOffer(ExecutionOffer.VERSION, profile, "synthetic-peer", "1", List.of(new ExecutionOffer.Combination(
                "synthetic-card", "synthetic-reference", Set.of(ExecutionOffer.ActionType.SYNTHETIC_CHALLENGE),
                Set.of(OperationPurpose.CHARGE, OperationPurpose.AUTHORIZE), effect, capabilities,
                List.of(new ExecutionOffer.CurrencyLimit("EUR", 2, 1, 999999), new ExecutionOffer.CurrencyLimit("USD", 2, 1, 999999)))));
    }
    static ExecutionOperation operation(ExecutionOffer offer, ExecutionBinding binding, String name) {
        return operation(offer, binding, name, 0, OperationPurpose.CHARGE);
    }
    static ExecutionOperation operation(ExecutionOffer offer, ExecutionBinding binding, String name, int combinationIndex, OperationPurpose purpose) {
        var money = new ExactMoney("EUR", 2, 1250); var c = offer.combinations().get(combinationIndex);
        var requirement = new CapabilitySelection.Requirement(ExecutionOffer.VERSION, offer.profileVersion(), offer.providerId(), offer.implementationRevision(),
                binding.accountRef(), binding.region(), c.methodId(), c.credentialFormatId(), c.actions(), purpose, c.preparationEffect(), c.capabilities(), money);
        return ExecutionOperation.create(new ExecutionIdentity("fixture-tenant", "merchant-demo", "fixture-run", "intent-" + name,
                "attempt-" + name, "operation-" + name, "key-" + name), binding, CapabilitySelection.select(offer, requirement, binding), money, purpose);
    }
    static TrustedActionDescriptor action(ExecutionOperation op) {
        return new TrustedActionDescriptor(ExecutionOffer.ActionType.SYNTHETIC_CHALLENGE, "action-ref", op.fingerprint(), op.binding().digest(), NOW.plusSeconds(600));
    }
    static ExecutionEvidence evidence(ExecutionOperation op, String id, ExecutionEvidence.Source source, ExecutionEvidence.State state) {
        return new ExecutionEvidence(op, id, Optional.of("resource-" + op.fingerprint()), source, NOW, state,
                state == ExecutionEvidence.State.ACTION_REQUIRED ? Optional.of(action(op)) : Optional.empty());
    }
    static ExecutionEvidence unknown(ExecutionOperation op) {
        return new ExecutionEvidence(op, "unknown-" + op.fingerprint(), Optional.empty(), ExecutionEvidence.Source.RECONCILIATION, NOW, ExecutionEvidence.State.UNKNOWN, Optional.empty());
    }
    static ExecutionFailure conflict() { return new ExecutionFailure(ExecutionFailure.Code.IDENTITY_CONFLICT, ExecutionFailure.Certainty.NOT_STARTED); }
    static void scope(ExecutionOperation op) {
        var id = op.identity();
        if (!id.tenantId().equals("fixture-tenant") || !id.merchantId().equals("merchant-demo") || !id.runId().equals("fixture-run"))
            throw new ExecutionFailure(ExecutionFailure.Code.INVALID_SELECTION, ExecutionFailure.Certainty.NOT_STARTED);
    }
}
