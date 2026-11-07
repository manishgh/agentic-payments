package io.agenticpayments.composition;

import io.agenticpayments.crypto.JcaSignatureProvider;
import java.time.Instant;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static io.agenticpayments.composition.Composition.*;

/** Trusted reference provisioning. Enrollment is separate from all untrusted issued artifact values. */
public final class FixtureKeyProvisioner implements KeyProvisioner {
    private final JcaSignatureProvider provider;
    private final Map<Scope,Provisioning> scopes=new HashMap<>();
    public FixtureKeyProvisioner(JcaSignatureProvider provider) { this.provider=Objects.requireNonNull(provider); }
    @Override public Descriptor describe() {
        return new Descriptor("key-provisioner",VERSION,"transient-synthetic-keys",Set.of(PROFILE),
            Set.of("scoped-enrollment","ephemeral-private-keys"),"trusted-fixture-provisioner");
    }
    @Override public synchronized Provisioning provision(Scope scope,Instant now) {
        Objects.requireNonNull(scope); Objects.requireNonNull(now);
        Provisioning old=scopes.get(scope);
        if(old!=null&&old.enrollments().stream().allMatch(k->!now.isBefore(k.validFrom())&&now.isBefore(k.validUntil()))) return old;
        String domain=ArtifactIntegrity.digest(scope.tenantId().getBytes(StandardCharsets.UTF_8)).substring(7,23)+":"+scope.runId();
        String merchantIssuer="urn:agentic-payments:merchant:"+domain, tapIssuer="urn:agentic-payments:tap:"+domain;
        var merchant=provider.generate(); var tap=provider.generate();
        String merchantKid="merchant-"+UUID.randomUUID(), tapKid="tap-"+UUID.randomUUID();
        Instant from=Instant.ofEpochSecond(now.getEpochSecond()), until=from.plusSeconds(86400);
        var keys=new ArrayList<TrustKey>();
        String merchantPublic=Base64.getUrlEncoder().withoutPadding().encodeToString(merchant.publicKeySpki());
        String tapPublic=Base64.getUrlEncoder().withoutPadding().encodeToString(tap.publicKeySpki());
        keys.add(new TrustKey(scope,merchantIssuer,merchantKid,MERCHANT_CHECKOUT,merchantPublic,from,until,1,false));
        for(String purpose:List.of(CHECKOUT_MANDATE,PAYMENT_MANDATE,PROCESSOR_PAYMENT_MANDATE))
            keys.add(new TrustKey(scope,tapIssuer,tapKid,purpose,tapPublic,from,until,1,false));
        Provisioning created=new Provisioning(keys,new SigningIdentity(merchantIssuer,merchantKid,merchant.handle()),
            new SigningIdentity(tapIssuer,tapKid,tap.handle()));
        scopes.put(scope,created); return created;
    }
}
