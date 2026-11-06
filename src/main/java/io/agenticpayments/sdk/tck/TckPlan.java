package io.agenticpayments.sdk.tck;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record TckPlan(String suiteVersion, String selectedProfile, Set<String> requiredCapabilities,
                      Set<String> requiredCaseIds, List<String> caseIds, String seed, Instant initialClock) {
    public static final String VERSION = "adapter-tck-0.1";
    public static final List<String> CASES = List.of("AT-PREPARE", "AT-IDEMPOTENCY", "AT-LOOKUP", "AT-SEAL");
    public TckPlan {
        if (!VERSION.equals(suiteVersion) || selectedProfile == null || !selectedProfile.matches("[a-z0-9][a-z0-9.-]{0,63}")) throw new IllegalArgumentException("Unsupported TCK plan.");
        requiredCapabilities = Set.copyOf(requiredCapabilities);
        requiredCaseIds = Set.copyOf(requiredCaseIds);
        caseIds = List.copyOf(caseIds);
        if (caseIds.isEmpty() || caseIds.size() > 64 || Set.copyOf(caseIds).size() != caseIds.size()
                || requiredCaseIds.isEmpty() || !caseIds.containsAll(requiredCaseIds)) throw new IllegalArgumentException("Invalid TCK case selection.");
        for (String capability : requiredCapabilities) if (!capability.matches("[a-z][a-z0-9.-]{0,63}")) throw new IllegalArgumentException("Invalid capability.");
        for (String name : caseIds) if (!name.matches("AT-[A-Z0-9-]{1,48}")) throw new IllegalArgumentException("Invalid case identity.");
        try { if (!Long.toString(Long.parseLong(seed)).equals(seed)) throw new NumberFormatException(); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Canonical seed is required."); }
        Objects.requireNonNull(initialClock);
    }
    public TckPlanEvidence evidence() {
        String format = "adapter-tck-plan-0.1";
        String canonical = String.join("\n", format, suiteVersion, selectedProfile,
                requiredCapabilities.stream().sorted().collect(java.util.stream.Collectors.joining(",")),
                requiredCaseIds.stream().sorted().collect(java.util.stream.Collectors.joining(",")),
                String.join(",", caseIds), seed, initialClock.toString());
        try {
            String digest = "sha256:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return new TckPlanEvidence(format, suiteVersion, selectedProfile, requiredCapabilities, requiredCaseIds, caseIds, seed, initialClock.toString(), digest);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("Required digest is unavailable."); }
    }
}
