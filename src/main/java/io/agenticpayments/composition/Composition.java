package io.agenticpayments.composition;

import java.time.Instant;
import java.util.*;

/** Versioned responsibility contracts. None of these contracts contains a storage/provider SDK type. */
public final class Composition {
    private Composition() {}
    public static final String PROFILE = "local-ap2-acp-single-tender-0.1";
    public static final String AP2_PROFILE = "ap2-v0.2-direct-tap-synthetic-0.1";
    public static final String ACP_VERSION = "2026-04-17";
    public static final String VERSION = "1";
    public static final String MERCHANT_CHECKOUT = "MERCHANT_CHECKOUT";
    public static final String CHECKOUT_MANDATE = "CHECKOUT_MANDATE";
    public static final String PAYMENT_MANDATE = "PAYMENT_MANDATE";
    public static final String PROCESSOR_PAYMENT_MANDATE = "PROCESSOR_PAYMENT_MANDATE";

    public enum Failure { INVALID_INPUT, UNSUPPORTED_CAPABILITY, INVALID_AUTHORITY, EXPIRED_AUTHORITY,
        UNTRUSTED_KEY, SIGNING_UNAVAILABLE, CRYPTO_UNAVAILABLE, ARTIFACT_UNAVAILABLE, ARTIFACT_CONFLICT }
    public static final class CompositionException extends IllegalArgumentException {
        private final Failure failure;
        public CompositionException(Failure failure) { super(failure.name()); this.failure=Objects.requireNonNull(failure); }
        public Failure failure() { return failure; }
    }
    public static void reject(Failure failure) { throw new CompositionException(failure); }
    static String identity(String value) {
        if(value==null||value.isEmpty()||value.length()>512||value.chars().anyMatch(c->c<32||c==127)) reject(Failure.INVALID_INPUT);
        return value;
    }
    static String artifact(String value) {
        if(value==null||value.isEmpty()||value.length()>131072) reject(Failure.INVALID_INPUT);
        return value;
    }
    public record Descriptor(String contract, String version, String implementation, Set<String> profiles,
                             Set<String> capabilities, String owner) {
        public Descriptor { identity(contract); identity(version); identity(implementation); identity(owner);
            profiles=Set.copyOf(profiles); capabilities=Set.copyOf(capabilities); }
        public void require(String requiredContract, String profile, String... requiredCapabilities) {
            if(!VERSION.equals(version)||!contract.equals(requiredContract)||!profiles.contains(profile)
                    ||!capabilities.containsAll(List.of(requiredCapabilities))) reject(Failure.UNSUPPORTED_CAPABILITY);
        }
    }
    public record Scope(String tenantId, UUID runId) {
        public Scope { identity(tenantId); Objects.requireNonNull(runId); }
        @Override public String toString() { return "Scope[redacted]"; }
    }
    public record CheckoutFacts(Scope scope, UUID intentId, String merchantId, int revision,
                                String currency, long minorUnits, String credentialReference, Instant expiresAt) {
        public CheckoutFacts { Objects.requireNonNull(scope); Objects.requireNonNull(intentId); identity(merchantId);
            identity(credentialReference); Objects.requireNonNull(expiresAt);
            if(revision<1||currency==null||!currency.matches("[A-Z]{3}")||minorUnits<1) reject(Failure.INVALID_INPUT); }
        @Override public String toString() { return "CheckoutFacts[redacted]"; }
    }
    /** Exact transient artifacts. Persistence stores ArtifactReferences, never these records in public responses. */
    public record FrozenCheckout(String sessionId, String acpPayload, String checkoutJwt, String checkoutHash,
                                  String checkoutDigest, String payloadDigest, String merchantIssuer,
                                  String handlerId, String protocolVersion) {
        public FrozenCheckout { identity(sessionId); artifact(acpPayload); artifact(checkoutJwt); identity(checkoutHash);
            identity(checkoutDigest); identity(payloadDigest); identity(merchantIssuer); identity(handlerId); identity(protocolVersion); }
        @Override public String toString() { return "FrozenCheckout[artifacts redacted]"; }
    }
    public record SigningIdentity(String issuer, String kid, String keyHandle) {
        public SigningIdentity { identity(issuer); identity(kid); identity(keyHandle); }
        @Override public String toString() { return "SigningIdentity[redacted]"; }
    }
    public record TrustKey(Scope scope, String issuer, String kid, String purpose, String publicKeySpki,
                            Instant validFrom, Instant validUntil, long version, boolean revoked) {
        public TrustKey { Objects.requireNonNull(scope); identity(issuer); identity(kid); identity(purpose); artifact(publicKeySpki);
            Objects.requireNonNull(validFrom); Objects.requireNonNull(validUntil);
            if(!validFrom.isBefore(validUntil)||version<1) reject(Failure.INVALID_INPUT); }
        @Override public String toString() { return "TrustKey[public enrollment redacted]"; }
    }
    public record Provisioning(List<TrustKey> enrollments, SigningIdentity merchantSigner, SigningIdentity tapSigner) {
        public Provisioning { enrollments=List.copyOf(enrollments); Objects.requireNonNull(merchantSigner); Objects.requireNonNull(tapSigner); }
        @Override public String toString() { return "Provisioning[redacted]"; }
    }
    public record CheckoutRequest(CheckoutFacts facts, SigningIdentity signer) {
        public CheckoutRequest { Objects.requireNonNull(facts); Objects.requireNonNull(signer); }
        @Override public String toString() { return "CheckoutRequest[redacted]"; }
    }
    public record AuthorityContext(CheckoutFacts facts, FrozenCheckout checkout, String subjectId, String grantId,
                                    long grantVersion, String issuer, String checkoutAudience, String paymentAudience,
                                    String checkoutNonce, String paymentNonce, Instant issuedAt, Instant expiresAt) {
        public AuthorityContext { Objects.requireNonNull(facts); Objects.requireNonNull(checkout); identity(subjectId); identity(grantId);
            identity(issuer); identity(checkoutAudience); identity(paymentAudience); identity(checkoutNonce); identity(paymentNonce);
            Objects.requireNonNull(issuedAt); Objects.requireNonNull(expiresAt);
            if(grantVersion<1||!issuedAt.isBefore(expiresAt)||expiresAt.isAfter(facts.expiresAt())
                    ||checkoutNonce.equals(paymentNonce)) reject(Failure.INVALID_INPUT); }
        @Override public String toString() { return "AuthorityContext[redacted]"; }
    }
    public record IssuanceRequest(AuthorityContext context, SigningIdentity signer) {
        public IssuanceRequest { Objects.requireNonNull(context); Objects.requireNonNull(signer); }
        @Override public String toString() { return "IssuanceRequest[redacted]"; }
    }
    public record IssuedAuthority(String checkoutMandate, String paymentMandate) {
        public IssuedAuthority { artifact(checkoutMandate); artifact(paymentMandate); }
        @Override public String toString() { return "IssuedAuthority[artifacts redacted]"; }
    }
    public record AuthorityRequest(AuthorityContext context, IssuedAuthority artifacts) {
        public AuthorityRequest { Objects.requireNonNull(context); Objects.requireNonNull(artifacts); }
        @Override public String toString() { return "AuthorityRequest[redacted]"; }
    }
    public record RoleAuthorization(String role, String issuer, String jwtId, Instant expiresAt) {
        public RoleAuthorization { identity(role); identity(issuer); identity(jwtId); Objects.requireNonNull(expiresAt); }
        @Override public String toString() { return "RoleAuthorization[redacted]"; }
    }
    public record VerifiedAuthority(String checkoutHash, String checkoutMandateHash, String paymentMandateHash,
                                     List<RoleAuthorization> authorizations, Instant expiresAt) {
        public VerifiedAuthority { identity(checkoutHash); identity(checkoutMandateHash); identity(paymentMandateHash);
            authorizations=List.copyOf(authorizations); Objects.requireNonNull(expiresAt); }
        @Override public String toString() { return "VerifiedAuthority[redacted]"; }
    }
    public record Artifact(byte[] bytes, String mediaType, String profile) {
        public Artifact { if(bytes==null||bytes.length==0||bytes.length>131072) reject(Failure.INVALID_INPUT);
            bytes=bytes.clone(); identity(mediaType); identity(profile); }
        @Override public byte[] bytes() { return bytes.clone(); }
        @Override public String toString() { return "Artifact[bytes redacted]"; }
    }
    public record ArtifactReference(String id, String digest, int length, String mediaType, String profile) {
        public ArtifactReference { identity(id); identity(digest); identity(mediaType); identity(profile);
            if(length<1||length>131072) reject(Failure.INVALID_INPUT); }
        @Override public String toString() { return "ArtifactReference[redacted]"; }
    }
    public interface CheckoutProtocol {
        Descriptor describe();
        FrozenCheckout freeze(CheckoutRequest request);
        CheckoutFacts validate(CheckoutFacts expected, FrozenCheckout checkout, KeyResolver keys, Instant now);
    }
    public interface AuthorityIssuer { Descriptor describe(); IssuedAuthority issue(IssuanceRequest request); }
    public interface AuthorityVerifier {
        Descriptor describe();
        VerifiedAuthority verify(AuthorityRequest request, KeyResolver keys, Instant now);
    }
    public interface KeyResolver {
        String CONTRACT_VERSION = "1";
        /** Current enrolled trust, resolved within the admission owner's revocation ordering. */
        TrustKey resolve(Scope scope, String issuer, String kid, String purpose, Instant now);
    }
    public record EnrollmentSnapshot(Scope scope, Instant now, List<TrustKey> keys) {
        public EnrollmentSnapshot {
            Objects.requireNonNull(scope); Objects.requireNonNull(now); keys=List.copyOf(keys);
            Set<List<String>> identities=new HashSet<>();
            for(TrustKey key:keys) if(!scope.equals(key.scope())||!identities.add(List.of(key.issuer(),key.kid(),key.purpose())))
                reject(Failure.INVALID_INPUT);
        }
        @Override public String toString() { return "EnrollmentSnapshot[redacted]"; }
    }
    /** Creates a transaction-scoped resolver using the admission owner's immutable enrollment snapshot. */
    public interface KeyResolverFactory {
        Descriptor describe();
        KeyResolver bind(EnrollmentSnapshot snapshot);
    }
    /** Trusted provisioning only. Never populate enrollment from issuer/verifier output or an HTTP request. */
    public interface KeyProvisioner { Descriptor describe(); Provisioning provision(Scope scope, Instant now); }
    public interface SignedArtifactStore {
        Descriptor describe();
        ArtifactReference put(Scope scope, Artifact artifact);
        Artifact get(Scope scope, ArtifactReference reference);
    }
    public record Ports(CheckoutProtocol checkout, AuthorityIssuer issuer, AuthorityVerifier verifier,
                         KeyProvisioner provisioner, SignedArtifactStore artifacts, KeyResolverFactory keyResolvers) {
        public Ports { Objects.requireNonNull(checkout); Objects.requireNonNull(issuer); Objects.requireNonNull(verifier);
            Objects.requireNonNull(provisioner); Objects.requireNonNull(artifacts); Objects.requireNonNull(keyResolvers); }
        public Ports(CheckoutProtocol checkout,AuthorityIssuer issuer,AuthorityVerifier verifier,
                     KeyProvisioner provisioner,SignedArtifactStore artifacts) {
            this(checkout,issuer,verifier,provisioner,artifacts,new EnrolledKeyResolverFactory());
        }
        public void requireSupported() {
            checkout.describe().require("checkout-protocol",PROFILE,"immutable-checkout","exact-money","local-freeze");
            issuer.describe().require("authority-issuer",PROFILE,"closed-mandates");
            verifier.describe().require("authority-verifier",PROFILE,"independent-signatures","role-bound","read-only");
            provisioner.describe().require("key-provisioner",PROFILE,"scoped-enrollment","ephemeral-private-keys");
            artifacts.describe().require("signed-artifact-store",PROFILE,"immutable","digest-verified","scoped","durable-retention");
            keyResolvers.describe().require("key-resolver",PROFILE,"scoped-enrollment","current-trust","transaction-snapshot");
            requireOwner(checkout.describe(),"simulator-core"); requireOwner(issuer.describe(),"simulator-core");
            requireOwner(verifier.describe(),"simulator-core"); requireOwner(provisioner.describe(),"trusted-fixture-provisioner");
            requireOwner(artifacts.describe(),"artifact-store"); requireOwner(keyResolvers.describe(),"admission-trust");
        }
        private static void requireOwner(Descriptor descriptor,String expected) {
            if(!expected.equals(descriptor.owner())) reject(Failure.UNSUPPORTED_CAPABILITY);
        }
    }
}
