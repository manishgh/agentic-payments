package io.agenticpayments.composition;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static io.agenticpayments.composition.Composition.*;

/** Replaceable immutable fixture store; intentionally declares no crash durability or financial atomicity. */
public final class InMemoryArtifactStore implements SignedArtifactStore {
    private record Identity(Scope scope,String id) {}
    private final Map<Identity,Artifact> artifacts=new ConcurrentHashMap<>();
    @Override public Descriptor describe() { return descriptor("memory-fixture-artifacts"); }
    public static Descriptor descriptor(String implementation) {
        return new Descriptor("signed-artifact-store",VERSION,implementation,Set.of(PROFILE),
            Set.of("immutable","digest-verified","scoped"),"artifact-store");
    }
    @Override public ArtifactReference put(Scope scope,Artifact artifact) {
        Objects.requireNonNull(scope); Objects.requireNonNull(artifact); ArtifactReference ref=ArtifactIntegrity.reference(artifact);
        Artifact previous=artifacts.putIfAbsent(new Identity(scope,ref.id()),artifact);
        if(previous!=null) ArtifactIntegrity.verify(previous,ref);
        return ref;
    }
    @Override public Artifact get(Scope scope,ArtifactReference reference) {
        Artifact value=artifacts.get(new Identity(Objects.requireNonNull(scope),reference.id()));
        if(value==null) reject(Failure.ARTIFACT_UNAVAILABLE);
        return ArtifactIntegrity.verify(value,reference);
    }
}
