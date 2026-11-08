package io.agenticpayments.application;

import io.agenticpayments.application.CheckoutConfigurationResolver.*;
import io.agenticpayments.application.CheckoutLifecycleTransactions.*;
import io.agenticpayments.checkout.CheckoutCommandTransport;
import io.agenticpayments.checkout.CheckoutSessions;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import static io.agenticpayments.checkout.CheckoutSessions.*;

/** Commerce orchestration. All external I/O occurs between committed atomic units of work. */
public final class CheckoutLifecycleService implements CheckoutLifecycle {
    private static final Set<String> STATUSES = Set.of("incomplete", "not_ready_for_payment", "requires_escalation",
            "authentication_required", "ready_for_payment", "pending_approval", "complete_in_progress", "completed",
            "canceled", "in_progress", "expired");
    private final CheckoutLifecycleTransactions transactions;
    private final CheckoutConfigurationResolver configurations;
    public CheckoutLifecycleService(CheckoutLifecycleTransactions transactions, CheckoutConfigurationResolver configurations) {
        this.transactions = Objects.requireNonNull(transactions); this.configurations = Objects.requireNonNull(configurations);
    }
    private record Plan(SafeView replay, Purchase purchase, ConfigurationIdentity configuration) {}

    @Override public SafeView stage(PaymentService.Session s, UUID purchaseId, String key, StageCommand input) {
        role(s, "intent:write"); require(purchaseId != null && input != null, ApplicationFailure.INVALID_REQUEST);
        merchant(s, input.merchantId());
        try { new Command(key, null); } catch (RuntimeException error) { fail(ApplicationFailure.INVALID_REQUEST); }
        String fingerprint = fingerprint(input);
        Plan plan = transactions.run(s, tx -> {
            StoredCommand prior = tx.replay(purchaseId, key);
            Purchase p = tx.purchase(purchaseId);
            if (p != null) merchant(s, p.configuration().binding().merchantId());
            if (prior != null) {
                require(prior.fingerprint().equals(fingerprint), ApplicationFailure.IDEMPOTENCY_CONFLICT);
                return new Plan(view(s, p, prior), null, null);
            }
            validateStage(tx, p, input);
            ConfigurationIdentity identity = tx.currentConfiguration(input.merchantId(), input.configurationId());
            require(identity != null, ApplicationFailure.CHECKOUT_CONFIGURATION_UNAVAILABLE);
            if (p != null) require(identity.equals(p.configuration()), ApplicationFailure.CONFIGURATION_CHANGED);
            return new Plan(null, p, identity);
        });
        if (plan.replay() != null) return plan.replay();
        var transport = resolve(plan.configuration(), input.operation());
        String merchantKey = input.operation() == Operation.RETRIEVE ? null : UUID.randomUUID().toString();
        var request = new CheckoutCommandTransport.Request(plan.configuration().binding(), input.operation(),
                plan.purchase() == null ? null : plan.purchase().sessionId(), input.currency(),
                input.itemIds().stream().map(ItemReference::new).toList(), merchantKey, UUID.randomUUID().toString());
        CheckoutCommandTransport.Prepared prepared;
        try {
            prepared = transport.prepare(request);
            require(prepared != null && request.equals(prepared.request())
                    && plan.configuration().transportProfile().equals(prepared.profile())
                    && plan.configuration().encodingVersion().equals(prepared.encodingVersion()), ApplicationFailure.INVALID_CHECKOUT_PREPARATION);
        } catch (ApplicationException error) { throw error; }
        catch (RuntimeException error) { throw new PaymentException(ApplicationFailure.INVALID_CHECKOUT_PREPARATION); }
        var immutablePrepared = prepared;
        return transactions.run(s, tx -> {
            StoredCommand prior = tx.replay(purchaseId, key);
            Purchase p = tx.purchase(purchaseId);
            if (p != null) merchant(s, p.configuration().binding().merchantId());
            if (prior != null) {
                require(prior.fingerprint().equals(fingerprint), ApplicationFailure.IDEMPOTENCY_CONFLICT);
                return view(s, p, prior);
            }
            validateStage(tx, p, input);
            require(plan.configuration().equals(tx.currentConfiguration(input.merchantId(), input.configurationId())),
                    ApplicationFailure.CONFIGURATION_CHANGED);
            var staged = tx.stage(purchaseId, key, fingerprint, input, plan.configuration(), immutablePrepared);
            return view(s, tx.purchase(purchaseId), staged);
        });
    }
    @Override public SafeView status(PaymentService.Session s, UUID purchaseId) {
        role(s, "intent:read"); require(purchaseId != null, ApplicationFailure.INVALID_REQUEST);
        return transactions.run(s, tx -> {
            Purchase p = owned(s, tx, purchaseId);
            return view(s, p, tx.command(purchaseId, p.currentCommandId()));
        });
    }
    @Override public SafeView dispatch(PaymentService.Session s, UUID purchaseId, UUID commandId) {
        role(s, "intent:write"); require(purchaseId != null && commandId != null, ApplicationFailure.INVALID_REQUEST);
        StoredCommand command = transactions.run(s, tx -> {
            owned(s, tx, purchaseId);
            StoredCommand c = tx.command(purchaseId, commandId); require(c != null, ApplicationFailure.NOT_FOUND); return c;
        });
        if (command.state() != CommandState.STAGED) return commandView(s, purchaseId, commandId);
        CheckoutCommandTransport transport = resolve(command.configuration(), command.input().operation());
        Claim claim = transactions.run(s, tx -> { owned(s, tx, purchaseId); return tx.claim(purchaseId, commandId, UUID.randomUUID()); });
        if (claim == null) return commandView(s, purchaseId, commandId);
        Result result;
        try { result = validate(transport.send(claim.command().prepared()), claim.command(), transport); }
        catch (RuntimeException error) { result = uncertain(Code.TRANSPORT_UNCERTAIN, 0); }
        boolean resolverCurrent;
        try { resolve(command.configuration(), command.input().operation()); resolverCurrent = true; }
        catch (RuntimeException error) { resolverCurrent = false; }
        Result retained = result; boolean allowProjection = resolverCurrent;
        return transactions.run(s, tx -> {
            owned(s, tx, purchaseId);
            StoredCommand recorded = tx.record(claim, retained, allowProjection);
            return view(s, tx.purchase(purchaseId), recorded);
        });
    }
    @Override public SafeView recoverClaim(PaymentService.Session s, UUID purchaseId, UUID commandId) {
        role(s, "intent:write"); require(purchaseId != null && commandId != null, ApplicationFailure.INVALID_REQUEST);
        return transactions.run(s, tx -> {
            owned(s, tx, purchaseId);
            var command = tx.recoverClaim(purchaseId, commandId);
            return view(s, tx.purchase(purchaseId), command);
        });
    }
    private SafeView commandView(PaymentService.Session s, UUID purchaseId, UUID commandId) {
        return transactions.run(s, tx -> { Purchase p = owned(s, tx, purchaseId); return view(s, p, tx.command(purchaseId, commandId)); });
    }
    private CheckoutCommandTransport resolve(ConfigurationIdentity identity, Operation operation) {
        try {
            Resolved resolved = configurations.resolve(identity);
            require(resolved != null && identity.equals(resolved.identity()), ApplicationFailure.CHECKOUT_CONFIGURATION_UNAVAILABLE);
            resolved.transport().describe().require(identity.transportProfile(), Set.of(operation));
            require(identity.encodingVersion().equals(resolved.transport().encodingVersion()), ApplicationFailure.UNSUPPORTED_CAPABILITY);
            return resolved.transport();
        } catch (ApplicationException error) { throw error; }
        catch (RuntimeException error) { throw new PaymentException(ApplicationFailure.CHECKOUT_CONFIGURATION_UNAVAILABLE); }
    }
    private static Result validate(Result result, StoredCommand command, CheckoutCommandTransport transport) {
        if (result == null) return uncertain(Code.INVALID_RESPONSE, 0);
        if (result.code() != Code.OK) {
            if (result.document() != null || result.observation() == Observation.OBSERVED
                    || result.observation() == Observation.NOT_SENT && result.httpStatus() != 0)
                return uncertain(Code.INVALID_RESPONSE, result.httpStatus());
            return result;
        }
        var document = result.document(); var request = command.prepared().request();
        if (result.observation() != Observation.OBSERVED || result.httpStatus() < 200 || result.httpStatus() > 299
                || document == null || !request.binding().equals(document.session().binding())
                || !request.currency().equals(document.session().currency())
                || request.sessionId() != null && !request.sessionId().equals(document.session().id())
                || !STATUSES.contains(document.status())
                || document.observedProtocolVersion() != null && !transport.observedProtocolVersions().contains(document.observedProtocolVersion()))
            return uncertain(Code.INVALID_RESPONSE, result.httpStatus());
        return result;
    }
    private static Result uncertain(Code code, int status) { return new Result(status, code, Observation.UNKNOWN, Metadata.absent(), null); }
    private static Purchase owned(PaymentService.Session s, Transaction tx, UUID id) {
        Purchase p = tx.purchase(id); require(p != null, ApplicationFailure.NOT_FOUND); merchant(s, p.configuration().binding().merchantId()); return p;
    }
    static void validateStage(Transaction tx, Purchase p, StageCommand input) {
        if (p == null) { require(input.operation() == Operation.CREATE && input.expectedRevision() == 0, ApplicationFailure.CHECKOUT_REVISION_CONFLICT); return; }
        require(p.revision() == input.expectedRevision(), ApplicationFailure.CHECKOUT_REVISION_CONFLICT);
        require(input.operation() != Operation.CREATE, ApplicationFailure.CHECKOUT_ALREADY_EXISTS);
        require(p.configuration().binding().merchantId().equals(input.merchantId())
                && p.configuration().binding().configurationId().equals(input.configurationId())
                && p.currency().equals(input.currency()), ApplicationFailure.CHECKOUT_BINDING_CONFLICT);
        require(p.sessionId() != null, ApplicationFailure.TRUSTED_RESOLUTION_REQUIRED);
        require(!p.mutationBlocked() || input.operation() == Operation.RETRIEVE, ApplicationFailure.CHECKOUT_MUTATION_BLOCKED);
        StoredCommand current = tx.command(p.id(), p.currentCommandId());
        require(current.state() != CommandState.STAGED && current.state() != CommandState.CLAIMED, ApplicationFailure.CHECKOUT_COMMAND_IN_FLIGHT);
    }
    private static SafeView view(PaymentService.Session s, Purchase p, StoredCommand c) {
        require(p != null && c != null, ApplicationFailure.NOT_FOUND);
        Recovery recovery = c.state() == CommandState.STAGED ? Recovery.DISPATCH_STAGED
                : c.state() == CommandState.CLAIMED ? Recovery.IN_FLIGHT
                : p.mutationBlocked() || c.state() == CommandState.UNKNOWN || c.state() == CommandState.STALE
                ? p.sessionId() == null ? Recovery.TRUSTED_RESOLUTION_REQUIRED : Recovery.RETRIEVE_ONLY : Recovery.NONE;
        return new SafeView(CONTRACT_VERSION, PROFILE, s.runId(), p.id(), c.id(), p.revision(), c.input().operation(),
                c.state(), c.outcomeCode(), c.observation(), c.httpStatus(), p.sessionId() != null,
                p.remoteStatus(), c.responseReference(), p.mutationBlocked(), recovery);
    }
    static String fingerprint(StageCommand input) {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            for (String value : new String[]{CONTRACT_VERSION,input.operation().name(),Integer.toString(input.expectedRevision()),
                    input.merchantId(),input.configurationId(),input.currency()}) write(out, value);
            out.writeInt(input.itemIds().size()); for (String item : input.itemIds()) write(out, item);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (Exception error) { throw new IllegalStateException("Checkout fingerprint unavailable"); }
    }
    private static void write(DataOutputStream out, String value) throws java.io.IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
    }
    private static void role(PaymentService.Session s, String role) { require(s != null && s.roles().contains(role), ApplicationFailure.FORBIDDEN); }
    private static void merchant(PaymentService.Session s, String merchant) { require(s.allowedMerchants().contains(merchant), ApplicationFailure.MERCHANT_NOT_ALLOWED); }
    private static void require(boolean valid, ApplicationFailure failure) { if (!valid) fail(failure); }
    private static void fail(ApplicationFailure failure) { throw new PaymentException(failure); }
}
