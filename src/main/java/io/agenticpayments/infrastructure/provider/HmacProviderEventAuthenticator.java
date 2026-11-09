package io.agenticpayments.infrastructure.provider;

import io.agenticpayments.application.ProviderEventAuthenticator;
import io.agenticpayments.sdk.execution.v1.ExecutionFailure;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Synthetic HMAC-SHA256 profile. Secret selection is trusted host configuration, never body input. */
public final class HmacProviderEventAuthenticator implements ProviderEventAuthenticator {
    private final byte[] secret;
    public HmacProviderEventAuthenticator(byte[] secret){if(secret==null||secret.length<32||secret.length>128)throw new IllegalArgumentException("Invalid event key.");this.secret=secret.clone();}
    @Override public void authenticate(byte[] body,String signature){
        try {
            if(body==null||body.length==0||body.length>16384||signature==null||!signature.matches("[0-9a-f]{64}"))throw invalid();
            var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            if(!MessageDigest.isEqual(mac.doFinal(body),HexFormat.of().parseHex(signature)))throw invalid();
        }catch(ExecutionFailure safe){throw safe;}catch(Exception unsafe){throw invalid();}
    }
    private static ExecutionFailure invalid(){return new ExecutionFailure(ExecutionFailure.Code.INVALID_EVENT,ExecutionFailure.Certainty.NOT_STARTED);}
}
