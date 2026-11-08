package io.agenticpayments.infrastructure.observability;

import io.agenticpayments.observability.*;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded catalog logging. Exporters are isolated behind independent lossy queues. */
public final class BoundedLoggerFactory implements LoggerFactory, AutoCloseable {
    private static final LogScope EMPTY_SCOPE = () -> { };
    private final Set<String> categories;
    private final Map<String, EventDefinition> events;
    private final Map<String, FieldDefinition> scopeFields;
    private final List<ProviderDispatcher<LogRecord>> dispatchers;
    private final TelemetryOptions options;
    private final Clock clock;
    private final HealthCounters health = new HealthCounters();
    private final ThreadLocal<ScopeState> scopes = ThreadLocal.withInitial(ScopeState::new);
    private volatile boolean closed;

    public BoundedLoggerFactory(Set<String> categories, List<EventDefinition> events,
                                Map<String, FieldDefinition> scopeFields, List<LogProvider> providers,
                                TelemetryOptions options) {
        this(categories, events, scopeFields, providers, options, Clock.systemUTC());
    }
    public BoundedLoggerFactory(Set<String> categories, List<EventDefinition> events,
                                Map<String, FieldDefinition> scopeFields, List<LogProvider> providers,
                                TelemetryOptions options, Clock clock) {
        if (categories == null || categories.size() > 64 || events == null || events.size() > 128
                || scopeFields == null || scopeFields.size() > 16 || providers == null || providers.size() > 16
                || options == null || clock == null) throw new IllegalArgumentException("Invalid log catalog");
        categories.forEach(TelemetryNames::require); scopeFields.keySet().forEach(TelemetryNames::field);
        this.categories = Set.copyOf(categories); this.scopeFields = Map.copyOf(scopeFields);
        Map<String, EventDefinition> catalog = new HashMap<>();
        for (EventDefinition event : events) {
            if (catalog.putIfAbsent(event.id(), event) != null) throw new IllegalArgumentException("Duplicate log event");
            if (event.fields().size() + scopeFields.size() > 16
                    || event.fields().keySet().stream().anyMatch(scopeFields::containsKey)) {
                throw new IllegalArgumentException("Conflicting or excessive event and scope fields");
            }
        }
        this.events = Map.copyOf(catalog); this.options = options; this.clock = clock;
        List<ProviderDispatcher<LogRecord>> queues = new ArrayList<>();
        Set<LogProvider> identities = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (LogProvider provider : providers) {
            if (provider == null || !identities.add(provider)) throw new IllegalArgumentException("Duplicate log provider");
            queues.add(new ProviderDispatcher<>(options, provider::export, provider::close, health));
        }
        dispatchers = List.copyOf(queues);
    }
    public synchronized void start() {
        if (closed) throw new IllegalStateException("Logger factory is closed");
        dispatchers.forEach(ProviderDispatcher::start);
    }
    @Override public io.agenticpayments.observability.Logger create(String category) {
        if (!categories.contains(category)) throw new IllegalArgumentException("Unregistered logger category");
        return new io.agenticpayments.observability.Logger() {
            @Override public boolean isEnabled(LogLevel level) {
                return !closed && level != null && level.ordinal() >= options.minimumLevel().ordinal();
            }
            @Override public void log(LogLevel level, EventDefinition event, LogFields fields) {
                if (!isEnabled(level)) return;
                if (event == null || !event.equals(events.get(event.id()))) { HealthCounters.add(health.dropped, 1); return; }
                try {
                    Map<String, LogValue> copy = new LinkedHashMap<>(scopes.get().snapshot());
                    if (fields != null) copy.putAll(validate(fields, event.fields()));
                    if (copy.size() > 16) { HealthCounters.add(health.dropped, 1); return; }
                    LogRecord record = new LogRecord(clock.instant(), category, level, event, new LogFields(copy));
                    dispatchers.forEach(dispatcher -> dispatcher.offer(record));
                } catch (Throwable failure) { TelemetryFaults.rethrowFatal(failure); HealthCounters.add(health.dropped, 1); }
            }
            @Override public LogScope beginScope(LogFields fields) { return openScope(fields); }
        };
    }
    private Map<String, LogValue> validate(LogFields fields, Map<String, FieldDefinition> schema) {
        Map<String, LogValue> valid = new LinkedHashMap<>();
        if (fields != null) fields.values().forEach((key, value) -> {
            FieldDefinition definition = schema.get(key);
            if (definition != null && definition.accepts(value)) valid.put(key, value);
            else HealthCounters.add(health.dropped, 1);
        });
        return Map.copyOf(valid);
    }
    private LogScope openScope(LogFields fields) {
        if (closed) return EMPTY_SCOPE;
        ScopeState state = scopes.get();
        Frame frame = new Frame(validate(fields, scopeFields));
        synchronized (state) {
            if (state.frames.size() == 16) { HealthCounters.add(health.dropped, 1); return EMPTY_SCOPE; }
            state.frames.addLast(frame);
        }
        Thread owner = Thread.currentThread();
        return new LogScope() {
            private boolean disposed;
            @Override public void close() {
                boolean misuse;
                synchronized (state) {
                    if (disposed) return;
                    disposed = true;
                    misuse = Thread.currentThread() != owner || state.frames.peekLast() != frame;
                    if (misuse) state.frames.clear(); else state.frames.removeLast();
                }
                if (misuse) scopes.get().clear();
            }
        };
    }
    @Override public ScopeSnapshot captureScope() {
        return new ScopeSnapshot(closed ? LogFields.EMPTY : new LogFields(scopes.get().snapshot()));
    }
    @Override public LogScope beginScope(ScopeSnapshot snapshot) {
        return openScope(snapshot == null ? LogFields.EMPTY : snapshot.fields());
    }
    public TelemetryHealth health() { return health.snapshot(); }
    @Override public void close() { close(options.shutdownTimeout()); }
    public void close(Duration timeout) {
        long deadline = ProviderDispatcher.deadline(timeout);
        synchronized (this) {
            if (closed) return;
            closed = true; dispatchers.forEach(ProviderDispatcher::beginClose);
        }
        scopes.remove();
        dispatchers.forEach(dispatcher -> dispatcher.awaitClose(deadline));
    }
    private record Frame(Map<String, LogValue> fields) { }
    private static final class ScopeState {
        private final ArrayDeque<Frame> frames = new ArrayDeque<>();
        synchronized Map<String, LogValue> snapshot() {
            Map<String, LogValue> values = new LinkedHashMap<>();
            for (Frame frame : frames) values.putAll(frame.fields());
            return values.size() > 16 ? Map.of() : Map.copyOf(values);
        }
        synchronized void clear() { frames.clear(); }
    }
}
