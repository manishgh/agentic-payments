package io.agenticpayments.application;

import io.agenticpayments.application.PaymentService.Session;
import io.agenticpayments.application.PaymentWorkerTransactions.*;
import io.agenticpayments.domain.PaymentRules;
import io.agenticpayments.sdk.*;
import java.time.Instant;
import java.util.*;

/** Worker control policy. Financial effects are outside every transaction callback. */
public final class RunPaymentWork {
    private final PaymentWorkerTransactions transactions;
    private final PaymentAdapter adapter;
    private final PaymentFactVerifier verifier;
    private final SyntheticPendingProgression progression;
    private record Ticket(ChargeOperation operation,Claim claim) {}
    private record Step(Activity before,Selection selection,Ticket ticket) {}

    public RunPaymentWork(PaymentWorkerTransactions transactions,PaymentAdapter adapter,
                          PaymentFactVerifier verifier,SyntheticPendingProgression progression) {
        this.transactions=Objects.requireNonNull(transactions);this.adapter=Objects.requireNonNull(adapter);
        this.verifier=Objects.requireNonNull(verifier);this.progression=Objects.requireNonNull(progression);
    }
    public boolean runNext(Session session) {
        var receipt=runNextRecorded(session);return receipt.operationId()!=null||receipt.progressed();
    }
    public WorkerReceipt runNextRecorded(Session session) {
        authorize(session);
        if(!CapabilityOffer.localFake().equals(adapter.capabilities())) throw new PaymentException(ApplicationFailure.UNSUPPORTED_CAPABILITY);
        Step step=transactions.run(session,tx->{
            var before=tx.activity();tx.expireEligible();var next=tx.next();
            if(next.isEmpty())return new Step(before,Selection.idle(),null);
            var work=next.orElseThrow();var snapshot=tx.load(work.operationId());
            var selection=new Selection(work.operationId(),work.kind(),work.fence());
            if(PaymentRules.TERMINAL.contains(snapshot.state())) {
                tx.completeObsolete(work);return new Step(before,selection,null);
            }
            var claim=tx.claim(work);selection=new Selection(claim.operationId(),claim.kind(),claim.fence());
            if(claim.kind()==WorkKind.PREPARE&&nativeProfile(snapshot)&&!tx.nativeGate(snapshot))return new Step(before,selection,null);
            if(claim.kind()==WorkKind.DISPATCH) {
                if(!"READY".equals(snapshot.state())||snapshot.budgetFrozen()) {
                    tx.completeObsolete(work);return new Step(before,selection,null);
                }
                if(!eligible(snapshot,tx.now())) {
                    tx.terminateBeforeAdmission(snapshot,authorityTermination(snapshot));return new Step(before,selection,null);
                }
                if(nativeProfile(snapshot)&&!tx.nativeGate(snapshot))return new Step(before,selection,null);
                if(needsAction(snapshot)&&!validAction(snapshot,tx.now())) {
                    tx.terminateBeforeAdmission(snapshot,Termination.FAILED);return new Step(before,selection,null);
                }
                if(!"HELD".equals(snapshot.reservationState()))throw new IllegalStateException("Missing execution exposure");
                tx.admit(claim,snapshot);
            }
            return new Step(before,selection,new Ticket(snapshot.operation(),claim));
        });
        if(step.ticket()!=null) {
            var ticket=step.ticket();
            if(ticket.claim().kind()==WorkKind.PREPARE) {
                Preparation preparation=adapter.prepare(ticket.operation());
                transactions.run(session,tx->{
                    var snapshot=tx.load(ticket.claim().operationId());
                    if(!"PREPARING".equals(snapshot.state())||!tx.isCurrent(ticket.claim()))return null;
                    if(!eligible(snapshot,tx.now())) {tx.terminateBeforeAdmission(snapshot,authorityTermination(snapshot));return null;}
                    if(nativeProfile(snapshot)&&!tx.nativeGate(snapshot))return null;
                    if((preparation.actionType()==ActionType.SYNTHETIC_CHALLENGE)!=needsAction(snapshot)) {
                        tx.terminateBeforeAdmission(snapshot,Termination.FAILED);return null;
                    }
                    tx.completePreparation(ticket.claim(),snapshot,preparation.actionType());return null;
                });
            } else if(ticket.claim().kind()==WorkKind.DISPATCH) {
                try { accept(session,ticket.operation(),adapter.execute(ticket.operation())); }
                catch(AdapterUnavailableException unavailable) { unknown(session,ticket); }
            } else {
                try {
                    var lookup=adapter.lookup(ticket.operation().identity());
                    if(lookup.status()==LookupResult.Status.FOUND) {
                        var fact=lookup.fact().orElseThrow();
                        if(fact.outcome()==Outcome.PENDING)fact=progression.progress(ticket.operation(),fact);
                        accept(session,ticket.operation(),fact);
                    } else unknown(session,ticket);
                } catch(AdapterUnavailableException unavailable) { unknown(session,ticket); }
            }
        }
        return transactions.run(session,tx->tx.receipt(step.before(),step.selection()));
    }
    public void reconcile(Session session,UUID intentId,boolean sealIfAbsent) {
        authorize(session);
        Ticket ticket=transactions.run(session,tx->{
            var snapshot=tx.findAdmitted(intentId);return new Ticket(snapshot.operation(),tx.reconciliationClaim(snapshot));
        });
        try {
            var lookup=adapter.lookup(ticket.operation().identity());
            if(lookup.status()==LookupResult.Status.FOUND)accept(session,ticket.operation(),lookup.fact().orElseThrow());
            else if(lookup.status()==LookupResult.Status.NOT_FOUND&&sealIfAbsent)accept(session,ticket.operation(),adapter.sealNoEffect(ticket.operation()));
            else unknown(session,ticket);
        } catch(AdapterUnavailableException unavailable) {unknown(session,ticket);}
    }
    private void accept(Session session,ChargeOperation expected,Fact delivered) {
        Fact verified=verifier.verify(expected,delivered);
        if(!Objects.equals(verified,delivered))throw new IllegalArgumentException("Unverified adapter fact");
        transactions.run(session,tx->{tx.applyVerifiedFact(expected,delivered);return null;});
    }
    private void unknown(Session session,Ticket ticket) {
        transactions.run(session,tx->{
            var snapshot=tx.load(ticket.claim().operationId());
            if(!snapshot.operation().equals(ticket.operation()))throw new IllegalArgumentException("Unbound payment observation");
            if(ticket.claim().kind()==WorkKind.RECONCILE&&!tx.isCurrent(ticket.claim()))return null;
            if("ADMITTED".equals(snapshot.state()))tx.markUnknown(ticket.claim(),snapshot);
            return null;
        });
    }
    private static boolean eligible(Snapshot snapshot,Instant now) {
        var grant=snapshot.grant();
        return "ACTIVE".equals(grant.state())&&grant.version()==snapshot.grantVersion()
                &&!now.isBefore(grant.validFrom())&&now.isBefore(grant.expiresAt())
                &&Objects.equals(grant.subjectId(),snapshot.subjectId())
                &&Objects.equals(grant.merchantId(),snapshot.operation().identity().scope().merchantId());
    }
    private static Termination authorityTermination(Snapshot snapshot) {
        return "REVOKED".equals(snapshot.grant().state())?Termination.CANCELLED:Termination.EXPIRED;
    }
    private static boolean nativeProfile(Snapshot snapshot) {
        return "local-ap2-acp-single-tender-0.1".equals(snapshot.profile())||"local-external-ap2-acp-single-tender-0.1".equals(snapshot.profile());
    }
    private static boolean needsAction(Snapshot snapshot) {return "challenge-then-success".equals(snapshot.operation().scenarioId());}
    private static boolean validAction(Snapshot snapshot,Instant now) {
        var action=snapshot.action();return action!=null&&"VERIFIED".equals(action.state())&&now.isBefore(action.expiresAt())
                &&Objects.equals(action.subjectId(),snapshot.subjectId())&&Objects.equals(action.checkoutDigest(),snapshot.checkoutDigest());
    }
    private static void authorize(Session session) {
        if(session==null)throw new PaymentException(ApplicationFailure.UNAUTHENTICATED);
        if(!session.roles().contains("runner:write"))throw new PaymentException(ApplicationFailure.FORBIDDEN);
    }
}
