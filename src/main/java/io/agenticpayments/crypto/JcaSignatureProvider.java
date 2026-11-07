package io.agenticpayments.crypto;

import java.security.*;
import java.security.interfaces.ECKey;
import java.security.spec.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Reference implementation. Private keys never leave this provider's process-local custody. */
public final class JcaSignatureProvider implements SignatureProvider {
    public record GeneratedKey(String handle, byte[] publicKeySpki) {
        public GeneratedKey { publicKeySpki=publicKeySpki.clone(); }
        @Override public byte[] publicKeySpki() { return publicKeySpki.clone(); }
        @Override public String toString() { return "GeneratedKey[redacted]"; }
    }
    private final Map<String,PrivateKey> privateKeys=new ConcurrentHashMap<>();
    @Override public String contractVersion() { return CONTRACT_VERSION; }
    @Override public Set<String> supportedSuites() { return Set.of(ES256); }
    public GeneratedKey generate() {
        try {
            KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"),new SecureRandom());
            KeyPair pair=generator.generateKeyPair(); String handle=UUID.randomUUID().toString();
            privateKeys.put(handle,pair.getPrivate()); return new GeneratedKey(handle,pair.getPublic().getEncoded());
        } catch(GeneralSecurityException e) { throw failure(); }
    }
    private static IllegalArgumentException failure() { return new IllegalArgumentException("SIGNATURE_PROVIDER_FAILURE"); }
    private static void input(String suite,byte[] bytes) {
        if(!ES256.equals(suite)||bytes==null||bytes.length==0||bytes.length>131072) throw failure();
    }
    private static void requireP256(Key key) throws GeneralSecurityException {
        if(!(key instanceof ECKey ec)) throw failure();
        AlgorithmParameters p=AlgorithmParameters.getInstance("EC"); p.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec a=ec.getParams(), b=p.getParameterSpec(ECParameterSpec.class);
        if(a==null||!a.getCurve().equals(b.getCurve())||!a.getGenerator().equals(b.getGenerator())
                ||!a.getOrder().equals(b.getOrder())||a.getCofactor()!=b.getCofactor()) throw failure();
    }
    @Override public byte[] sign(String suite,String opaqueKeyHandle,byte[] originalBytes) {
        input(suite,originalBytes);
        try {
            PrivateKey key=opaqueKeyHandle==null?null:privateKeys.get(opaqueKeyHandle);
            requireP256(key); Signature signature=Signature.getInstance("SHA256withECDSAinP1363Format");
            signature.initSign(key,new SecureRandom()); signature.update(originalBytes.clone());
            byte[] result=signature.sign(); if(result.length!=64) throw failure(); return result;
        } catch(GeneralSecurityException e) { throw failure(); }
    }
    @Override public boolean verify(String suite,byte[] publicKeySpki,byte[] originalBytes,byte[] signature) {
        input(suite,originalBytes);
        if(publicKeySpki==null||publicKeySpki.length>1024||signature==null||signature.length!=64) return false;
        try {
            PublicKey key=KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(publicKeySpki.clone()));
            requireP256(key); if(!MessageDigest.isEqual(publicKeySpki,key.getEncoded())) return false;
            Signature verifier=Signature.getInstance("SHA256withECDSAinP1363Format");
            verifier.initVerify(key); verifier.update(originalBytes.clone()); return verifier.verify(signature.clone());
        } catch(NoSuchAlgorithmException|ProviderException e) { throw new UnavailableException(); }
        catch(GeneralSecurityException|IllegalArgumentException e) { return false; }
    }
}
