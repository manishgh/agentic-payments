package io.agenticpayments.application;

import io.agenticpayments.composition.ArtifactIntegrity;
import io.agenticpayments.composition.Composition.*;
import io.agenticpayments.domain.PaymentRules;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static io.agenticpayments.composition.Composition.PROFILE;

/** Native artifact/verification policy; transaction, trust and financial mutations belong to admission. */
public final class NativeApplicationCoordinator {
    /** Property names and string-form instants preserve the existing durable JSON representation. */
    public record StoredCheckout(String sessionId,ArtifactReference acpPayload,ArtifactReference checkoutJwt,
                                 String checkoutHash,String checkoutDigest,String payloadDigest,String merchantIssuer,
                                 String handlerId,String protocolVersion) {
        @Override public String toString() { return "StoredCheckout[redacted]"; }
    }
    public record StoredAuthority(ArtifactReference checkoutMandate,ArtifactReference paymentMandate,String issuer,
                                  String checkoutAudience,String paymentAudience,String checkoutNonce,String paymentNonce,
                                  String issuedAt,String expiresAt) {
        @Override public String toString() { return "StoredAuthority[redacted]"; }
    }

    private final CheckoutProtocol checkout;
    private final AuthorityIssuer issuer;
    private final AuthorityVerifier verifier;
    private final SignedArtifactStore artifacts;

    public NativeApplicationCoordinator(CheckoutProtocol checkout,AuthorityIssuer issuer,
                                        AuthorityVerifier verifier,SignedArtifactStore artifacts) {
        this.checkout=Objects.requireNonNull(checkout); this.issuer=Objects.requireNonNull(issuer);
        this.verifier=Objects.requireNonNull(verifier); this.artifacts=Objects.requireNonNull(artifacts);
    }

    public FrozenCheckout freezeCheckout(CheckoutRequest request) { return checkout.freeze(request); }

    /** Bind the transaction's current resolver only after freezing/reloading has completed. */
    public void validateCheckout(CheckoutFacts facts,FrozenCheckout frozen,KeyResolver keys,Instant now) {
        checkout.validate(facts,frozen,keys,now);
    }

    public StoredCheckout retainCheckout(Scope scope,FrozenCheckout frozen) {
        return new StoredCheckout(frozen.sessionId(),retain(scope,frozen.acpPayload(),"application/json"),
            retain(scope,frozen.checkoutJwt(),"application/jwt"),frozen.checkoutHash(),frozen.checkoutDigest(),
            frozen.payloadDigest(),frozen.merchantIssuer(),frozen.handlerId(),frozen.protocolVersion());
    }

    public FrozenCheckout reloadCheckout(Scope scope,StoredCheckout stored,String expectedDigest) {
        if(!Objects.equals(stored.checkoutDigest(),expectedDigest)) invalid();
        return new FrozenCheckout(stored.sessionId(),artifact(scope,stored.acpPayload()),artifact(scope,stored.checkoutJwt()),
            stored.checkoutHash(),stored.checkoutDigest(),stored.payloadDigest(),stored.merchantIssuer(),stored.handlerId(),stored.protocolVersion());
    }

    public StoredAuthority issueAndRetainAuthority(IssuanceRequest request) {
        var issued=issuer.issue(request);
        var context=request.context();
        return new StoredAuthority(retain(context.facts().scope(),issued.checkoutMandate(),"application/jwt"),
            retain(context.facts().scope(),issued.paymentMandate(),"application/jwt"),context.issuer(),context.checkoutAudience(),
            context.paymentAudience(),context.checkoutNonce(),context.paymentNonce(),context.issuedAt().toString(),context.expiresAt().toString());
    }

    /** Admission persists provisional authority/challenges before reloading; issuance is never verification. */
    public IssuedAuthority reloadAuthority(Scope scope,StoredAuthority stored) {
        return new IssuedAuthority(artifact(scope,stored.checkoutMandate()),artifact(scope,stored.paymentMandate()));
    }

    public String authorityDigest(StoredAuthority stored) {
        return PaymentRules.sha256(stored.checkoutMandate().digest()+"\n"+stored.paymentMandate().digest());
    }

    /** Reverification checks the durable digest after artifact reads and before binding its fresh resolver. */
    public void validateAuthorityDigest(StoredAuthority stored,String expectedDigest) {
        if(!authorityDigest(stored).equals(expectedDigest)) invalid();
    }

    public VerifiedAuthority verifyAuthority(AuthorityContext context,IssuedAuthority reloaded,KeyResolver keys,Instant now) {
        var verified=verifier.verify(new AuthorityRequest(context,reloaded),keys,now);
        var roles=new HashSet<String>();
        for(var role:verified.authorizations())
            if(!roles.add(role.role())||!role.issuer().equals(context.issuer())||!context.issuedAt().isBefore(role.expiresAt())
                    ||role.expiresAt().isAfter(context.expiresAt())) invalid();
        if(!roles.equals(Set.of("MERCHANT","CREDENTIAL_PROVIDER","PAYMENT_PROCESSOR"))
                ||!verified.checkoutHash().equals(context.checkout().checkoutHash())||verified.expiresAt().isAfter(context.expiresAt())
                ||!context.issuedAt().isBefore(verified.expiresAt())) invalid();
        return verified;
    }

    private ArtifactReference retain(Scope scope,String value,String mediaType) {
        // Independent immutable staging may leave harmless unreferenced bytes after admission rollback.
        return artifacts.put(scope,new Artifact(value.getBytes(StandardCharsets.UTF_8),mediaType,PROFILE));
    }

    private String artifact(Scope scope,ArtifactReference reference) {
        if(!PROFILE.equals(reference.profile())) invalid();
        byte[] bytes=ArtifactIntegrity.verify(artifacts.get(scope,reference),reference).bytes();
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch(java.nio.charset.CharacterCodingException invalidEncoding) { throw new PaymentException(422,"AUTHORITY_BINDING_INVALID"); }
    }

    private static void invalid() { throw new PaymentException(422,"AUTHORITY_BINDING_INVALID"); }
}
