package io.agenticpayments.application;

import io.agenticpayments.application.PaymentCommandTransactions.*;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.domain.OperationBinding;
import io.agenticpayments.domain.PaymentRules;
import io.agenticpayments.sdk.CapabilityOffer;
import java.util.*;
import java.util.function.Supplier;

/** Execution-command policy, independent of persistence and provider effects. */
public final class ExecutePayment {
    public static final String NATIVE_PROFILE="local-ap2-acp-single-tender-0.1";
    private static final String ROUTE="POST /intents/{id}/execute";
    private final PaymentCommandTransactions transactions;
    private final Supplier<CapabilityOffer> capabilities;

    public ExecutePayment(PaymentCommandTransactions transactions,Supplier<CapabilityOffer> capabilities) {
        this.transactions=Objects.requireNonNull(transactions);this.capabilities=Objects.requireNonNull(capabilities);
    }

    public Result execute(Session session,UUID id,String key,ExecuteCommand command) {
        if(session==null) fail(401,"UNAUTHENTICATED");
        if(!session.roles().contains("execution:write")) fail(403,"FORBIDDEN");
        if(command==null||command.expectedVersion()<1) fail(400,"INVALID_REQUEST");
        if(!CapabilityOffer.localFake().equals(capabilities.get())) fail(422,"UNSUPPORTED_CAPABILITY");
        String resource=id.toString();
        if(key==null||!key.matches("[\\x20-\\x7E]{16,128}")) fail(400,"INVALID_REQUEST");
        String digest=transactions.requestDigest(command);
        var identity=new CommandIdentity(ROUTE,resource,key);
        return transactions.run(session,tx->{
            authorize(session,tx.intent(id));
            var accepted=tx.accepted(identity);
            if(accepted.isPresent()) {
                var previous=accepted.orElseThrow();
                if(!digest.equals(previous.requestDigest())) fail(409,"IDEMPOTENCY_CONFLICT");
                var replay=tx.replay(previous);return new Result(replay.status(),replay.body(),true);
            }
            var intent=tx.intent(id);authorize(session,intent);
            if(intent.version()!=command.expectedVersion()) fail(409,"VERSION_CONFLICT");
            if(!"APPROVED".equals(intent.state())) fail(409,"INVALID_STATE");
            var attempt=tx.attempt(id);var checkout=tx.checkout(intent);var grant=tx.grant(attempt.grantId());
            if(!Objects.equals(grant.subjectId(),attempt.subjectId())||!Objects.equals(grant.merchantId(),intent.merchantId())) fail(422,"AUTHORITY_BINDING_INVALID");
            if("REVOKED".equals(grant.state())) fail(409,"AUTHORITY_REVOKED");
            if(tx.now().isBefore(grant.validFrom())||!tx.now().isBefore(grant.expiresAt())) fail(410,"AUTHORITY_EXPIRED");
            if(!"ACTIVE".equals(grant.state())||grant.version()!=attempt.grantVersion()
                    ||tx.now().isBefore(grant.validFrom())||!tx.now().isBefore(grant.expiresAt())
                    ||!Objects.equals(grant.subjectId(),attempt.subjectId())||!Objects.equals(grant.merchantId(),intent.merchantId())) fail(409,"AUTHORITY_REVOKED");
            if(NATIVE_PROFILE.equals(intent.profile())||"local-external-ap2-acp-single-tender-0.1".equals(intent.profile())) tx.verifyNative(intent,attempt,grant);
            if(!Objects.equals(command.attemptId(),attempt.id())) fail(422,"AUTHORITY_BINDING_INVALID");
            UUID operation=UUID.randomUUID();
            String binding=new OperationBinding(session.tenantId(),session.runId(),intent.merchantId(),id,attempt.id(),operation,
                checkout.digest(),checkout.currency(),checkout.minorUnits(),attempt.grantId(),attempt.grantVersion(),attempt.subjectId(),
                intent.scenarioId(),checkout.credentialReference()).digest();
            tx.prepare(new PreparationWrite(id,command.expectedVersion(),attempt.id(),operation,binding));
            var body=new LinkedHashMap<String,Object>();
            body.put("contractVersion",PaymentRules.CONTRACT);body.put("correlationId",UUID.randomUUID().toString());
            body.put("intentId",id);body.put("state","PREPARING");body.put("version",command.expectedVersion()+1);
            body.put("attemptId",attempt.id());body.put("operationId",operation);
            var result=new Result(202,body,false);tx.accept(identity,digest,result);return result;
        });
    }

    private static void authorize(Session session,ExecutionIntent intent) {
        if(!session.allowedMerchants().contains(intent.merchantId())||!"merchant-demo".equals(intent.merchantId())) fail(404,"RESOURCE_NOT_FOUND");
    }
    private static void fail(int status,String code){throw new PaymentException(status,code);}
}
