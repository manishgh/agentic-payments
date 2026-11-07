package io.agenticpayments.composition;

import java.util.*;
import static io.agenticpayments.composition.Composition.*;

/** Reference key-service adapter. Alternative services/decorators implement the same snapshot contract. */
public final class EnrolledKeyResolverFactory implements KeyResolverFactory {
    private record Identity(String issuer,String kid,String purpose) {}
    @Override public Descriptor describe() {
        return new Descriptor("key-resolver",VERSION,"enrolled-snapshot-keys",Set.of(PROFILE),
            Set.of("scoped-enrollment","current-trust","transaction-snapshot"),"admission-trust");
    }
    @Override public KeyResolver bind(EnrollmentSnapshot snapshot) {
        Objects.requireNonNull(snapshot); Map<Identity,TrustKey> enrolled=new HashMap<>();
        for(TrustKey key:snapshot.keys()) enrolled.put(new Identity(key.issuer(),key.kid(),key.purpose()),key);
        Map<Identity,TrustKey> immutable=Map.copyOf(enrolled);
        return (scope,issuer,kid,purpose,now)->{
            if(!snapshot.scope().equals(scope)||!snapshot.now().equals(now)) reject(Failure.UNTRUSTED_KEY);
            TrustKey key=immutable.get(new Identity(issuer,kid,purpose));
            if(key==null||key.revoked()||now.isBefore(key.validFrom())||!now.isBefore(key.validUntil())) reject(Failure.UNTRUSTED_KEY);
            return key;
        };
    }
}
