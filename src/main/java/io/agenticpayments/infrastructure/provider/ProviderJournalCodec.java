package io.agenticpayments.infrastructure.provider;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.agenticpayments.sdk.*;
import io.agenticpayments.sdk.execution.v1.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/** Explicit bounded storage format; Optional/Instant modules and default record mapping are unused. */
public final class ProviderJournalCodec {
    public static final String VERSION = "provider-compatibility-json-1";
    private final ObjectMapper json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public ProviderJournalCodec() { }
    public String encodeOperation(ExecutionOperation value) { return encode("operation", operation(value), 65536); }
    public ExecutionOperation decodeOperation(String value) { return decode(value, "operation", 65536, this::operation); }
    public String encodeLegacy(ChargeOperation value) { return encode("legacy", legacy(value), 8192); }
    public ChargeOperation decodeLegacy(String value) { return decode(value, "legacy", 8192, this::legacy); }
    public String encodeSubmission(SubmissionRequest value) {
        var n = object(); n.set("operation", operation(value.operation())); optional(n, "preparedResourceRef", value.preparedResourceRef());
        return encode("submission", n, 65536);
    }
    public SubmissionRequest decodeSubmission(String value) { return decode(value, "submission", 65536, n -> {
        fields(n, "operation", "preparedResourceRef"); return new SubmissionRequest(operation(n.get("operation")), optional(n, "preparedResourceRef"));
    }); }
    public String encodeEvidence(ExecutionEvidence value) {
        var n = object(); n.set("operation", operation(value.operation())); n.put("evidenceId", value.evidenceId());
        optional(n, "providerResourceRef", value.providerResourceRef()); n.put("source", value.source().name());
        n.put("observedAt", value.observedAt().toString()); n.put("state", value.state().name());
        if (value.action().isPresent()) n.set("action", action(value.action().orElseThrow())); else n.putNull("action");
        return encode("evidence", n, 131072);
    }
    public ExecutionEvidence decodeEvidence(String value) { return decode(value, "evidence", 131072, n -> {
        fields(n, "operation", "evidenceId", "providerResourceRef", "source", "observedAt", "state", "action");
        return new ExecutionEvidence(operation(n.get("operation")), text(n, "evidenceId"), optional(n, "providerResourceRef"),
                enumeration(n, "source", ExecutionEvidence.Source.class), instant(n, "observedAt"), enumeration(n, "state", ExecutionEvidence.State.class),
                n.get("action").isNull() ? Optional.empty() : Optional.of(action(n.get("action"))));
    }); }
    public String evidenceDigest(ExecutionEvidence value) { return digest(encodeEvidence(value)); }
    static String digest(String value) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw invalid(); }
    }
    private String encode(String kind, JsonNode value, int bound) {
        try {
            var root = object(); root.put("codecVersion", VERSION); root.put("kind", kind); root.set("value", value);
            String encoded = json.writeValueAsString(root); bounded(encoded, bound); return encoded;
        } catch (Exception unsafe) { throw invalid(); }
    }
    private <T> T decode(String encoded, String kind, int bound, Function<JsonNode, T> decoder) {
        try {
            bounded(encoded, bound); var root = json.readTree(encoded); fields(root, "codecVersion", "kind", "value");
            require(VERSION.equals(text(root, "codecVersion")) && kind.equals(text(root, "kind")));
            return decoder.apply(root.get("value"));
        } catch (Exception unsafe) { throw invalid(); }
    }
    private ObjectNode operation(ExecutionOperation op) {
        var n = object(); var i = object(); var id = op.identity();
        i.put("tenantId", id.tenantId()); i.put("merchantId", id.merchantId()); i.put("runId", id.runId());
        i.put("intentId", id.intentId()); i.put("attemptId", id.attemptId()); i.put("operationId", id.operationId()); i.put("callerIdempotencyKey", id.callerIdempotencyKey());
        n.set("identity", i); n.set("binding", binding(op.binding())); n.set("selection", selection(op.selection()));
        var money = object(); money.put("currency", op.money().currency()); money.put("exponent", op.money().exponent()); money.put("minorUnits", op.money().minorUnits());
        n.set("money", money); n.put("purpose", op.purpose().name()); n.put("fingerprint", op.fingerprint()); return n;
    }
    private ExecutionOperation operation(JsonNode n) {
        fields(n, "identity", "binding", "selection", "money", "purpose", "fingerprint"); var i = n.get("identity");
        fields(i, "tenantId", "merchantId", "runId", "intentId", "attemptId", "operationId", "callerIdempotencyKey");
        var id = new ExecutionIdentity(text(i, "tenantId"), text(i, "merchantId"), text(i, "runId"), text(i, "intentId"), text(i, "attemptId"), text(i, "operationId"), text(i, "callerIdempotencyKey"));
        var m = n.get("money"); fields(m, "currency", "exponent", "minorUnits");
        return new ExecutionOperation(id, binding(n.get("binding")), selection(n.get("selection")),
                new ExactMoney(text(m, "currency"), integer(m, "exponent"), number(m, "minorUnits")), enumeration(n, "purpose", OperationPurpose.class), text(n, "fingerprint"));
    }
    private ObjectNode binding(ExecutionBinding b) {
        var n = object(); n.put("providerId", b.providerId()); n.put("accountRef", b.accountRef()); n.put("region", b.region());
        n.put("configurationRef", b.configurationRef()); n.put("configurationVersion", b.configurationVersion());
        n.put("credentialRef", b.credentialRef()); n.put("credentialVersion", b.credentialVersion()); n.put("executionOwnerId", b.executionOwnerId()); return n;
    }
    private ExecutionBinding binding(JsonNode n) {
        fields(n, "providerId", "accountRef", "region", "configurationRef", "configurationVersion", "credentialRef", "credentialVersion", "executionOwnerId");
        return new ExecutionBinding(text(n, "providerId"), text(n, "accountRef"), text(n, "region"), text(n, "configurationRef"), text(n, "configurationVersion"), text(n, "credentialRef"), text(n, "credentialVersion"), text(n, "executionOwnerId"));
    }
    private ObjectNode selection(CapabilitySelection s) {
        var n = object(); n.put("contractVersion", s.contractVersion()); n.put("profileVersion", s.profileVersion());
        n.put("implementationRevision", s.implementationRevision()); n.put("offerDigest", s.offerDigest()); n.set("combination", combination(s.combination())); n.set("binding", binding(s.binding())); return n;
    }
    private CapabilitySelection selection(JsonNode n) {
        fields(n, "contractVersion", "profileVersion", "implementationRevision", "offerDigest", "combination", "binding");
        return new CapabilitySelection(text(n, "contractVersion"), text(n, "profileVersion"), text(n, "implementationRevision"), text(n, "offerDigest"), combination(n.get("combination")), binding(n.get("binding")));
    }
    private ObjectNode combination(ExecutionOffer.Combination c) {
        var n = object(); n.put("methodId", c.methodId()); n.put("credentialFormatId", c.credentialFormatId());
        n.set("actions", names(c.actions())); n.set("purposes", names(c.purposes())); n.put("preparationEffect", c.preparationEffect().name()); n.set("capabilities", names(c.capabilities()));
        var limits = json.createArrayNode(); c.limits().forEach(limit -> {
            var l = object(); l.put("currency", limit.currency()); l.put("exponent", limit.exponent()); l.put("minimum", limit.minimum()); l.put("maximum", limit.maximum()); limits.add(l);
        }); n.set("limits", limits); return n;
    }
    private ExecutionOffer.Combination combination(JsonNode n) {
        fields(n, "methodId", "credentialFormatId", "actions", "purposes", "preparationEffect", "capabilities", "limits");
        var limits = new ArrayList<ExecutionOffer.CurrencyLimit>(); array(n.get("limits"));
        for (var l : n.get("limits")) { fields(l, "currency", "exponent", "minimum", "maximum"); limits.add(new ExecutionOffer.CurrencyLimit(text(l, "currency"), integer(l, "exponent"), number(l, "minimum"), number(l, "maximum"))); }
        return new ExecutionOffer.Combination(text(n, "methodId"), text(n, "credentialFormatId"), enums(n.get("actions"), ExecutionOffer.ActionType.class), enums(n.get("purposes"), OperationPurpose.class),
                enumeration(n, "preparationEffect", ExecutionOffer.PreparationEffect.class), enums(n.get("capabilities"), ExecutionOffer.Capability.class), limits);
    }
    private ObjectNode legacy(ChargeOperation op) {
        var n = object(); var i = object(); var id = op.identity(); var s = object();
        s.put("tenantId", id.scope().tenantId()); s.put("runId", id.scope().runId().toString()); s.put("merchantId", id.scope().merchantId()); i.set("scope", s);
        i.put("intentId", id.intentId().toString()); i.put("attemptId", id.attemptId().toString()); i.put("operationId", id.operationId().toString());
        i.put("adapterKey", id.adapterKey().toString()); i.put("requestDigest", id.requestDigest()); n.set("identity", i);
        var m = object(); m.put("currency", op.money().currency()); m.put("minorUnits", op.money().minorUnits()); n.set("money", m); n.put("scenarioId", op.scenarioId()); return n;
    }
    private ChargeOperation legacy(JsonNode n) {
        fields(n, "identity", "money", "scenarioId"); var i = n.get("identity"); fields(i, "scope", "intentId", "attemptId", "operationId", "adapterKey", "requestDigest");
        var s = i.get("scope"); fields(s, "tenantId", "runId", "merchantId"); var m = n.get("money"); fields(m, "currency", "minorUnits");
        return new ChargeOperation(new OperationIdentity(new ExecutionScope(text(s, "tenantId"), uuid(s, "runId"), text(s, "merchantId")), uuid(i, "intentId"), uuid(i, "attemptId"), uuid(i, "operationId"), uuid(i, "adapterKey"), text(i, "requestDigest")),
                new Money(text(m, "currency"), number(m, "minorUnits")), text(n, "scenarioId"));
    }
    private ObjectNode action(TrustedActionDescriptor a) {
        var n = object(); n.put("type", a.type().name()); n.put("actionRef", a.actionRef()); n.put("operationFingerprint", a.operationFingerprint()); n.put("bindingDigest", a.bindingDigest()); n.put("expiresAt", a.expiresAt().toString()); return n;
    }
    private TrustedActionDescriptor action(JsonNode n) {
        fields(n, "type", "actionRef", "operationFingerprint", "bindingDigest", "expiresAt");
        return new TrustedActionDescriptor(enumeration(n, "type", ExecutionOffer.ActionType.class), text(n, "actionRef"), text(n, "operationFingerprint"), text(n, "bindingDigest"), instant(n, "expiresAt"));
    }
    private ObjectNode object() { return json.createObjectNode(); }
    private ArrayNode names(Set<? extends Enum<?>> values) { var n = json.createArrayNode(); values.stream().map(Enum::name).sorted().forEach(n::add); return n; }
    private static <E extends Enum<E>> Set<E> enums(JsonNode n, Class<E> type) {
        array(n); var result = EnumSet.noneOf(type); for (var entry : n) { require(entry.isTextual()); require(result.add(Enum.valueOf(type, entry.textValue()))); } return result;
    }
    private static <E extends Enum<E>> E enumeration(JsonNode n, String field, Class<E> type) { return Enum.valueOf(type, text(n, field)); }
    private static void array(JsonNode n) { require(n != null && n.isArray() && n.size() <= 32); }
    private static void fields(JsonNode n, String... expected) {
        require(n != null && n.isObject() && n.size() == expected.length); for (String key : expected) require(n.has(key));
    }
    private static String text(JsonNode n, String key) { var value = n.get(key); require(value != null && value.isTextual()); return value.textValue(); }
    private static long number(JsonNode n, String key) { var v = n.get(key); require(v != null && v.isIntegralNumber() && v.canConvertToLong()); return v.longValue(); }
    private static int integer(JsonNode n, String key) { long v = number(n, key); require(v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE); return (int)v; }
    private static UUID uuid(JsonNode n, String key) { String s = text(n, key); UUID value = UUID.fromString(s); require(value.toString().equals(s)); return value; }
    private static Instant instant(JsonNode n, String key) { String s = text(n, key); var value = Instant.parse(s); require(value.toString().equals(s)); return value; }
    private static void optional(ObjectNode n, String key, Optional<String> value) { if (value.isPresent()) n.put(key, value.orElseThrow()); else n.putNull(key); }
    private static Optional<String> optional(JsonNode n, String key) { require(n.has(key)); return n.get(key).isNull() ? Optional.empty() : Optional.of(text(n, key)); }
    private static void bounded(String value, int bound) { require(value != null && value.getBytes(StandardCharsets.UTF_8).length <= bound); }
    private static void require(boolean valid) { if (!valid) throw invalid(); }
    private static ExecutionFailure invalid() { return new ExecutionFailure(ExecutionFailure.Code.CONTRACT_VIOLATION, ExecutionFailure.Certainty.MAY_HAVE_OCCURRED); }
}
