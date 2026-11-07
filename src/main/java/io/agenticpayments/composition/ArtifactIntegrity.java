package io.agenticpayments.composition;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import static io.agenticpayments.composition.Composition.*;

/** Immutable artifact identity is independent of storage and never a proof of payment authority. */
public final class ArtifactIntegrity {
    private ArtifactIntegrity() {}
    public static String digest(byte[] bytes) {
        if(bytes==null) { reject(Failure.INVALID_INPUT); }
        try { return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException("SHA256_UNAVAILABLE"); }
    }
    public static ArtifactReference reference(Artifact artifact) {
        String digest=digest(artifact.bytes());
        // Media/profile are part of reference identity; identical bytes may validly have different purposes.
        String identity=digest((digest+"\n"+artifact.mediaType()+"\n"+artifact.profile()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new ArtifactReference(identity.substring(7),digest,artifact.bytes().length,artifact.mediaType(),artifact.profile());
    }
    public static Artifact verify(Artifact artifact,ArtifactReference reference) {
        if(!reference(artifact).equals(reference)) reject(Failure.ARTIFACT_CONFLICT);
        return artifact;
    }
}
