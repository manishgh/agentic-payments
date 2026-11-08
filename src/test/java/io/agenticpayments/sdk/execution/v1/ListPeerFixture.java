package io.agenticpayments.sdk.execution.v1;

import io.agenticpayments.sdk.execution.v1.tck.*;
import java.time.Instant;
import java.util.*;

/** Second independently authored execution algorithm: append-only bindings and separate peer log. */
class ListPeerFixture implements ExecutionTckFixture {
    private final ExecutionOffer offer;
    private final int selectedCombination;
    private final OperationPurpose defaultPurpose;
    private record Bound(ExecutionOperation operation, Optional<SubmissionRequest> request, Optional<ExecutionEvidence> known) { }
    private record PeerCall(ExecutionOperation operation, ExecutionEvidence result, boolean accepted) { }
    private record Fault(ExecutionIdentity.ExecutionKey key, Control type) { }
    private final List<Bound> journal = new ArrayList<>();
    private final List<PeerCall> peerLog = new ArrayList<>();
    private final Set<ExecutionIdentity.ExecutionKey> providerKeys = new HashSet<>();
    private final Set<Fault> faults = new HashSet<>();
    private Ports ports;
    ListPeerFixture() { this(FixtureOperations.offer("synthetic-list-1", ExecutionOffer.PreparationEffect.EFFECT_FREE, Set.of()), 0, OperationPurpose.CHARGE); }
    ListPeerFixture(ExecutionOffer offer, int selectedCombination, OperationPurpose defaultPurpose) {
        this.offer = offer; this.selectedCombination = selectedCombination; this.defaultPurpose = defaultPurpose; restart();
    }
    public String fixtureId() { return "independent-list-peer-1"; }
    public ExecutionOffer offer() { return offer; }
    public ExecutionBinding binding() { return FixtureOperations.BINDING; }
    public ExecutionOperation operation(String name) { return FixtureOperations.operation(offer, binding(), name, selectedCombination, defaultPurpose); }
    public Ports ports() { return ports; }
    public void restart() { ports = portsForAuthenticatedScope("fixture-tenant", "merchant-demo", "fixture-run"); }
    public Ports portsForAuthenticatedScope(String tenant, String merchant, String run) {
        var fresh = new Executor(tenant, merchant, run); return new Ports(fresh, fresh, fresh, Optional.empty(), Optional.empty(), Optional.empty());
    }
    public RestartModel restartModel() { return RestartModel.IN_PROCESS_RECONSTRUCTION; }
    public Instant now() { return FixtureOperations.NOW; }
    public synchronized Inspection inspect() {
        boolean valid = journal.stream().allMatch(row -> row.request.isEmpty() || row.request.orElseThrow().operation().equals(row.operation));
        return new Inspection(peerLog.size(), peerLog.stream().filter(PeerCall::accepted).count(), 0, providerKeys.size(), valid);
    }
    public synchronized void control(Control c, ExecutionOperation op) {
        if (c == Control.EXPIRE_PROVIDER_KEYS) providerKeys.remove(op.identity().key());
        if (c == Control.LOOKUP_UNAVAILABLE) faults.remove(new Fault(op.identity().key(), Control.LOOKUP_ABSENT));
        faults.add(new Fault(op.identity().key(), c));
    }
    private boolean fault(ExecutionOperation op, Control c) { return faults.contains(new Fault(op.identity().key(), c)); }
    public EventVerificationRequest event(ExecutionOperation operation, EventKind kind) { throw new UnsupportedOperationException("Events are undeclared."); }
    private final class Executor implements ExecutionPreparer, FinancialSubmitter, ExecutionReconciler {
        private final String tenant, merchant, run;
        Executor(String tenant, String merchant, String run) { this.tenant = tenant; this.merchant = merchant; this.run = run; }
        private Bound bind(ExecutionOperation op) {
            var id = op.identity();
            if (!tenant.equals(id.tenantId()) || !merchant.equals(id.merchantId()) || !run.equals(id.runId()))
                throw new ExecutionFailure(ExecutionFailure.Code.INVALID_SELECTION, ExecutionFailure.Certainty.NOT_STARTED);
            op.selection().validate(offer, binding());
            var found = journal.stream().filter(row -> row.operation.identity().key().equals(op.identity().key())).reduce((a, b) -> b);
            if (found.isPresent()) {
                if (!found.orElseThrow().operation.equals(op)) throw FixtureOperations.conflict();
                return found.orElseThrow();
            }
            var initial = new Bound(op, Optional.empty(), Optional.empty()); journal.add(initial); return initial;
        }
        public PreparationResult prepare(ExecutionOperation op) {
            synchronized (ListPeerFixture.this) {
                bind(op); boolean action = fault(op, Control.ACTION_REQUIRED);
                return new PreparationResult(op, action ? PreparationResult.Status.ACTION_REQUIRED : PreparationResult.Status.READY,
                        ExecutionOffer.PreparationEffect.EFFECT_FREE, Optional.empty(), action ? Optional.of(FixtureOperations.action(op)) : Optional.empty());
            }
        }
        public ExecutionEvidence submit(SubmissionRequest request) {
            synchronized (ListPeerFixture.this) {
                var op = request.operation(); var previous = bind(op);
                if (previous.request.isPresent()) {
                    if (!previous.request.orElseThrow().equals(request)) throw FixtureOperations.conflict();
                    return reconcile(op);
                }
                // Claim appears before the peer call, even if its result is lost.
                journal.add(new Bound(op, Optional.of(request), Optional.empty()));
                if (fault(op, Control.UNSAFE_EXCEPTION)) {
                    try { throw new IllegalStateException("CANARY_PRIVATE_PROVIDER_HEADER"); }
                    catch (RuntimeException unsafe) { return FixtureOperations.unknown(op); }
                }
                if (providerKeys.contains(op.identity().key())) return peerLog.stream().filter(row -> row.operation.equals(op)).findFirst().orElseThrow().result;
                var state = fault(op, Control.REFUSED) ? ExecutionEvidence.State.DEFINITIVELY_REFUSED : fault(op, Control.PENDING)
                        ? ExecutionEvidence.State.PENDING : op.purpose() == OperationPurpose.AUTHORIZE ? ExecutionEvidence.State.AUTHORIZED : ExecutionEvidence.State.CAPTURED;
                var peer = FixtureOperations.evidence(op, "list-peer-" + peerLog.size(), ExecutionEvidence.Source.SUBMIT, state);
                peerLog.add(new PeerCall(op, peer, state != ExecutionEvidence.State.DEFINITIVELY_REFUSED));
                providerKeys.add(op.identity().key());
                if (fault(op, Control.SUBMIT_RESPONSE_LOSS)) return FixtureOperations.unknown(op);
                journal.add(new Bound(op, Optional.of(request), Optional.of(peer))); return peer;
            }
        }
        public ExecutionEvidence reconcile(ExecutionOperation op) {
            synchronized (ListPeerFixture.this) {
                var existing = bind(op);
                if (existing.known.isPresent() && existing.known.orElseThrow().terminalForSubmission()) return existing.known.orElseThrow();
                if (fault(op, Control.LOOKUP_ABSENT) || fault(op, Control.LOOKUP_UNAVAILABLE)) return FixtureOperations.unknown(op);
                var peer = peerLog.stream().filter(entry -> entry.operation.equals(op)).findFirst();
                if (peer.isEmpty()) return FixtureOperations.unknown(op);
                var fact = peer.orElseThrow().result;
                journal.add(new Bound(op, existing.request, Optional.of(fact))); return fact;
            }
        }
    }
}
