package io.agenticpayments.sdk.execution.v1;

import io.agenticpayments.sdk.*;
import io.agenticpayments.sdk.execution.v1.compat.*;
import io.agenticpayments.sdk.execution.v1.tck.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Synthetic original-SDK delegate and independent retained-memory atomic journal, not production. */
final class CompatibilityFixture implements ExecutionTckFixture {
    private final ExecutionBinding binding = new ExecutionBinding("simulator", "account-ref", "eu-test", "config-ref", "1", "credential-ref", "1", "platform-owner");
    private final Journal journal = new Journal();
    private final LegacyPeer peer = new LegacyPeer();
    private final Map<ExecutionIdentity.ExecutionKey, EnumSet<Control>> controls = new HashMap<>();
    private record Pause(CountDownLatch paused, CountDownLatch resume) { }
    private final Map<ExecutionIdentity.ExecutionKey, Pause> pauses = new ConcurrentHashMap<>();
    private final boolean unsafeDelayedSend;
    private Ports ports;
    CompatibilityFixture() { this(false); }
    CompatibilityFixture(boolean unsafeDelayedSend) { this.unsafeDelayedSend = unsafeDelayedSend; restart(); }
    public String fixtureId() { return "local-fake-compatibility-1"; }
    public ExecutionOffer offer() { return LocalFakeExecutionAdapter.offer(); }
    public ExecutionBinding binding() { return binding; }
    public ExecutionOperation operation(String name) { return FixtureOperations.operation(offer(), binding, name); }
    public Ports ports() { return ports; }
    public void restart() {
        ports = portsForAuthenticatedScope("fixture-tenant", "merchant-demo", "fixture-run");
    }
    public Ports portsForAuthenticatedScope(String tenant, String merchant, String run) {
        var bridge = new LocalFakeExecutionAdapter(peer, journal, binding, tenant, merchant, run,
                now(), now().plusSeconds(600), op -> "success");
        return new Ports(bridge, bridge, bridge, Optional.empty(), Optional.empty(), Optional.of(bridge));
    }
    public RestartModel restartModel() { return RestartModel.IN_PROCESS_RECONSTRUCTION; }
    public Instant now() { return FixtureOperations.NOW; }
    public synchronized Inspection inspect() { return new Inspection(peer.sends, peer.acceptances, 0, peer.providerKeys.size(),
            journal.rows.values().stream().allMatch(row -> row.request == null || row.request.operation().equals(row.op))); }
    public synchronized void control(Control c, ExecutionOperation op) {
        if (c == Control.EXPIRE_PROVIDER_KEYS) peer.providerKeys.clear();
        if (c == Control.PAUSE_BEFORE_FINANCIAL_SEND) pauses.put(op.identity().key(), new Pause(new CountDownLatch(1), new CountDownLatch(1)));
        var configured = controls.computeIfAbsent(op.identity().key(), k -> EnumSet.noneOf(Control.class));
        if (c == Control.LOOKUP_UNAVAILABLE) configured.remove(Control.LOOKUP_ABSENT);
        configured.add(c);
    }
    public void awaitFinancialSendPaused(ExecutionOperation op) {
        var pause = Objects.requireNonNull(pauses.get(op.identity().key()));
        try { if (!pause.paused.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Pause evidence unavailable."); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Pause interrupted."); }
    }
    public void resumeFinancialSend(ExecutionOperation op) { Objects.requireNonNull(pauses.get(op.identity().key())).resume.countDown(); }
    public EventVerificationRequest event(ExecutionOperation operation, EventKind kind) { throw new UnsupportedOperationException("Events are undeclared."); }
    private boolean has(ExecutionOperation op, Control c) { return controls.getOrDefault(op.identity().key(), EnumSet.noneOf(Control.class)).contains(c); }
    private final class Journal implements CompatibilityBindingJournal {
        private final Map<ExecutionIdentity.ExecutionKey, Row> rows = new HashMap<>();
        private final class Row {
            final ExecutionOperation op; final ChargeOperation legacy;
            SubmissionRequest request; boolean claimed; ExecutionEvidence known;
            Row(ExecutionOperation op, ChargeOperation legacy) { this.op = op; this.legacy = legacy; }
        }
        public synchronized ChargeOperation bind(ExecutionOperation op, ChargeOperation candidate) {
            var row = rows.computeIfAbsent(op.identity().key(), k -> new Row(op, candidate));
            if (!row.op.equals(op) || !row.legacy.equals(candidate)) throw FixtureOperations.conflict();
            return row.legacy;
        }
        public synchronized DispatchClaim claim(SubmissionRequest request) {
            var row = rows.get(request.operation().identity().key());
            if (row == null || !row.op.equals(request.operation()) || (row.request != null && !row.request.equals(request))) throw FixtureOperations.conflict();
            row.request = request;
            if (row.known != null && row.known.terminalForSubmission()) return new DispatchClaim(false, Optional.of(row.known));
            boolean first = !row.claimed; row.claimed = true;
            return new DispatchClaim(first, first ? Optional.empty() : Optional.ofNullable(row.known));
        }
        public synchronized ExecutionEvidence record(ExecutionEvidence evidence) {
            var row = rows.get(evidence.operation().identity().key());
            if (row == null || !row.op.equals(evidence.operation())) throw FixtureOperations.conflict();
            if (row.known != null && row.known.terminalForSubmission()) {
                if (evidence.terminalForSubmission() && row.known.state() != evidence.state())
                    throw new ExecutionFailure(ExecutionFailure.Code.EVIDENCE_CONFLICT, ExecutionFailure.Certainty.MAY_HAVE_OCCURRED);
                return row.known;
            }
            row.known = evidence; return evidence;
        }
        public synchronized Optional<ExecutionEvidence> read(ExecutionOperation op) {
            var row = rows.get(op.identity().key());
            if (row == null) return Optional.empty(); if (!row.op.equals(op)) throw FixtureOperations.conflict();
            return Optional.ofNullable(row.known);
        }
        synchronized ExecutionOperation operation(OperationIdentity id) {
            return rows.values().stream().filter(row -> row.legacy.identity().equals(id)).findFirst().orElseThrow().op;
        }
    }
    private final class LegacyPeer implements PaymentAdapter {
        private final Map<UUID, Fact> journaled = new HashMap<>();
        private final Set<UUID> providerKeys = new HashSet<>();
        private long sends, acceptances;
        public CapabilityOffer capabilities() { return CapabilityOffer.localFake(); }
        public Preparation prepare(ChargeOperation op) {
            return new Preparation(has(journal.operation(op.identity()), Control.ACTION_REQUIRED) ? ActionType.SYNTHETIC_CHALLENGE : ActionType.NONE);
        }
        public Fact execute(ChargeOperation op) {
            var generic = journal.operation(op.identity()); var key = op.identity().adapterKey();
            var pause = pauses.get(generic.identity().key());
            if (pause != null) {
                pause.paused.countDown();
                try { if (!pause.resume.await(5, TimeUnit.SECONDS)) throw new AdapterUnavailableException(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AdapterUnavailableException(); }
            }
            synchronized (this) {
            if (has(generic, Control.UNSAFE_EXCEPTION)) throw new IllegalStateException("CANARY_PRIVATE_ORIGINAL_ADAPTER");
            boolean unsafeTransmission = pause != null && unsafeDelayedSend;
            if (unsafeTransmission) sends++; // Independent peer observed a forbidden send even if it refuses it.
            var existing = journaled.get(key); if (existing != null) return existing;
            if (!unsafeTransmission) sends++;
            providerKeys.add(key);
            var outcome = has(generic, Control.REFUSED) ? Outcome.NO_EFFECT : has(generic, Control.PENDING) ? Outcome.PENDING : Outcome.SUCCEEDED;
            if (outcome != Outcome.NO_EFFECT) acceptances++;
            var fact = new Fact(UUID.nameUUIDFromBytes(("fact-" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8)), op.identity(), op.money(), 1, outcome);
            journaled.put(key, fact);
            if (has(generic, Control.SUBMIT_RESPONSE_LOSS)) throw new AdapterUnavailableException();
            return fact;
            }
        }
        public synchronized LookupResult lookup(OperationIdentity id) {
            var generic = journal.operation(id);
            if (has(generic, Control.LOOKUP_ABSENT)) return LookupResult.notFound();
            if (has(generic, Control.LOOKUP_UNAVAILABLE)) return LookupResult.unavailable();
            return Optional.ofNullable(journaled.get(id.adapterKey())).map(LookupResult::found).orElseGet(LookupResult::notFound);
        }
        public synchronized Fact sealNoEffect(ChargeOperation op) {
            return journaled.computeIfAbsent(op.identity().adapterKey(), key -> new Fact(UUID.nameUUIDFromBytes(("seal-" + key)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)), op.identity(), op.money(), 1, Outcome.NO_EFFECT));
        }
    }
}
