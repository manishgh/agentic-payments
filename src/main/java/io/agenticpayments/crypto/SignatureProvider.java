package io.agenticpayments.crypto;

import java.util.Set;

/** Stable original-byte signature contract; algorithms are selected by profile, never an untrusted header. */
public interface SignatureProvider {
    String CONTRACT_VERSION = "1";
    String ES256 = "ES256";
    String contractVersion();
    Set<String> supportedSuites();
    /** Retryable backend uncertainty, not evidence that the signature is invalid. No provider detail escapes. */
    final class UnavailableException extends RuntimeException {
        public UnavailableException() { super("SIGNATURE_PROVIDER_UNAVAILABLE",null,false,false); }
    }
    byte[] sign(String suite, String opaqueKeyHandle, byte[] originalBytes);
    /** False means a definitive signature rejection. Backend outages must throw UnavailableException. */
    boolean verify(String suite, byte[] publicKeySpki, byte[] originalBytes, byte[] signature);
}
