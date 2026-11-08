package io.agenticpayments.infrastructure.provider;

import io.agenticpayments.sdk.ChargeOperation;
import io.agenticpayments.sdk.execution.v1.*;
import io.agenticpayments.sdk.execution.v1.compat.CompatibilityBindingJournal;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import static io.agenticpayments.sdk.execution.v1.ExecutionFailure.Code.*;
import static io.agenticpayments.sdk.execution.v1.ExecutionFailure.Certainty.*;

/** Short independent PostgreSQL transactions; no provider I/O or resettable ownership leases. */
public final class JdbcCompatibilityBindingJournal implements CompatibilityBindingJournal {
    private static final String SCOPE = "tenant_id=? AND merchant_id=? AND run_id=? AND caller_key=?";
    private final DataSource database;
    private final ProviderJournalConfiguration configuration;
    private final ProviderJournalCodec codec = new ProviderJournalCodec();
    public JdbcCompatibilityBindingJournal(DataSource database, ProviderJournalConfiguration configuration) {
        this.database = Objects.requireNonNull(database); this.configuration = Objects.requireNonNull(configuration);
    }
    @Override public ChargeOperation bind(ExecutionOperation operation, ChargeOperation candidate) {
        var expected = configuration.expectedLegacy(operation); Objects.requireNonNull(candidate);
        String encodedOperation = codec.encodeOperation(operation), encodedLegacy = codec.encodeLegacy(candidate);
        return transaction(NOT_STARTED, c -> {
            if (!candidate.equals(expected)) {
                var existing = query(c, "SELECT * FROM provider_compatibility_bindings WHERE " + SCOPE, key(operation));
                receipt(c, operation, existing, "BIND_IDENTITY", IDENTITY_CONFLICT, NOT_STARTED,
                        existing == null ? null : string(existing, "operation_fingerprint"), operation.fingerprint(), null);
                return Decision.failure(IDENTITY_CONFLICT, NOT_STARTED);
            }
            var legacyId = candidate.identity();
            write(c, "INSERT INTO provider_compatibility_bindings(tenant_id,merchant_id,run_id,caller_key,operation_id,operation_fingerprint,operation_json,legacy_tenant_id,legacy_run_id,legacy_adapter_key,legacy_json,codec_version,mapping_policy_version,scenario_policy_ref,scenario_policy_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                    operation.identity().tenantId(), operation.identity().merchantId(), operation.identity().runId(), operation.identity().callerIdempotencyKey(),
                    operation.identity().operationId(), operation.fingerprint(), encodedOperation, legacyId.scope().tenantId(), legacyId.scope().runId(), legacyId.adapterKey(), encodedLegacy,
                    ProviderJournalCodec.VERSION, configuration.mappingPolicyVersion(), configuration.scenarioPolicyReference(), configuration.scenarioPolicyVersion());
            var requested = query(c, "SELECT * FROM provider_compatibility_bindings WHERE " + SCOPE + " FOR UPDATE", key(operation));
            if (requested == null) {
                var existing = query(c, "SELECT * FROM provider_compatibility_bindings WHERE tenant_id=? AND merchant_id=? AND run_id=? AND operation_id=?",
                        operation.identity().tenantId(), operation.identity().merchantId(), operation.identity().runId(), operation.identity().operationId());
                String kind = "OPERATION_ALIAS";
                if (existing == null) {
                    existing = query(c, "SELECT * FROM provider_compatibility_bindings WHERE legacy_tenant_id=? AND legacy_run_id=? AND legacy_adapter_key=?", legacyId.scope().tenantId(), legacyId.scope().runId(), legacyId.adapterKey());
                    kind = "LEGACY_ALIAS";
                }
                receipt(c, operation, existing, kind, IDENTITY_CONFLICT, NOT_STARTED,
                        existing == null ? null : string(existing, "operation_fingerprint"), operation.fingerprint(), null);
                return Decision.failure(IDENTITY_CONFLICT, NOT_STARTED);
            }
            var loaded = load(c, operation, expected, requested);
            return loaded.failure != null ? Decision.failure(loaded.failure) : Decision.success(loaded.stored.legacy);
        });
    }
    @Override public DispatchClaim claim(SubmissionRequest request) {
        Objects.requireNonNull(request); var operation = request.operation(); var expected = configuration.expectedLegacy(operation);
        String encoded = codec.encodeSubmission(request);
        return transaction(MAY_HAVE_OCCURRED, c -> {
            var loaded = load(c, operation, expected, null);
            if (loaded.failure != null) return Decision.failure(loaded.failure);
            if (loaded.stored == null) return Decision.failure(IDENTITY_CONFLICT, NOT_STARTED);
            var stored = loaded.stored;
            if (stored.submission != null && !stored.submission.equals(request)) {
                receipt(c, operation, stored.row, "SUBMISSION_IDENTITY", IDENTITY_CONFLICT, NOT_STARTED,
                        stored.submission.fingerprint(), request.fingerprint(), null);
                return Decision.failure(IDENTITY_CONFLICT, NOT_STARTED);
            }
            boolean first = !flag(stored.row, "dispatch_claimed") && !flag(stored.row, "dispatch_blocked") && stored.projection == null;
            if (stored.submission == null) {
                write(c, "UPDATE provider_compatibility_bindings SET submission_fingerprint=?,submission_json=?,prepared_resource_present=false,prepared_resource_ref=NULL,dispatch_claimed=?,updated_at=current_timestamp WHERE " + SCOPE,
                        prefixed(operation, request.fingerprint(), encoded, first));
            } else if (first) {
                write(c, "UPDATE provider_compatibility_bindings SET dispatch_claimed=true,updated_at=current_timestamp WHERE " + SCOPE, key(operation));
            }
            return Decision.success(new DispatchClaim(first, Optional.ofNullable(stored.projection)));
        });
    }
    @Override public ExecutionEvidence record(ExecutionEvidence evidence) {
        Objects.requireNonNull(evidence); var operation = evidence.operation(); var expected = configuration.expectedLegacy(operation);
        String encoded = codec.encodeEvidence(evidence), digest = codec.evidenceDigest(evidence);
        return transaction(MAY_HAVE_OCCURRED, c -> {
            var loaded = load(c, operation, expected, null);
            if (loaded.failure != null) return Decision.failure(loaded.failure);
            if (loaded.stored == null) return Decision.failure(IDENTITY_CONFLICT, NOT_STARTED);
            var stored = loaded.stored;
            var existing = query(c, "SELECT * FROM provider_compatibility_evidence WHERE " + SCOPE + " AND evidence_id=?", suffixed(operation, evidence.evidenceId()));
            if (existing != null) {
                ExecutionEvidence accepted;
                try { accepted = decodeEvidence(existing, operation); }
                catch (RuntimeException corrupt) { return corrupt(c, operation, stored.row); }
                if (!accepted.equals(evidence)) return conflict(c, operation, stored, "EVIDENCE_ID", string(existing, "evidence_digest"), digest, encoded);
                return Decision.success(stored.projection == null ? accepted : stored.projection);
            }
            if (!Set.of(ExecutionEvidence.State.UNKNOWN, ExecutionEvidence.State.PENDING, ExecutionEvidence.State.CAPTURED, ExecutionEvidence.State.NO_EFFECT_FENCED).contains(evidence.state())) {
                receipt(c, operation, stored.row, "UNSUPPORTED_EVIDENCE", CONTRACT_VIOLATION, MAY_HAVE_OCCURRED, null, digest, encoded);
                quarantine(c, operation); return Decision.failure(CONTRACT_VIOLATION, MAY_HAVE_OCCURRED);
            }
            write(c, "INSERT INTO provider_compatibility_evidence(tenant_id,merchant_id,run_id,caller_key,evidence_id,codec_version,evidence_json,evidence_digest,state,source,observed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    suffixed(operation, evidence.evidenceId(), ProviderJournalCodec.VERSION, encoded, digest, evidence.state().name(), evidence.source().name(), evidence.observedAt().toString()));
            if (evidence.providerResourceRef().isPresent()) {
                String resource = evidence.providerResourceRef().orElseThrow(); var binding = operation.binding();
                var priorResource = query(c, "SELECT * FROM provider_compatibility_resource_owners WHERE " + SCOPE, key(operation));
                if (priorResource != null && !resource.equals(string(priorResource, "resource_ref")))
                    return conflict(c, operation, stored, "RESOURCE_REFERENCE", ProviderJournalCodec.digest(string(priorResource, "resource_ref")), digest, encoded);
                write(c, "INSERT INTO provider_compatibility_resource_owners(provider_id,account_ref,region,resource_ref,tenant_id,merchant_id,run_id,caller_key) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                        binding.providerId(), binding.accountRef(), binding.region(), resource, operation.identity().tenantId(), operation.identity().merchantId(), operation.identity().runId(), operation.identity().callerIdempotencyKey());
                var owner = query(c, "SELECT * FROM provider_compatibility_resource_owners WHERE provider_id=? AND account_ref=? AND region=? AND resource_ref=?", binding.providerId(), binding.accountRef(), binding.region(), resource);
                if (owner == null || !sameKey(owner, operation)) return conflict(c, operation, stored, "RESOURCE_OWNER", null, digest, encoded);
            }
            var old = stored.projection;
            if (old != null && excludes(old.state(), evidence.state()))
                return conflict(c, operation, stored, "FINANCIAL_STATE", codec.evidenceDigest(old), digest, encoded);
            ExecutionEvidence selected = old == null || (old.state() == ExecutionEvidence.State.UNKNOWN && evidence.state() != ExecutionEvidence.State.UNKNOWN)
                    || (old.state() == ExecutionEvidence.State.PENDING && evidence.state() == ExecutionEvidence.State.CAPTURED) ? evidence : old;
            write(c, "UPDATE provider_compatibility_bindings SET dispatch_blocked=true,projection_evidence_id=?,updated_at=current_timestamp WHERE " + SCOPE, prefixed(operation, selected.evidenceId()));
            return Decision.success(selected);
        });
    }
    @Override public Optional<ExecutionEvidence> read(ExecutionOperation operation) {
        var expected = configuration.expectedLegacy(operation);
        return transaction(NOT_STARTED, c -> {
            var loaded = load(c, operation, expected, null);
            return loaded.failure != null ? Decision.failure(loaded.failure)
                    : Decision.success(loaded.stored == null ? Optional.empty() : Optional.ofNullable(loaded.stored.projection));
        });
    }
    private static boolean excludes(ExecutionEvidence.State first, ExecutionEvidence.State second) {
        boolean firstAccepted = first == ExecutionEvidence.State.PENDING || first == ExecutionEvidence.State.CAPTURED;
        boolean secondAccepted = second == ExecutionEvidence.State.PENDING || second == ExecutionEvidence.State.CAPTURED;
        return (firstAccepted && second == ExecutionEvidence.State.NO_EFFECT_FENCED) || (secondAccepted && first == ExecutionEvidence.State.NO_EFFECT_FENCED);
    }
    private record Stored(Map<String, Object> row, ExecutionOperation operation, ChargeOperation legacy,
                          SubmissionRequest submission, ExecutionEvidence projection) { }
    private record Loaded(Stored stored, ExecutionFailure failure) { }
    private Loaded load(Connection c, ExecutionOperation operation, ChargeOperation expected, Map<String, Object> supplied) throws SQLException {
        var row = supplied == null ? query(c, "SELECT * FROM provider_compatibility_bindings WHERE " + SCOPE + " FOR UPDATE", key(operation)) : supplied;
        if (row == null) return new Loaded(null, null);
        if (flag(row, "conflicted")) return new Loaded(null, failure(EVIDENCE_CONFLICT, MAY_HAVE_OCCURRED));
        ExecutionOperation decoded; ChargeOperation legacy;
        try {
            require(ProviderJournalCodec.VERSION.equals(string(row, "codec_version")));
            decoded = codec.decodeOperation(string(row, "operation_json")); legacy = codec.decodeLegacy(string(row, "legacy_json"));
            require(sameKey(row, decoded) && decoded.identity().operationId().equals(string(row, "operation_id")) && decoded.fingerprint().equals(string(row, "operation_fingerprint")));
            require(legacy.identity().scope().tenantId().equals(string(row, "legacy_tenant_id")) && legacy.identity().scope().runId().equals(row.get("legacy_run_id")) && legacy.identity().adapterKey().equals(row.get("legacy_adapter_key")));
        } catch (RuntimeException corrupt) { var rejected = corrupt(c, operation, row); return new Loaded(null, rejected.failure); }
        if (!decoded.equals(operation) || !legacy.equals(expected) || !configuration.mappingPolicyVersion().equals(string(row, "mapping_policy_version"))
                || !configuration.scenarioPolicyReference().equals(string(row, "scenario_policy_ref")) || !configuration.scenarioPolicyVersion().equals(string(row, "scenario_policy_version"))) {
            receipt(c, operation, row, "BIND_IDENTITY", IDENTITY_CONFLICT, NOT_STARTED, decoded.fingerprint(), operation.fingerprint(), null);
            return new Loaded(null, failure(IDENTITY_CONFLICT, NOT_STARTED));
        }
        try {
            SubmissionRequest submission = null;
            if (row.get("submission_json") != null) {
                submission = codec.decodeSubmission(string(row, "submission_json"));
                require(submission.operation().equals(operation) && submission.fingerprint().equals(string(row, "submission_fingerprint"))
                        && Boolean.FALSE.equals(row.get("prepared_resource_present")) && row.get("prepared_resource_ref") == null && submission.preparedResourceRef().isEmpty()
                        && (flag(row, "dispatch_claimed") || flag(row, "dispatch_blocked")));
            } else require(row.get("submission_fingerprint") == null && row.get("prepared_resource_present") == null && row.get("prepared_resource_ref") == null && !flag(row, "dispatch_claimed"));
            var anyEvidence = query(c, "SELECT evidence_id FROM provider_compatibility_evidence WHERE " + SCOPE + " LIMIT 1", key(operation));
            require(anyEvidence == null || flag(row, "dispatch_blocked"));
            ExecutionEvidence projection = null;
            if (row.get("projection_evidence_id") != null) {
                var fact = query(c, "SELECT * FROM provider_compatibility_evidence WHERE " + SCOPE + " AND evidence_id=?", suffixed(operation, row.get("projection_evidence_id")));
                require(fact != null && flag(row, "dispatch_blocked")); projection = decodeEvidence(fact, operation);
            } else require(anyEvidence == null);
            var resource = query(c, "SELECT * FROM provider_compatibility_resource_owners WHERE " + SCOPE, key(operation));
            if (resource != null) {
                require(operation.binding().providerId().equals(string(resource, "provider_id")) && operation.binding().accountRef().equals(string(resource, "account_ref")) && operation.binding().region().equals(string(resource, "region")));
                if (projection != null && projection.providerResourceRef().isPresent()) require(projection.providerResourceRef().orElseThrow().equals(string(resource, "resource_ref")));
            } else if (projection != null) require(projection.providerResourceRef().isEmpty());
            var priorConflict = query(c, "SELECT receipt_id FROM provider_compatibility_conflicts WHERE requested_tenant_id=? AND requested_merchant_id=? AND requested_run_id=? AND requested_caller_key=? AND code IN ('EVIDENCE_CONFLICT','CONTRACT_VIOLATION') LIMIT 1", key(operation));
            require(priorConflict == null);
            return new Loaded(new Stored(row, decoded, legacy, submission, projection), null);
        } catch (RuntimeException corrupt) { var rejected = corrupt(c, operation, row); return new Loaded(null, rejected.failure); }
    }
    private ExecutionEvidence decodeEvidence(Map<String, Object> row, ExecutionOperation operation) {
        var fact = codec.decodeEvidence(string(row, "evidence_json"));
        require(fact.operation().equals(operation) && sameKey(row, operation) && ProviderJournalCodec.VERSION.equals(string(row, "codec_version"))
                && fact.evidenceId().equals(string(row, "evidence_id")) && codec.evidenceDigest(fact).equals(string(row, "evidence_digest"))
                && fact.state().name().equals(string(row, "state")) && fact.source().name().equals(string(row, "source")) && fact.observedAt().toString().equals(string(row, "observed_at")));
        return fact;
    }
    private <T> Decision<T> corrupt(Connection c, ExecutionOperation operation, Map<String, Object> row) throws SQLException {
        receipt(c, operation, row, "CORRUPT_STORAGE", CONTRACT_VIOLATION, MAY_HAVE_OCCURRED, null, operation.fingerprint(), null);
        quarantine(c, operation); return Decision.failure(CONTRACT_VIOLATION, MAY_HAVE_OCCURRED);
    }
    private Decision<ExecutionEvidence> conflict(Connection c, ExecutionOperation operation, Stored stored, String kind, String oldDigest, String candidateDigest, String candidate) throws SQLException {
        receipt(c, operation, stored.row, kind, EVIDENCE_CONFLICT, MAY_HAVE_OCCURRED, oldDigest, candidateDigest, candidate);
        quarantine(c, operation); return Decision.failure(EVIDENCE_CONFLICT, MAY_HAVE_OCCURRED);
    }
    private void quarantine(Connection c, ExecutionOperation operation) throws SQLException {
        write(c, "UPDATE provider_compatibility_bindings SET conflicted=true,dispatch_blocked=true,updated_at=current_timestamp WHERE " + SCOPE, key(operation));
    }
    private void receipt(Connection c, ExecutionOperation operation, Map<String, Object> owner, String kind, ExecutionFailure.Code code,
                         ExecutionFailure.Certainty certainty, String existingDigest, String candidateDigest, String candidateEvidence) throws SQLException {
        var id = operation.identity();
        write(c, "INSERT INTO provider_compatibility_conflicts(receipt_id,requested_tenant_id,requested_merchant_id,requested_run_id,requested_caller_key,owner_tenant_id,owner_merchant_id,owner_run_id,owner_caller_key,kind,code,certainty,existing_digest,candidate_digest,candidate_evidence_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), id.tenantId(), id.merchantId(), id.runId(), id.callerIdempotencyKey(), owner == null ? null : owner.get("tenant_id"),
                owner == null ? null : owner.get("merchant_id"), owner == null ? null : owner.get("run_id"), owner == null ? null : owner.get("caller_key"),
                kind, code.name(), certainty.name(), existingDigest, candidateDigest, candidateEvidence);
    }
    private record Decision<T>(T value, ExecutionFailure failure) {
        static <T> Decision<T> success(T value) { return new Decision<>(value, null); }
        static <T> Decision<T> failure(ExecutionFailure.Code code, ExecutionFailure.Certainty certainty) { return failure(new ExecutionFailure(code, certainty)); }
        static <T> Decision<T> failure(ExecutionFailure failure) { return new Decision<>(null, failure); }
    }
    @FunctionalInterface private interface Work<T> { Decision<T> run(Connection connection) throws SQLException; }
    private <T> T transaction(ExecutionFailure.Certainty unavailableCertainty, Work<T> work) {
        Decision<T> decision;
        try (var c = database.getConnection()) {
            c.setAutoCommit(false);
            try { decision = work.run(c); c.commit(); }
            catch (RuntimeException | SQLException unsafe) { try { c.rollback(); } catch (SQLException ignored) { } throw unsafe; }
        } catch (SQLException | RuntimeException unsafe) {
            throw new ExecutionFailure(CONFIGURATION_UNAVAILABLE, unavailableCertainty);
        }
        // Deliberately outside rollback handling: conflict receipts/quarantine already committed.
        if (decision.failure != null) throw decision.failure;
        return decision.value;
    }
    private static ExecutionFailure failure(ExecutionFailure.Code code, ExecutionFailure.Certainty certainty) { return new ExecutionFailure(code, certainty); }
    private static void require(boolean valid) { if (!valid) throw failure(CONTRACT_VIOLATION, MAY_HAVE_OCCURRED); }
    private static boolean flag(Map<String, Object> row, String column) { return Boolean.TRUE.equals(row.get(column)); }
    private static String string(Map<String, Object> row, String column) { return Objects.toString(row.get(column), null); }
    private static boolean sameKey(Map<String, Object> row, ExecutionOperation operation) {
        var id = operation.identity(); return id.tenantId().equals(row.get("tenant_id")) && id.merchantId().equals(row.get("merchant_id")) && id.runId().equals(row.get("run_id")) && id.callerIdempotencyKey().equals(row.get("caller_key"));
    }
    private static Object[] key(ExecutionOperation operation) {
        var id = operation.identity(); return new Object[]{id.tenantId(), id.merchantId(), id.runId(), id.callerIdempotencyKey()};
    }
    private static Object[] suffixed(ExecutionOperation operation, Object... extra) {
        var result = Arrays.copyOf(key(operation), 4 + extra.length); System.arraycopy(extra, 0, result, 4, extra.length); return result;
    }
    private static Object[] prefixed(ExecutionOperation operation, Object... extra) {
        var result = Arrays.copyOf(extra, extra.length + 4); System.arraycopy(key(operation), 0, result, extra.length, 4); return result;
    }
    private static int write(Connection c, String sql, Object... values) throws SQLException {
        try (var statement = c.prepareStatement(sql)) { parameters(statement, values); return statement.executeUpdate(); }
    }
    private static Map<String, Object> query(Connection c, String sql, Object... values) throws SQLException {
        try (var statement = c.prepareStatement(sql)) {
            parameters(statement, values);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return null; var result = new HashMap<String, Object>(); var metadata = rows.getMetaData();
                for (int index = 1; index <= metadata.getColumnCount(); index++) result.put(metadata.getColumnLabel(index), rows.getObject(index));
                return result;
            }
        }
    }
    private static void parameters(PreparedStatement statement, Object[] values) throws SQLException {
        for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
    }
}
