package io.agenticpayments.application;

import io.agenticpayments.application.PaymentService.*;
import java.util.*;

/** Synthetic interaction followed by independently resolved provider truth and atomic core recheck. */
public final class CompleteCustomerAction {
    private final CustomerActionTransactions transactions;
    private final SyntheticAuthenticationDecisions decisions;
    private final ProviderAuthenticationResolver resolver;
    public CompleteCustomerAction(CustomerActionTransactions transactions,SyntheticAuthenticationDecisions decisions,ProviderAuthenticationResolver resolver) {
        this.transactions=Objects.requireNonNull(transactions);this.decisions=Objects.requireNonNull(decisions);this.resolver=Objects.requireNonNull(resolver);
    }
    public Result complete(Session session,UUID actionId,String key,CompleteActionCommand command) {
        if(session==null)throw new PaymentException(ApplicationFailure.UNAUTHENTICATED);
        if(!session.roles().contains("action:complete"))throw new PaymentException(ApplicationFailure.FORBIDDEN);
        if(actionId==null||key==null||key.isBlank()||command==null||command.expectedVersion()<1
                ||command.decision()==null||!Set.of("approve","deny").contains(command.decision()))
            throw new PaymentException(ApplicationFailure.INVALID_REQUEST);
        var reservation=transactions.run(session,tx->tx.reserve(actionId,key,command));
        if(reservation.replay().isPresent())return reservation.replay().orElseThrow();
        var request=reservation.request().orElseThrow();
        if(!request.actionId().equals(actionId)||!request.key().equals(key)||request.expectedVersion()!=command.expectedVersion()
                ||!request.subjectId().equals(session.principalId())||!request.decision().equals(command.decision()))
            throw new PaymentException(ApplicationFailure.AUTHORITY_BINDING_INVALID);
        AuthenticationObservation observation;
        try {
            decisions.decide(request.binding(),request.subjectId(),request.requestId(),request.decision(),request.now());
            observation=resolver.resolve(request.binding()).orElseThrow(()->new ApplicationException(ApplicationFailure.TRUSTED_RESOLUTION_REQUIRED));
        }catch(ApplicationException safe){throw safe;}
        catch(RuntimeException unsafe){throw new ApplicationException(ApplicationFailure.TEMPORARILY_UNAVAILABLE_SAME_COMMAND);}
        if(!observation.binding().equals(request.binding())||!observation.subjectId().equals(request.subjectId())
                ||!observation.observedAt().isBefore(request.binding().action().expiresAt())
                ||observation.outcome()!=(request.decision().equals("approve")?AuthenticationObservation.Outcome.VERIFIED:AuthenticationObservation.Outcome.DENIED))
            throw new PaymentException(ApplicationFailure.AUTHORITY_BINDING_INVALID);
        return transactions.run(session,tx->tx.finish(request,observation));
    }
}
