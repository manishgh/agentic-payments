package io.agenticpayments.application;

import java.time.Instant;
import java.util.*;

/** Authenticated application facade; callers cannot directly set payment state. */
public final class PaymentService {
    public record Session(String tenantId, UUID runId, String principalId, Set<String> roles, Set<String> allowedMerchants, Set<String> allowedGrants) {
        public Session { Objects.requireNonNull(tenantId); Objects.requireNonNull(runId); Objects.requireNonNull(principalId); roles=Set.copyOf(roles); allowedMerchants=Set.copyOf(allowedMerchants); allowedGrants=Set.copyOf(allowedGrants); }
    }
    public record CreateCommand(String merchantId,String scenarioId,String profile,List<String> requiredCapabilities) {}
    public record Tender(String provider,String method,String credentialReference) {}
    public record QuoteCommand(int expectedVersion,String cartFixtureId,List<Tender> tenders) {}
    public record ApproveCommand(int expectedVersion,int checkoutRevision,String checkoutDigest,String authorityReference) {}
    public record ExecuteCommand(int expectedVersion,UUID attemptId) {}
    public record CompleteActionCommand(int expectedVersion,String decision) {}
    public record RevokeCommand(String reason) {}
    public record Result(int status,Map<String,Object> body,boolean replayed) { public Result { body=Collections.unmodifiableMap(new LinkedHashMap<>(body)); } }
    private final PaymentStore store;
    public PaymentService(PaymentStore store) { this.store=Objects.requireNonNull(store); }
    public Result create(Session s,String k,CreateCommand c) { return store.create(s,k,c); }
    public Result quote(Session s,UUID id,String k,QuoteCommand c) { return store.quote(s,id,k,c); }
    public Result approve(Session s,UUID id,String k,ApproveCommand c) { return store.approve(s,id,k,c); }
    public Result execute(Session s,UUID id,String k,ExecuteCommand c) { return store.execute(s,id,k,c); }
    public Result status(Session s,UUID id) { return store.status(s,id); }
    public Result checkout(Session s,UUID id) { return store.checkout(s,id); }
    public Result action(Session s,UUID id) { return store.action(s,id); }
    public Result completeAction(Session s,UUID id,String k,CompleteActionCommand c) { return store.completeAction(s,id,k,c); }
    public Result revoke(Session s,String id,String k,RevokeCommand c) { return store.revoke(s,id,k,c); }
    public void seedRun(Session s,Instant clock,long budget) { store.seedRun(s,clock,budget); }
    public void advanceClock(Session s,Instant clock) { store.advanceClock(s,clock); }
    public boolean runNext(Session s) { return store.runNext(s); }
    public void reconcile(Session s,UUID id,boolean sealIfAbsent) { store.reconcile(s,id,sealIfAbsent); }
}
