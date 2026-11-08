package io.agenticpayments.sdk.execution.v1;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Pinned length-prefixed UTF-8 canonical encoding; no locale or record diagnostics. */
final class ContractValues {
    private ContractValues() { }
    static String token(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            throw new IllegalArgumentException("Invalid bounded opaque identifier.");
        return value;
    }
    static String digest(String value) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid fingerprint.");
        return value;
    }
    static <T> Set<T> set(Set<T> values, boolean emptyAllowed) {
        Objects.requireNonNull(values);
        if ((!emptyAllowed && values.isEmpty()) || values.size() > 32)
            throw new IllegalArgumentException("Invalid bounded capability set.");
        return Set.copyOf(values);
    }
    static String hash(List<String> fields) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(fields.size());
            for (String field : fields) {
                byte[] encoded = Objects.requireNonNull(field).getBytes(StandardCharsets.UTF_8);
                out.writeInt(encoded.length); out.write(encoded);
            }
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Required canonical fingerprint unavailable.");
        }
    }
    static String names(Set<? extends Enum<?>> values) {
        return hash(values.stream().map(Enum::name).sorted().toList());
    }
    static void require(boolean valid, ExecutionFailure.Code code) {
        if (!valid) throw new ExecutionFailure(code, ExecutionFailure.Certainty.NOT_STARTED);
    }
}
