package io.agenticpayments.infrastructure.tck;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agenticpayments.domain.PaymentRules;
import io.agenticpayments.infrastructure.JdbcSimulatorAdapter;
import io.agenticpayments.sdk.*;
import io.agenticpayments.sdk.descriptor.*;
import io.agenticpayments.sdk.tck.*;
import javax.sql.DataSource;
import java.util.*;

/** Adapter-only fixture. It inspects journal tables directly and leaves durable evidence in place. */
public final class JdbcAdapterTckFactory implements AdapterTckFactory {
    private final DataSource database;
    public JdbcAdapterTckFactory(DataSource database) { this.database = Objects.requireNonNull(database); }
    @Override public AdapterTckFixture open(TckCaseContext context) { return new Fixture(context); }

    public static AdapterDescriptor descriptor() {
        Set<String> capabilities = new HashSet<>(CapabilityOffer.CATALOG);
        capabilities.add("atomic-no-effect-seal");
        return new AdapterDescriptor(AdapterDescriptor.SCHEMA_VERSION, "simulator", "0.1.0", Set.of(CapabilityOffer.EXTENSION_VERSION),
                Set.of(CapabilityOffer.PROFILE), capabilities, List.of(new AdapterCombination("synthetic-card", "synthetic-reference",
                Set.of("none", "synthetic-challenge"), Set.of("synthetic-charge"), capabilities,
                List.of(new CurrencyLimit("EUR", 2, "1", "999999999999"), new CurrencyLimit("USD", 2, "1", "999999999999")))));
    }
    private final class Fixture implements AdapterTckFixture {
        private final ExecutionScope scope;
        private final JdbcSimulatorAdapter adapter = new JdbcSimulatorAdapter(database);
        private final Map<String, ChargeOperation> operations = new HashMap<>();
        private final ObjectMapper json = new ObjectMapper();
        Fixture(TckCaseContext context) { scope = new ExecutionScope("tck-fixture", UUID.fromString(context.fixtureNamespace()), "merchant-demo"); }
        @Override public AdapterDescriptor descriptor() { return JdbcAdapterTckFactory.descriptor(); }
        @Override public ChargeOperation operation(String logicalName, String scenarioId) {
            if (!logicalName.matches("[a-z][a-z0-9-]{0,63}")) throw new IllegalArgumentException("Invalid TCK entity alias.");
            ChargeOperation existing = operations.get(logicalName);
            if (existing != null) {
                if (!existing.scenarioId().equals(scenarioId)) throw new IllegalArgumentException("Fixture alias binding changed.");
                return existing;
            }
            UUID operation = UUID.randomUUID();
            var identity = new OperationIdentity(scope, UUID.randomUUID(), UUID.randomUUID(), operation, operation,
                    PaymentRules.sha256("adapter-tck-binding-0.1|" + scope.runId() + "|" + logicalName + "|" + scenarioId + "|EUR|1250"));
            var created = new ChargeOperation(identity, new Money("EUR", 1250), scenarioId);
            operations.put(logicalName, created);
            return created;
        }
        @Override public TckExecutionDriver execution() {
            return new TckExecutionDriver() {
                public Preparation prepare(ChargeOperation operation) { return adapter.prepare(operation); }
                public Fact execute(ChargeOperation operation) { return adapter.execute(operation); }
                public LookupResult lookup(OperationIdentity identity) { return adapter.lookup(identity); }
            };
        }
        @Override public Optional<AtomicNoEffectSealer> noEffectSealer() { return Optional.of(adapter::sealNoEffect); }
        @Override public TckObservation inspect() {
            long accepted = 0, successful = 0;
            boolean bindings = true;
            List<Fact> facts = new ArrayList<>();
            try (var connection = database.getConnection()) {
                connection.setAutoCommit(false);
                connection.setReadOnly(true);
                connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
                try (var statement = connection.prepareStatement("SELECT * FROM simulator_operation_journal WHERE tenant_id=? AND run_id=?")) {
                    statement.setString(1, scope.tenantId()); statement.setObject(2, scope.runId());
                    try (var rows = statement.executeQuery()) {
                        while (rows.next()) {
                            var identity = json.readValue(rows.getString("identity_json"), OperationIdentity.class);
                            var expected = operations.values().stream().filter(operation -> operation.identity().adapterKey().equals(identity.adapterKey())).findFirst();
                            bindings &= expected.isPresent() && expected.get().identity().equals(identity)
                                    && expected.get().money().equals(new Money(rows.getString("currency"), rows.getLong("minor_units")))
                                    && expected.get().scenarioId().equals(rows.getString("scenario_id"))
                                    && identity.requestDigest().equals(rows.getString("request_digest"));
                            String outcome = rows.getString("outcome");
                            if (!outcome.equals("NO_EFFECT")) accepted++;
                            if (outcome.equals("SUCCEEDED")) successful++;
                        }
                    }
                }
                try (var statement = connection.prepareStatement("SELECT * FROM simulator_journal_events WHERE tenant_id=? AND run_id=? ORDER BY sequence,event_id")) {
                    statement.setString(1, scope.tenantId()); statement.setObject(2, scope.runId());
                    try (var rows = statement.executeQuery()) {
                        while (rows.next()) {
                            var identity = json.readValue(rows.getString("identity_json"), OperationIdentity.class);
                            facts.add(new Fact(rows.getObject("event_id", UUID.class), identity,
                                    new Money(rows.getString("currency"), rows.getLong("minor_units")), rows.getLong("sequence"), Outcome.valueOf(rows.getString("outcome"))));
                            bindings &= operations.values().stream().anyMatch(operation -> operation.identity().equals(identity));
                        }
                    }
                }
                connection.commit();
                return new TckObservation(accepted, successful, bindings, facts);
            } catch (Exception failure) { throw new IllegalStateException("TCK journal inspection is unavailable."); }
        }
    }
}
