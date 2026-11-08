package io.agenticpayments.sdk.execution.v1;

import java.util.*;
import static io.agenticpayments.sdk.execution.v1.ExecutionFailure.Code.*;

/** Immutable exact selection. Adapters still compare it to their trusted pinned configuration. */
public record CapabilitySelection(String contractVersion, String profileVersion, String implementationRevision,
                                  String offerDigest, ExecutionOffer.Combination combination, ExecutionBinding binding) {
    public CapabilitySelection {
        if (!ExecutionOffer.VERSION.equals(contractVersion)) throw new ExecutionFailure(UNSUPPORTED_CONTRACT, ExecutionFailure.Certainty.NOT_STARTED);
        profileVersion = ContractValues.token(profileVersion); implementationRevision = ContractValues.token(implementationRevision);
        offerDigest = ContractValues.digest(offerDigest); Objects.requireNonNull(combination); Objects.requireNonNull(binding);
    }
    public record Requirement(String contractVersion, String profileVersion, String providerId, String implementationRevision,
                              String accountRef, String region, String methodId, String credentialFormatId,
                              Set<ExecutionOffer.ActionType> actions, OperationPurpose purpose,
                              ExecutionOffer.PreparationEffect preparationEffect, Set<ExecutionOffer.Capability> capabilities,
                              ExactMoney money) {
        public Requirement {
            contractVersion = ContractValues.token(contractVersion); profileVersion = ContractValues.token(profileVersion);
            providerId = ContractValues.token(providerId); implementationRevision = ContractValues.token(implementationRevision);
            accountRef = ContractValues.token(accountRef); region = ContractValues.token(region);
            methodId = ContractValues.token(methodId); credentialFormatId = ContractValues.token(credentialFormatId);
            actions = ContractValues.set(actions, true); capabilities = ContractValues.set(capabilities, true);
            Objects.requireNonNull(purpose); Objects.requireNonNull(preparationEffect); Objects.requireNonNull(money);
        }
        @Override public String toString() { return "ExecutionRequirement[redacted]"; }
    }
    public static CapabilitySelection select(ExecutionOffer offer, Requirement requirement, ExecutionBinding binding) {
        Objects.requireNonNull(offer); Objects.requireNonNull(requirement); Objects.requireNonNull(binding);
        ContractValues.require(offer.contractVersion().equals(requirement.contractVersion()), UNSUPPORTED_CONTRACT);
        ContractValues.require(offer.profileVersion().equals(requirement.profileVersion())
                && offer.implementationRevision().equals(requirement.implementationRevision()), UNSUPPORTED_PROFILE);
        ContractValues.require(offer.providerId().equals(requirement.providerId()) && binding.providerId().equals(offer.providerId())
                && binding.accountRef().equals(requirement.accountRef()) && binding.region().equals(requirement.region()), INVALID_SELECTION);
        var matches = offer.combinations().stream().filter(c -> c.methodId().equals(requirement.methodId())
                && c.credentialFormatId().equals(requirement.credentialFormatId()) && c.actions().containsAll(requirement.actions())
                && c.purposes().contains(requirement.purpose()) && c.preparationEffect() == requirement.preparationEffect()
                && c.capabilities().containsAll(requirement.capabilities()) && c.permits(requirement.money())).toList();
        ContractValues.require(matches.size() == 1, UNSUPPORTED_COMBINATION);
        return new CapabilitySelection(offer.contractVersion(), offer.profileVersion(), offer.implementationRevision(), offer.digest(), matches.getFirst(), binding);
    }
    public void validate(ExecutionOffer offer, ExecutionBinding trustedBinding) {
        ContractValues.require(binding.equals(trustedBinding) && offer.providerId().equals(binding.providerId())
                && offer.digest().equals(offerDigest) && offer.profileVersion().equals(profileVersion)
                && offer.implementationRevision().equals(implementationRevision) && offer.combinations().contains(combination), INVALID_SELECTION);
    }
    public String digest() { return ContractValues.hash(List.of(contractVersion, profileVersion, implementationRevision,
            offerDigest, combination.digest(), binding.digest())); }
    @Override public String toString() { return "CapabilitySelection[redacted]"; }
}
