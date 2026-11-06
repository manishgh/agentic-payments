package io.agenticpayments.domain;

import java.util.Objects;
import java.util.UUID;

/** Versioned, fixed-order encoding of every locally immutable dispatch parameter. */
public record OperationBinding(String tenantId,UUID runId,String merchantId,UUID intentId,UUID attemptId,
                               UUID operationId,String checkoutDigest,String currency,long minorUnits,
                               String grantId,long grantVersion,String subjectId,String scenarioId,String credentialReference) {
    public OperationBinding { Objects.requireNonNull(tenantId); Objects.requireNonNull(runId); Objects.requireNonNull(merchantId); Objects.requireNonNull(intentId); Objects.requireNonNull(attemptId); Objects.requireNonNull(operationId); Objects.requireNonNull(checkoutDigest); Objects.requireNonNull(currency); Objects.requireNonNull(grantId); Objects.requireNonNull(subjectId); Objects.requireNonNull(scenarioId); Objects.requireNonNull(credentialReference); }
    public String canonical() {
        return "{\"schema\":\"synthetic-charge-0.1\",\"tenantId\":"+quoted(tenantId)+",\"runId\":"+quoted(runId.toString())
                +",\"merchantId\":"+quoted(merchantId)+",\"intentId\":"+quoted(intentId.toString())+",\"attemptId\":"+quoted(attemptId.toString())
                +",\"operationId\":"+quoted(operationId.toString())+",\"adapterKey\":"+quoted(operationId.toString())
                +",\"checkoutDigest\":"+quoted(checkoutDigest)+",\"currency\":"+quoted(currency)+",\"minorUnits\":"+quoted(Long.toString(minorUnits))
                +",\"grantId\":"+quoted(grantId)+",\"grantVersion\":"+grantVersion+",\"subjectId\":"+quoted(subjectId)
                +",\"scenarioId\":"+quoted(scenarioId)+",\"credentialReference\":"+quoted(credentialReference)
                +",\"owner\":\"simulator-core\",\"catalogVersion\":\"fixtures-0.1\"}";
    }
    public String digest() { return PaymentRules.sha256(canonical()); }
    private static String quoted(String s) {
        var b=new StringBuilder("\"");
        for(char ch:s.toCharArray()) {
            if(ch=='"'||ch=='\\') b.append('\\').append(ch);
            else if(ch<0x20) b.append(String.format(java.util.Locale.ROOT,"\\u%04x",(int)ch));
            else b.append(ch);
        }
        return b.append('"').toString();
    }
}
