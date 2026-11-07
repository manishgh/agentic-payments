package io.agenticpayments.ap2;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Set;
import static io.agenticpayments.ap2.Ap2Exception.Code.*;

/** Independently authored bounded JOSE framing, using standard JDK cryptographic primitives. */
public final class Ap2Crypto {
    public static final String MANDATE_TYPE = "dc+sd-jwt";
    public static final String CHECKOUT_TYPE = "ap2-checkout+jwt";
    public static final String CHECKOUT_RECEIPT_TYPE = "ap2-checkout-receipt+jwt";
    public static final String PAYMENT_RECEIPT_TYPE = "ap2-payment-receipt+jwt";
    private static final int MAX_COMPACT = 131072;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(24)
            .maxStringLength(65536).maxNumberLength(20).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private Ap2Crypto() {}

    public static ObjectNode parseJson(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > 65536) throw new Ap2Exception(INVALID_JSON);
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode node = JSON.readTree(text);
            if (!(node instanceof ObjectNode object)) throw new Ap2Exception(INVALID_JSON);
            return object;
        } catch (Ap2Exception e) { throw e; }
        catch (Exception e) { throw new Ap2Exception(INVALID_JSON); }
    }

    public static byte[] jsonBytes(JsonNode value) {
        if (value == null || !value.isObject()) throw new Ap2Exception(INVALID_JSON);
        try {
            byte[] bytes = JSON.writeValueAsBytes(value);
            parseJson(bytes);
            return bytes;
        } catch (Ap2Exception e) { throw e; }
        catch (Exception e) { throw new Ap2Exception(INVALID_JSON); }
    }

    public static ObjectNode object() { return JSON.createObjectNode(); }

    public static String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }

    public static byte[] decode(String encoded) {
        if (encoded == null || encoded.isEmpty() || encoded.length() > MAX_COMPACT
                || !encoded.matches("[A-Za-z0-9_-]+")) throw new Ap2Exception(INVALID_ENCODING);
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (!encode(bytes).equals(encoded)) throw new Ap2Exception(INVALID_ENCODING);
            return bytes;
        } catch (IllegalArgumentException e) { throw new Ap2Exception(INVALID_ENCODING); }
    }

    private static byte[] ascii(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_COMPACT
                || value.chars().anyMatch(c -> c > 127)) throw new Ap2Exception(INVALID_ENCODING);
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    public static String hash(String exactAscii) {
        try { return encode(MessageDigest.getInstance("SHA-256").digest(ascii(exactAscii))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA256_UNAVAILABLE"); }
    }

    public static String issuerJwt(String sdJwt) {
        ascii(sdJwt);
        if (!sdJwt.endsWith("~") || sdJwt.indexOf('~') != sdJwt.length() - 1)
            throw new Ap2Exception(UNSUPPORTED_PROFILE);
        return sdJwt.substring(0, sdJwt.length() - 1);
    }

    private static String[] segments(String compact) {
        ascii(compact);
        String[] parts = compact.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()
                || parts[0].length() > 2048) throw new Ap2Exception(INVALID_ENCODING);
        return parts;
    }

    public static ObjectNode protectedHeader(String compact) {
        String[] parts = segments(compact);
        ObjectNode header = parseJson(decode(parts[0]));
        Set<String> allowed = Set.of("alg", "kid", "typ");
        if (header.size() != 3 || !allowed.stream().allMatch(k -> header.has(k) && header.get(k).isTextual())
                || !header.get("alg").textValue().equals("ES256")) throw new Ap2Exception(INVALID_HEADER);
        boundedHeaderValue(header.get("kid").textValue());
        boundedHeaderValue(header.get("typ").textValue());
        return header;
    }

    private static void boundedHeaderValue(String value) {
        if (value == null || !value.matches("[\\x21-\\x7e]{1,128}")) throw new Ap2Exception(INVALID_HEADER);
    }

    public static void requireP256(Key key) {
        try {
            if (!(key instanceof ECKey ec) || ec.getParams() == null) throw new Ap2Exception(INVALID_KEY);
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec expected = parameters.getParameterSpec(ECParameterSpec.class);
            ECParameterSpec actual = ec.getParams();
            if (!actual.getCurve().equals(expected.getCurve()) || !actual.getGenerator().equals(expected.getGenerator())
                    || !actual.getOrder().equals(expected.getOrder()) || actual.getCofactor() != expected.getCofactor())
                throw new Ap2Exception(INVALID_KEY);
        } catch (Ap2Exception e) { throw e; }
        catch (GeneralSecurityException e) { throw new Ap2Exception(INVALID_KEY); }
    }

    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) { throw new Ap2Exception(INVALID_KEY); }
    }

    public static PublicKey publicKey(String base64urlSpki) {
        try {
            byte[] der = decode(base64urlSpki);
            if (der.length > 1024) throw new Ap2Exception(INVALID_KEY);
            PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
            requireP256(key);
            if (!MessageDigest.isEqual(der, key.getEncoded())) throw new Ap2Exception(INVALID_KEY);
            return key;
        } catch (GeneralSecurityException e) { throw new Ap2Exception(INVALID_KEY); }
    }

    public static String publicKeyEncoded(PublicKey key) { requireP256(key); return encode(key.getEncoded()); }

    /** Caller owns trusted signing-key custody; this library exposes no agent-facing signer service. */
    public static String sign(byte[] payload, PrivateKey key, String kid, String typ) {
        requireP256(key);
        boundedHeaderValue(kid); boundedHeaderValue(typ);
        parseJson(payload);
        ObjectNode header = object().put("alg", "ES256").put("kid", kid).put("typ", typ);
        String input = encode(jsonBytes(header)) + "." + encode(payload);
        try {
            Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
            signer.initSign(key, new SecureRandom());
            signer.update(ascii(input));
            byte[] signature = signer.sign();
            if (signature.length != 64) throw new Ap2Exception(INVALID_SIGNATURE);
            return input + "." + encode(signature);
        } catch (GeneralSecurityException e) { throw new Ap2Exception(INVALID_SIGNATURE); }
    }

    public static ObjectNode verify(String compact, PublicKey trustedKey, String expectedKid, String expectedTyp) {
        requireP256(trustedKey);
        String[] parts = segments(compact);
        ObjectNode header = protectedHeader(compact);
        if (!header.get("kid").textValue().equals(expectedKid) || !header.get("typ").textValue().equals(expectedTyp))
            throw new Ap2Exception(INVALID_HEADER);
        byte[] payload = decode(parts[1]);
        byte[] signature = decode(parts[2]);
        if (payload.length > 65536 || signature.length != 64) throw new Ap2Exception(INVALID_ENCODING);
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
            verifier.initVerify(trustedKey);
            verifier.update(ascii(parts[0] + "." + parts[1]));
            if (!verifier.verify(signature)) throw new Ap2Exception(INVALID_SIGNATURE);
        } catch (GeneralSecurityException e) { throw new Ap2Exception(INVALID_SIGNATURE); }
        return parseJson(payload);
    }
}
