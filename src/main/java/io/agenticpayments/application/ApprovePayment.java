package io.agenticpayments.application;

import io.agenticpayments.application.PaymentCommandTransactions.*;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.domain.PaymentRules;
import io.agenticpayments.sdk.CapabilityOffer;
import java.util.*;
import java.util.function.Supplier;

/** Approval-command policy, independent of persistence and native protocol implementations. */
public final class ApprovePayment {
    private static final String ROUTE="POST /intents/{id}/approve";
    private final PaymentCommandTransactions transactions;
    private final Supplier<CapabilityOffer> capabilities;

    public ApprovePayment(PaymentCommandTransactions transactions,Supplier<CapabilityOffer> capabilities) {
        this.transactions=Objects.requireNonNull(transactions);this.capabilities=Objects.requireNonNull(capabilities);
    }

    public Result approve(Session session,UUID id,String key,ApproveCommand command) {
        if(session==null) fail(ApplicationFailure.UNAUTHENTICATED);
        if(!session.roles().contains("approval:write")) fail(ApplicationFailure.FORBIDDEN);
        if(command==null||command.expectedVersion()<1) fail(ApplicationFailure.INVALID_REQUEST);
        if(!session.allowedGrants().contains(command.authorityReference())) fail(ApplicationFailure.RESOURCE_NOT_FOUND);
        if(!CapabilityOffer.localFake().equals(capabilities.get())) fail(ApplicationFailure.UNSUPPORTED_CAPABILITY);
        String resource=id.toString();
        if(key==null||!key.matches("[\\x20-\\x7E]{16,128}")) fail(ApplicationFailure.INVALID_REQUEST);
        String digest=transactions.approvalRequestDigest(command);
        var identity=new CommandIdentity(ROUTE,resource,key);
        return transactions.runApproval(session,tx->{
            authorize(session,tx.intent(id));
            var accepted=tx.accepted(identity);
            if(accepted.isPresent()) {
                var previous=accepted.orElseThrow();
                if(!digest.equals(previous.requestDigest())) fail(ApplicationFailure.IDEMPOTENCY_CONFLICT);
                var replay=tx.replay(previous);return new Result(replay.status(),replay.body(),true);
            }
            var intent=tx.intent(id);authorize(session,intent);
            if(intent.version()!=command.expectedVersion()) fail(ApplicationFailure.VERSION_CONFLICT);
            if(!"QUOTED".equals(intent.state())) fail(ApplicationFailure.INVALID_STATE);
            var checkout=tx.checkout(intent);var grant=tx.grant(command.authorityReference());
            if(!Objects.equals(grant.subjectId(),session.principalId())||!Objects.equals(grant.merchantId(),intent.merchantId())) fail(ApplicationFailure.AUTHORITY_BINDING_INVALID);
            if("REVOKED".equals(grant.state())) fail(ApplicationFailure.AUTHORITY_REVOKED);
            if(tx.now().isBefore(grant.validFrom())||!tx.now().isBefore(grant.expiresAt())) fail(ApplicationFailure.AUTHORITY_EXPIRED);
            if(!tx.now().isBefore(checkout.expiresAt())) fail(ApplicationFailure.AUTHORITY_EXPIRED);
            if(command.checkoutRevision()!=checkout.revision()||!Objects.equals(command.checkoutDigest(),checkout.digest())) fail(ApplicationFailure.CHECKOUT_CHANGED);
            var budget=tx.budget(grant.budgetId());long total=checkout.minorUnits();
            if(!Objects.equals(budget.currency(),checkout.currency())) fail(ApplicationFailure.AUTHORITY_BINDING_INVALID);
            if(budget.frozen()||budget.held()+budget.consumed()+total>budget.limitMinor()) fail(ApplicationFailure.BUDGET_EXCEEDED);
            UUID attempt=UUID.randomUUID();
            var write=new ApprovalWrite(id,command.expectedVersion(),attempt,command.checkoutRevision(),command.authorityReference(),grant.version(),session.principalId(),grant.budgetId(),total);
            tx.stageAttempt(write);
            boolean nativeProfile="local-ap2-acp-single-tender-0.1".equals(intent.profile())||"local-external-ap2-acp-single-tender-0.1".equals(intent.profile());
            if(nativeProfile) tx.issueAndVerifyNative(intent,checkout,grant,write);
            tx.reserveAndApprove(write);
            var body=new LinkedHashMap<String,Object>();
            body.put("contractVersion",PaymentRules.CONTRACT);body.put("correlationId",UUID.randomUUID().toString());
            body.put("intentId",id);body.put("state","APPROVED");body.put("version",command.expectedVersion()+1);
            body.put("attemptId",attempt);body.put("executionOwner","simulator-core");
            body.put("authorityProfile",nativeProfile?"ap2-v0.2-direct-tap-synthetic-0.1":"local-fake");body.put("authorityVersion",grant.version());
            var reserved=new LinkedHashMap<String,Object>();reserved.put("currency",checkout.currency());reserved.put("minorUnits",Long.toString(total));
            body.put("reserved",reserved);
            var result=new Result(200,body,false);tx.accept(identity,digest,result);return result;
        });
    }

    private static void authorize(Session session,ApprovalIntent intent) {
        if(!session.allowedMerchants().contains(intent.merchantId())||!"merchant-demo".equals(intent.merchantId())) fail(ApplicationFailure.RESOURCE_NOT_FOUND);
    }
    private static void fail(ApplicationFailure failure){throw new PaymentException(failure);}
}
