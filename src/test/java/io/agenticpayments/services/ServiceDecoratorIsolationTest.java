package io.agenticpayments.services;

import io.agenticpayments.application.*;
import io.agenticpayments.application.PaymentService.*;
import io.agenticpayments.application.instrumentation.*;
import io.agenticpayments.checkout.CheckoutSessions;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ServiceDecoratorIsolationTest {
    private static final Session SESSION = new Session("services-test", UUID.randomUUID(), "synthetic-subject",
            Set.of(), Set.of(), Set.of());

    @Test void everyPaymentOperationRetainsExactResultAndOriginalExceptionAcrossReplacementFaults() throws Exception {
        verify(PaymentOperations.class);
    }

    @Test void everyCheckoutOperationRetainsExactResultAndOriginalExceptionAcrossReplacementFaults() throws Exception {
        verify(CheckoutLifecycle.class);
    }

    private static <T> void verify(Class<T> contract) throws Exception {
        for (Method method : contract.getMethods()) {
            for (var fault : TelemetryFaults.Fault.values()) {
              for (var telemetryFailure : TelemetryFaults.Failure.values()) {
                for (Throwable original : Arrays.asList(null,
                        new ApplicationException(ApplicationFailure.AUTHORITY_EXPIRED),
                        new IllegalArgumentException("private-business-canary"),
                        new AssertionError("private-business-canary"), new LinkageError("private-business-canary"))) {
                    var faults = new TelemetryFaults(fault, telemetryFailure);
                    var calls = new AtomicInteger();
                    Object[] arguments = arguments(method);
                    Object expected = result(method.getReturnType());
                    T inner = contract.cast(Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract},
                            (proxy, invoked, actual) -> {
                                calls.incrementAndGet();
                                assertEquals(method, invoked);
                                assertArrayEquals(arguments, actual);
                                if (original != null) throw original;
                                return expected;
                            }));
                    Object decorated = contract == PaymentOperations.class
                            ? new InstrumentedPaymentOperations((PaymentOperations) inner, faults.telemetry)
                            : new InstrumentedCheckoutLifecycle((CheckoutLifecycle) inner, faults.telemetry);
                    if (original == null) {
                        assertSame(expected, method.invoke(decorated, arguments), method.getName() + "/" + fault);
                    } else {
                        var thrown = assertThrows(InvocationTargetException.class, () -> method.invoke(decorated, arguments));
                        assertSame(original, thrown.getCause(), method.getName() + "/" + fault);
                    }
                    assertEquals(1, calls.get(), method.getName() + "/" + fault);
                    assertTrue(faults.failures.get() > 0, "Fault was not exercised: " + method.getName() + "/" + fault);
                }
              }
            }
        }
    }

    private static Object result(Class<?> type) {
        if (type == void.class) return null;
        if (type == boolean.class) return Boolean.TRUE;
        if (type == Result.class) return new Result(202, Map.of("protected", "synthetic-result-canary"), true);
        if (type == WorkerReceipt.class) return new WorkerReceipt(UUID.randomUUID(), "PREPARE", 1, 1, 2, true);
        if (type == CheckoutLifecycle.SafeView.class) return new CheckoutLifecycle.SafeView(
                CheckoutLifecycle.CONTRACT_VERSION, CheckoutLifecycle.PROFILE, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 3, CheckoutSessions.Operation.RETRIEVE, CheckoutLifecycle.CommandState.UNKNOWN,
                "TRANSPORT_UNAVAILABLE", CheckoutSessions.Observation.UNKNOWN, 0, true, "ready_for_payment", null,
                true, CheckoutLifecycle.Recovery.RETRIEVE_ONLY);
        throw new AssertionError("Unexpected contract return: " + type);
    }

    private static Object[] arguments(Method method) {
        Class<?>[] types = method.getParameterTypes(); Object[] values = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            values[i] = type == Session.class ? SESSION : type == UUID.class ? UUID.randomUUID()
                    : type == String.class ? "argument-" + i : type == boolean.class ? true
                    : type == long.class ? 1250L : type == Instant.class ? Instant.parse("2026-11-06T10:00:00Z")
                    : type == CreateCommand.class ? new CreateCommand("merchant-demo", "success", "fixture", List.of())
                    : type == QuoteCommand.class ? new QuoteCommand(1, "cart", List.of())
                    : type == ExternalQuoteCommand.class ? new ExternalQuoteCommand(1, UUID.randomUUID(), 1, UUID.randomUUID(), List.of())
                    : type == ApproveCommand.class ? new ApproveCommand(1, 1, "digest", "grant")
                    : type == ExecuteCommand.class ? new ExecuteCommand(1, UUID.randomUUID())
                    : type == CompleteActionCommand.class ? new CompleteActionCommand(1, "approve")
                    : type == RevokeCommand.class ? new RevokeCommand("user-request")
                    : type == CheckoutLifecycle.StageCommand.class ? new CheckoutLifecycle.StageCommand(
                            CheckoutSessions.Operation.RETRIEVE, 1, "merchant-demo", "local-config", "eur", List.of()) : null;
            assertNotNull(values[i], "Missing fixture for " + type);
        }
        return values;
    }
}
