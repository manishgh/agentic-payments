package io.agenticpayments.sdk.descriptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Provider declarations describe support; they neither authorize nor instantiate execution. */
public record AdapterDescriptor(String schemaVersion, String providerId, String revision,
                                Set<String> extensionVersions, Set<String> profiles,
                                Set<String> capabilities, List<AdapterCombination> combinations) {
    public static final String SCHEMA_VERSION = "adapter-descriptor-0.1";
    public AdapterDescriptor {
        if (!SCHEMA_VERSION.equals(schemaVersion)) throw new IllegalArgumentException("Unsupported descriptor schema.");
        providerId = DescriptorValues.identifier(providerId);
        revision = DescriptorValues.revision(revision);
        extensionVersions = DescriptorValues.revisions(extensionVersions);
        profiles = DescriptorValues.revisions(profiles);
        capabilities = DescriptorValues.identifiers(capabilities, true);
        combinations = DescriptorValues.distinct(combinations);
        for (AdapterCombination combination : combinations) {
            if (!capabilities.containsAll(combination.capabilities())) throw new IllegalArgumentException("Combination capabilities must be declared.");
        }
    }
    /** Versioned delimiter encoding: all values exclude delimiters; unordered sets/tuples are sorted. */
    public String digest() {
        String canonical = String.join("\n", SCHEMA_VERSION, providerId, revision,
                DescriptorValues.sorted(extensionVersions), DescriptorValues.sorted(profiles), DescriptorValues.sorted(capabilities),
                combinations.stream().map(AdapterCombination::canonical).sorted().collect(java.util.stream.Collectors.joining("\n")));
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("Required digest is unavailable."); }
    }
}
