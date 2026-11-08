package io.agenticpayments.application.instrumentation;

import io.agenticpayments.application.ApplicationException;
import io.agenticpayments.application.ApplicationFailure;
import io.agenticpayments.application.PaymentService;
import io.agenticpayments.observability.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Finite service-invocation observations. No arguments, result bodies or exception text are exported. */
public final class ServiceTelemetry {
    public enum Operation {
        CREATE, QUOTE, QUOTE_EXTERNAL, APPROVE, EXECUTE, STATUS, CHECKOUT, ACTION,
        COMPLETE_ACTION, REVOKE, SEED_RUN, ADVANCE_CLOCK, RUN_NEXT, RUN_NEXT_RECORDED,
        RECONCILE, COMMERCE_STAGE, COMMERCE_STATUS, COMMERCE_DISPATCH, COMMERCE_RECOVER
    }
    public static final String CATEGORY = "platform.services";
    public static final String METER = "platform.services";
    public static final String VERSION = "0.1";
    private static final Set<String> OPERATIONS = Arrays.stream(Operation.values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());
    private static final Set<String> OUTCOMES = Set.of("returned", "replayed", "rejected", "error");
    private static final Set<String> FAILURES;
    static {
        var values = new HashSet<String>(); values.add("NONE");
        Arrays.stream(ApplicationFailure.Category.values()).map(Enum::name).forEach(values::add);
        FAILURES = Set.copyOf(values);
    }
    private static final Map<String, Set<String>> DIMENSIONS = Map.of("operation", OPERATIONS, "outcome", OUTCOMES, "failure", FAILURES);
    public static final EventDefinition COMPLETED = new EventDefinition("service.completed", "Service invocation completed", Map.of(
        "operation", FieldDefinition.finite(OPERATIONS), "outcome", FieldDefinition.finite(OUTCOMES), "failure", FieldDefinition.finite(FAILURES)));
    public static final CounterDefinition CALLS = new CounterDefinition("service.calls", "calls", DIMENSIONS);
    public static final HistogramDefinition DURATION = new HistogramDefinition("service.duration", "ms", DIMENSIONS,
        List.of(0.1, 1.0, 10.0, 100.0, 1000.0, 10000.0, 60000.0));
    public static final MeterDefinition DEFINITION = new MeterDefinition(METER, VERSION, List.of(CALLS), List.of(DURATION));
    private final LoggerFactory loggers;
    private final MeterFactory meters;
    private final MonotonicClock clock;

    public ServiceTelemetry(LoggerFactory loggers, MeterFactory meters, MonotonicClock clock) {
        this.loggers = Objects.requireNonNull(loggers);
        this.meters = Objects.requireNonNull(meters);
        this.clock = Objects.requireNonNull(clock);
    }
    public <T> T call(Operation operation, Supplier<T> invocation) {
        Objects.requireNonNull(operation); Objects.requireNonNull(invocation);
        Long started = null;
        try { started = clock.nanoTime(); } catch (Throwable fault) { fatal(fault); }
        T result;
        try { result = invocation.get(); }
        catch (RuntimeException | Error failure) {
            fatal(failure);
            String category = failure instanceof ApplicationException application ? application.failure().category().name() : "INTERNAL";
            completed(operation, category.equals("INTERNAL") || category.equals("UNAVAILABLE") ? "error" : "rejected", category, started);
            throw failure;
        }
        completed(operation, result instanceof PaymentService.Result response && response.replayed() ? "replayed" : "returned", "NONE", started);
        return result;
    }
    public void run(Operation operation, Runnable invocation) {
        call(operation, () -> { invocation.run(); return null; });
    }
    private static void fatal(Throwable fault) {
        if (fault instanceof VirtualMachineError fatal) throw fatal;
        if (fault instanceof ThreadDeath fatal) throw fatal;
    }
    private void completed(Operation operation, String outcome, String failure, Long started) {
        Map<String,String> dimensions = Map.of("operation", operation.name(), "outcome", outcome, "failure", failure);
        // Separate guards keep one failed replacement from suppressing the other observation channels.
        try {
            Logger logger = loggers.create(CATEGORY);
            LogLevel level = outcome.equals("error") ? LogLevel.ERROR : LogLevel.INFO;
            if (logger.isEnabled(level)) logger.log(level, COMPLETED, LogFields.of(Map.of(
                "operation", LogValue.finite(operation.name()), "outcome", LogValue.finite(outcome), "failure", LogValue.finite(failure))));
        } catch (Throwable fault) { fatal(fault); }
        try { meters.create(METER, VERSION).counter(CALLS).add(1, dimensions); } catch (Throwable fault) { fatal(fault); }
        if (started != null) {
            try {
                long elapsed = clock.nanoTime() - started;
                if (elapsed >= 0) meters.create(METER, VERSION).histogram(DURATION).record(elapsed / 1_000_000.0, dimensions);
            } catch (Throwable fault) { fatal(fault); }
        }
    }
}
