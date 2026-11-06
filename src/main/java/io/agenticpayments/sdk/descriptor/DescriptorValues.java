package io.agenticpayments.sdk.descriptor;

import java.math.BigInteger;
import java.util.Collection;
import java.util.List;
import java.util.Set;

final class DescriptorValues {
    private DescriptorValues() { }
    static String identifier(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9.-]{0,63}")) throw new IllegalArgumentException("Invalid capability identifier.");
        return value;
    }
    static String revision(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) throw new IllegalArgumentException("Invalid exact revision.");
        return value;
    }
    static Set<String> identifiers(Set<String> values, boolean emptyAllowed) {
        if (values == null || values.size() > 64 || (!emptyAllowed && values.isEmpty())) throw new IllegalArgumentException("Invalid capability collection.");
        values.forEach(DescriptorValues::identifier);
        return Set.copyOf(values);
    }
    static Set<String> revisions(Set<String> values) {
        if (values == null || values.isEmpty() || values.size() > 64) throw new IllegalArgumentException("Explicit versions are required.");
        values.forEach(DescriptorValues::revision);
        return Set.copyOf(values);
    }
    static <T> List<T> distinct(List<T> values) {
        if (values == null || values.isEmpty() || values.size() > 64 || Set.copyOf(values).size() != values.size()) throw new IllegalArgumentException("A bounded distinct collection is required.");
        return List.copyOf(values);
    }
    static String currency(String value) {
        if (value == null || !value.matches("[A-Z]{3}")) throw new IllegalArgumentException("Invalid currency metadata.");
        return value;
    }
    static BigInteger amount(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,29}")) throw new IllegalArgumentException("Positive integer minor units are required.");
        return new BigInteger(value);
    }
    static String sorted(Collection<String> values) { return values.stream().sorted().collect(java.util.stream.Collectors.joining(",")); }
}
