package io.agenticpayments.sdk.execution.v1;

import io.agenticpayments.sdk.execution.v1.tck.*;
import java.time.Instant;
import java.util.*;
import static io.agenticpayments.sdk.execution.v1.tck.ExecutionTckFixture.Control.*;

/** Independent synthetic peer, adapter journal and inspected counters. Retained memory, not disk. */
final class MapPeerFixture implements ExecutionTckFixture {
    private final ExecutionOffer offer = FixtureOperations.offer("synthetic-map-1", ExecutionOffer.PreparationEffect.RESOURCE_CREATING, Set.of(ExecutionOffer.Capability.VERIFIED_EVENTS));
    private final Map<ExecutionIdentity.ExecutionKey, ExecutionOperation> bindings = new HashMap<>();
    private final Map<ExecutionIdentity.ExecutionKey, SubmissionRequest> submissions = new HashMap<>();
    private final Map<ExecutionIdentity.ExecutionKey, String> resources = new HashMap<>();
    private final Map<ExecutionIdentity.ExecutionKey, ExecutionEvidence> peerFacts = new HashMap<>(), localFacts = new HashMap<>();
    private final Map<ExecutionIdentity.ExecutionKey, ExecutionEvidence> providerKeys = new HashMap<>();
    private final Map<ExecutionIdentity.ExecutionKey, EnumSet<Control>> controls = new HashMap<>();
    private final Map<String, Envelope> envelopes = new HashMap<>();
    private final Map<String, VerifiedEventReference> verified = new HashMap<>();
    private final Map<String, Envelope> verifiedIds = new HashMap<>();
    private long sends, acceptances, resourceCreates;
    private Ports ports;
    MapPeerFixture() { restart(); }
    public String fixtureId() { return "independent-map-peer-1"; }
    public ExecutionOffer offer() { return offer; }
    public ExecutionBinding binding() { return FixtureOperations.BINDING; }
    public ExecutionOperation operation(String name) { return FixtureOperations.operation(offer, binding(), name); }
    public Ports ports() { return ports; }
    public void restart() { ports = portsForAuthenticatedScope("fixture-tenant", "merchant-demo", "fixture-run"); }
    public Ports portsForAuthenticatedScope(String tenant, String merchant, String run) {
        var adapter = new Adapter(tenant, merchant, run); return new Ports(adapter, adapter, adapter, Optional.of(adapter), Optional.of(adapter), Optional.empty());
    }
    public RestartModel restartModel() { return RestartModel.IN_PROCESS_RECONSTRUCTION; }
    public Instant now() { return FixtureOperations.NOW; }
    public synchronized Inspection inspect() { return new Inspection(sends, acceptances, resourceCreates, providerKeys.size(),
            submissions.values().stream().allMatch(s -> bindings.get(s.operation().identity().key()).equals(s.operation()))); }
    public synchronized void control(Control c, ExecutionOperation op) {
        if (c == EXPIRE_PROVIDER_KEYS) providerKeys.remove(op.identity().key());
        var configured = controls.computeIfAbsent(op.identity().key(), k -> EnumSet.noneOf(Control.class));
        if (c == LOOKUP_UNAVAILABLE) configured.remove(LOOKUP_ABSENT);
        configured.add(c);
    }
    public synchronized Optional<String> observedPreparedResource(ExecutionOperation op) { return Optional.ofNullable(resources.get(op.identity().key())); }
    private boolean has(Control c, ExecutionOperation op) { return controls.getOrDefault(op.identity().key(), EnumSet.noneOf(Control.class)).contains(c); }
    private boolean take(Control c, ExecutionOperation op) { return controls.getOrDefault(op.identity().key(), EnumSet.noneOf(Control.class)).remove(c); }
    private record Envelope(String id, ExecutionOperation op, String account, boolean authentic, ExecutionEvidence.State state) { }
    public synchronized EventVerificationRequest event(ExecutionOperation op, EventKind kind) {
        String ref = "envelope-" + kind.name().toLowerCase(Locale.ROOT) + "-" + op.identity().operationId();
        String eventId = kind == EventKind.CONFLICTING_DUPLICATE ? "event-valid-" + op.identity().operationId() : "event-" + kind.name().toLowerCase(Locale.ROOT) + "-" + op.identity().operationId();
        var eventOp = kind == EventKind.UNBOUND ? operation("unbound") : op;
        envelopes.put(ref, new Envelope(eventId, eventOp, kind == EventKind.WRONG_ACCOUNT ? "other-account" : binding().accountRef(),
                kind != EventKind.FORGED && kind != EventKind.ALTERED,
                kind == EventKind.WEAK_PENDING || kind == EventKind.CONFLICTING_DUPLICATE ? ExecutionEvidence.State.PENDING : ExecutionEvidence.State.CAPTURED));
        return new EventVerificationRequest(op, ref);
    }
    private final class Adapter implements ExecutionPreparer, FinancialSubmitter, ExecutionReconciler, ProviderEventVerifier, ProviderEventNormalizer {
        private final String tenant, merchant, run;
        Adapter(String tenant, String merchant, String run) { this.tenant = tenant; this.merchant = merchant; this.run = run; }
        private void bind(ExecutionOperation op) {
            var id = op.identity();
            if (!tenant.equals(id.tenantId()) || !merchant.equals(id.merchantId()) || !run.equals(id.runId()))
                throw new ExecutionFailure(ExecutionFailure.Code.INVALID_SELECTION, ExecutionFailure.Certainty.NOT_STARTED);
            op.selection().validate(offer, binding());
            var prior = bindings.putIfAbsent(op.identity().key(), op);
            if (prior != null && !prior.equals(op)) throw FixtureOperations.conflict();
        }
        public PreparationResult prepare(ExecutionOperation op) {
            synchronized (MapPeerFixture.this) {
                bind(op); var key = op.identity().key();
                if (!resources.containsKey(key)) { resources.put(key, "prepared-" + op.fingerprint()); resourceCreates++; }
                if (take(PREPARE_RESPONSE_LOSS, op)) return new PreparationResult(op, PreparationResult.Status.UNKNOWN,
                        ExecutionOffer.PreparationEffect.RESOURCE_CREATING, Optional.empty(), Optional.empty());
                boolean action = has(ACTION_REQUIRED, op);
                return new PreparationResult(op, action ? PreparationResult.Status.ACTION_REQUIRED : PreparationResult.Status.READY,
                        ExecutionOffer.PreparationEffect.RESOURCE_CREATING, Optional.of(resources.get(key)), action ? Optional.of(FixtureOperations.action(op)) : Optional.empty());
            }
        }
        public ExecutionEvidence submit(SubmissionRequest request) {
            synchronized (MapPeerFixture.this) {
                var op = request.operation(); bind(op); var key = op.identity().key();
                if (!request.preparedResourceRef().equals(Optional.ofNullable(resources.get(key)))) throw FixtureOperations.conflict();
                var old = submissions.putIfAbsent(key, request);
                if (old != null && !old.equals(request)) throw FixtureOperations.conflict();
                if (old != null) return reconcile(op);
                try {
                    if (has(UNSAFE_EXCEPTION, op)) throw new IllegalStateException("CANARY_PRIVATE_CREDENTIAL_PAYLOAD");
                    sends++;
                    if (providerKeys.containsKey(key)) return providerKeys.get(key);
                    var state = has(REFUSED, op) ? ExecutionEvidence.State.DEFINITIVELY_REFUSED : has(PENDING, op) ? ExecutionEvidence.State.PENDING
                            : op.purpose() == OperationPurpose.AUTHORIZE ? ExecutionEvidence.State.AUTHORIZED : ExecutionEvidence.State.CAPTURED;
                    if (state != ExecutionEvidence.State.DEFINITIVELY_REFUSED) acceptances++;
                    var fact = FixtureOperations.evidence(op, "peer-" + op.fingerprint(), ExecutionEvidence.Source.SUBMIT, state);
                    peerFacts.put(key, fact);
                    providerKeys.put(key, fact);
                    if (take(SUBMIT_RESPONSE_LOSS, op)) throw new IllegalStateException("CANARY_PRIVATE_RESPONSE");
                    localFacts.put(key, fact); return fact;
                } catch (RuntimeException unsafe) { var unknown = FixtureOperations.unknown(op); localFacts.put(key, unknown); return unknown; }
            }
        }
        public ExecutionEvidence reconcile(ExecutionOperation op) {
            synchronized (MapPeerFixture.this) {
                bind(op); var known = localFacts.get(op.identity().key());
                if (known != null && known.terminalForSubmission()) return known;
                if (has(LOOKUP_ABSENT, op) || has(LOOKUP_UNAVAILABLE, op)) return FixtureOperations.unknown(op);
                var peer = peerFacts.get(op.identity().key());
                if (peer == null) return FixtureOperations.unknown(op);
                localFacts.put(op.identity().key(), peer); return peer;
            }
        }
        public VerifiedEventReference verify(EventVerificationRequest request) {
            synchronized (MapPeerFixture.this) {
                bind(request.operation()); var e = envelopes.get(request.eventEnvelopeRef());
                if (e == null || !e.authentic || !e.account.equals(binding().accountRef()) || !e.op.equals(request.operation())) invalid();
                var prior = verifiedIds.putIfAbsent(e.id, e);
                if (prior != null && !prior.equals(e)) throw new ExecutionFailure(ExecutionFailure.Code.EVIDENCE_CONFLICT, ExecutionFailure.Certainty.NOT_STARTED);
                var value = new VerifiedEventReference("verified-" + request.eventEnvelopeRef(), e.id, e.op.fingerprint(), e.op.binding().digest());
                verified.put(value.verifiedEventRef(), value); return value;
            }
        }
        public ExecutionEvidence normalize(ExecutionOperation op, VerifiedEventReference event) {
            synchronized (MapPeerFixture.this) {
                bind(op); if (!event.boundTo(op) || !event.equals(verified.get(event.verifiedEventRef()))) invalid();
                var e = verifiedIds.get(event.eventId()); if (e == null || !e.op.equals(op)) invalid();
                return FixtureOperations.evidence(op, e.id, ExecutionEvidence.Source.VERIFIED_EVENT, e.state);
            }
        }
        private void invalid() { throw new ExecutionFailure(ExecutionFailure.Code.INVALID_EVENT, ExecutionFailure.Certainty.NOT_STARTED); }
    }
}
