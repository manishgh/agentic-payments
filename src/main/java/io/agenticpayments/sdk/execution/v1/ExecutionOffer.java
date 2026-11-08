package io.agenticpayments.sdk.execution.v1;

import java.util.*;

/** Complete supported tuples. Declaration is neither authority nor an authenticated configuration. */
public record ExecutionOffer(String contractVersion, String profileVersion, String providerId,
                             String implementationRevision, List<Combination> combinations) {
    public static final String VERSION = "provider-execution-1";
    public enum PreparationEffect { EFFECT_FREE, RESOURCE_CREATING }
    public enum Capability { VERIFIED_EVENTS, ATOMIC_NO_EFFECT_FENCE }
    public enum ActionType { SYNTHETIC_CHALLENGE, REDIRECT, SDK_HANDOFF }
    public ExecutionOffer {
        if (!VERSION.equals(contractVersion)) throw new ExecutionFailure(ExecutionFailure.Code.UNSUPPORTED_CONTRACT, ExecutionFailure.Certainty.NOT_STARTED);
        profileVersion = ContractValues.token(profileVersion); providerId = ContractValues.token(providerId);
        implementationRevision = ContractValues.token(implementationRevision);
        combinations = List.copyOf(Objects.requireNonNull(combinations));
        if (combinations.isEmpty() || combinations.size() > 32 || new HashSet<>(combinations).size() != combinations.size())
            throw new IllegalArgumentException("Invalid bounded combinations.");
    }
    public record CurrencyLimit(String currency, int exponent, long minimum, long maximum) {
        public CurrencyLimit {
            new ExactMoney(currency, exponent, minimum); new ExactMoney(currency, exponent, maximum);
            if (minimum > maximum) throw new IllegalArgumentException("Invalid currency range.");
        }
        public boolean permits(ExactMoney money) { return currency.equals(money.currency()) && exponent == money.exponent()
                && minimum <= money.minorUnits() && money.minorUnits() <= maximum; }
        String canonical() { return ContractValues.hash(List.of(currency, Integer.toString(exponent), Long.toString(minimum), Long.toString(maximum))); }
    }
    public record Combination(String methodId, String credentialFormatId, Set<ActionType> actions,
                              Set<OperationPurpose> purposes, PreparationEffect preparationEffect,
                              Set<Capability> capabilities, List<CurrencyLimit> limits) {
        public Combination {
            methodId = ContractValues.token(methodId); credentialFormatId = ContractValues.token(credentialFormatId);
            actions = ContractValues.set(actions, true); purposes = ContractValues.set(purposes, false);
            Objects.requireNonNull(preparationEffect); capabilities = ContractValues.set(capabilities, true);
            limits = List.copyOf(Objects.requireNonNull(limits));
            if (limits.isEmpty() || limits.size() > 32 || limits.stream().map(CurrencyLimit::currency).distinct().count() != limits.size())
                throw new IllegalArgumentException("Ambiguous currency limits.");
        }
        public boolean permits(ExactMoney money) { return limits.stream().anyMatch(limit -> limit.permits(money)); }
        public String digest() {
            var fields = new ArrayList<>(List.of("provider-execution-combination-1", methodId, credentialFormatId,
                    ContractValues.names(actions), ContractValues.names(purposes), preparationEffect.name(), ContractValues.names(capabilities)));
            fields.addAll(limits.stream().map(CurrencyLimit::canonical).sorted().toList()); return ContractValues.hash(fields);
        }
    }
    public String digest() {
        var fields = new ArrayList<>(List.of(VERSION, profileVersion, providerId, implementationRevision));
        fields.addAll(combinations.stream().map(Combination::digest).sorted().toList()); return ContractValues.hash(fields);
    }
}
