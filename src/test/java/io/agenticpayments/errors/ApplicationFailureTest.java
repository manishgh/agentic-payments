package io.agenticpayments.errors;

import io.agenticpayments.application.ApplicationException;
import io.agenticpayments.application.ApplicationFailure;
import io.agenticpayments.application.PaymentException;
import io.agenticpayments.http.HttpFailureMapper;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static io.agenticpayments.application.ApplicationFailure.*;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationFailureTest {
    @Test void pinsEveryLegacyApplicationWireRepresentation() {
        String rows = """
                INVALID_REQUEST|400|INVALID_REQUEST|invalid request|NEVER
                UNAUTHENTICATED|401|UNAUTHENTICATED|unauthenticated|NEVER
                FORBIDDEN|403|FORBIDDEN|forbidden|NEVER
                MERCHANT_NOT_ALLOWED|403|MERCHANT_NOT_ALLOWED|merchant not allowed|NEVER
                NOT_FOUND|404|NOT_FOUND|not found|NEVER
                RESOURCE_NOT_FOUND|404|RESOURCE_NOT_FOUND|resource not found|NEVER
                ACTION_ALREADY_CONSUMED|409|ACTION_ALREADY_CONSUMED|action already consumed|NEVER
                AUTHORITY_REPLAY|409|AUTHORITY_REPLAY|authority replay|NEVER
                AUTHORITY_REVOKED|409|AUTHORITY_REVOKED|authority revoked|NEVER
                BUDGET_EXCEEDED|409|BUDGET_EXCEEDED|budget exceeded|NEVER
                CHECKOUT_ALREADY_BOUND|409|CHECKOUT_ALREADY_BOUND|checkout already bound|NEVER
                CHECKOUT_ALREADY_EXISTS|409|CHECKOUT_ALREADY_EXISTS|checkout already exists|NEVER
                CHECKOUT_BINDING_CONFLICT|409|CHECKOUT_BINDING_CONFLICT|checkout binding conflict|NEVER
                CHECKOUT_BINDING_CONFLICT_VALIDATION|422|CHECKOUT_BINDING_CONFLICT|checkout binding conflict|NEVER
                CHECKOUT_CHANGED|409|CHECKOUT_CHANGED|checkout changed|NEVER
                CHECKOUT_COMMAND_IN_FLIGHT|409|CHECKOUT_COMMAND_IN_FLIGHT|checkout command in flight|NEVER
                CHECKOUT_CORRELATION_CONFLICT|409|CHECKOUT_CORRELATION_CONFLICT|checkout correlation conflict|NEVER
                CHECKOUT_MUTATION_BLOCKED|409|CHECKOUT_MUTATION_BLOCKED|checkout mutation blocked|NEVER
                CHECKOUT_REVISION_CONFLICT|409|CHECKOUT_REVISION_CONFLICT|checkout revision conflict|NEVER
                CHECKOUT_REVISION_EXHAUSTED|409|CHECKOUT_REVISION_EXHAUSTED|checkout revision exhausted|NEVER
                CONFIGURATION_CHANGED|409|CONFIGURATION_CHANGED|configuration changed|NEVER
                CONFIGURATION_IDENTITY_CONFLICT|409|CONFIGURATION_IDENTITY_CONFLICT|configuration identity conflict|NEVER
                IDEMPOTENCY_CONFLICT|409|IDEMPOTENCY_CONFLICT|idempotency conflict|NEVER
                INVALID_STATE|409|INVALID_STATE|invalid state|NEVER
                TRUSTED_RESOLUTION_REQUIRED|409|TRUSTED_RESOLUTION_REQUIRED|trusted resolution required|NEVER
                VERSION_CONFLICT|409|VERSION_CONFLICT|version conflict|NEVER
                ACTION_EXPIRED|410|ACTION_EXPIRED|action expired|NEVER
                AUTHORITY_EXPIRED|410|AUTHORITY_EXPIRED|authority expired|NEVER
                AUTHORITY_BINDING_INVALID|422|AUTHORITY_BINDING_INVALID|authority binding invalid|NEVER
                CHECKOUT_CONFIGURATION_UNAVAILABLE|422|CHECKOUT_CONFIGURATION_UNAVAILABLE|checkout configuration unavailable|NEVER
                INVALID_CHECKOUT_PREPARATION|422|INVALID_CHECKOUT_PREPARATION|invalid checkout preparation|NEVER
                UNSUPPORTED_CAPABILITY|422|UNSUPPORTED_CAPABILITY|unsupported capability|NEVER
                UNSUPPORTED_PROFILE|422|UNSUPPORTED_PROFILE|unsupported profile|NEVER
                UNSUPPORTED_TENDER|422|UNSUPPORTED_TENDER|unsupported tender|NEVER
                CHECKOUT_STORE_UNAVAILABLE|503|CHECKOUT_STORE_UNAVAILABLE|checkout store unavailable|NEVER
                CHECKOUT_STORE_UNAVAILABLE_SAME_COMMAND|503|CHECKOUT_STORE_UNAVAILABLE|checkout store unavailable|SAME_COMMAND
                TEMPORARILY_UNAVAILABLE|503|TEMPORARILY_UNAVAILABLE|temporarily unavailable|NEVER
                TEMPORARILY_UNAVAILABLE_SAME_COMMAND|503|TEMPORARILY_UNAVAILABLE|temporarily unavailable|SAME_COMMAND
                """;
        var seen = new HashSet<ApplicationFailure>();
        for (String row : rows.lines().toList()) {
            String[] cells = row.split("\\|");
            var failure = ApplicationFailure.valueOf(cells[0]);
            assertTrue(seen.add(failure));
            assertEquals(cells[0], failure.id());
            var expected = new HttpFailureMapper.MappedFailure(Integer.parseInt(cells[1]), cells[2], cells[3], cells[4]);
            assertEquals(expected, HttpFailureMapper.map(failure), failure.id());
            assertEquals(expected, HttpFailureMapper.map(new ApplicationException(failure)), failure.id());
            assertEquals(expected, HttpFailureMapper.map(new PaymentException(failure)), failure.id());
        }
        assertEquals(ApplicationFailure.values().length, seen.size());
    }

    @Test void failuresCannotCarryRawCausesSuppressedExceptionsOrCustomDetails() {
        for (Class<?> type : new Class<?>[]{ApplicationException.class, PaymentException.class}) {
            var constructors = type.getConstructors();
            assertEquals(1, constructors.length);
            assertArrayEquals(new Class<?>[]{ApplicationFailure.class}, constructors[0].getParameterTypes());
            assertThrows(NoSuchMethodException.class, () -> type.getMethod("status"));
        }
        var exception = new ApplicationException(TEMPORARILY_UNAVAILABLE_SAME_COMMAND);
        assertSame(TEMPORARILY_UNAVAILABLE_SAME_COMMAND, exception.failure());
        assertEquals("temporarily unavailable", exception.getMessage());
        assertEquals("TEMPORARILY_UNAVAILABLE", exception.code());
        assertEquals("SAME_COMMAND", HttpFailureMapper.map(exception).retry());
        assertNull(exception.getCause());
        assertThrows(IllegalStateException.class, () -> exception.initCause(new RuntimeException("private peer text")));
        exception.addSuppressed(new RuntimeException("private peer text"));
        assertEquals(0, exception.getSuppressed().length);
        assertThrows(NullPointerException.class, () -> new ApplicationException(null));
    }

    @Test void semanticCategoriesAndUncertainStorageFailuresRemainTransportIndependent() {
        assertEquals(Category.EXPIRED, AUTHORITY_EXPIRED.category());
        assertEquals(Category.EXPIRED, ACTION_EXPIRED.category());
        assertEquals(Category.CONFLICT, AUTHORITY_REVOKED.category());
        assertEquals(Category.UNSUPPORTED, UNSUPPORTED_CAPABILITY.category());
        assertEquals(Category.VALIDATION, CHECKOUT_BINDING_CONFLICT_VALIDATION.category());
        for (var failure : new ApplicationFailure[]{TEMPORARILY_UNAVAILABLE, TEMPORARILY_UNAVAILABLE_SAME_COMMAND,
                CHECKOUT_STORE_UNAVAILABLE, CHECKOUT_STORE_UNAVAILABLE_SAME_COMMAND}) {
            assertEquals(Category.UNAVAILABLE, failure.category());
            assertEquals(Certainty.MAY_HAVE_OCCURRED, failure.certainty());
        }
        assertEquals(Retry.NEVER, TEMPORARILY_UNAVAILABLE.retry());
        assertEquals(Retry.SAME_COMMAND, TEMPORARILY_UNAVAILABLE_SAME_COMMAND.retry());
        assertEquals(Retry.NEVER, CHECKOUT_STORE_UNAVAILABLE.retry());
        assertEquals(Retry.SAME_COMMAND, CHECKOUT_STORE_UNAVAILABLE_SAME_COMMAND.retry());
    }

    @Test void decodeAndUnexpectedResponsesRetainTheirDistinctSafeMessages() {
        assertEquals(new HttpFailureMapper.MappedFailure(400, "INVALID_REQUEST",
                "The request does not match the contract.", "NEVER"), HttpFailureMapper.invalidRequest());
        assertEquals(new HttpFailureMapper.MappedFailure(503, "TEMPORARILY_UNAVAILABLE",
                "The service is temporarily unavailable.", "SAME_COMMAND"), HttpFailureMapper.unexpected());
        assertNotEquals(HttpFailureMapper.map(TEMPORARILY_UNAVAILABLE_SAME_COMMAND).message(),
                HttpFailureMapper.unexpected().message());
    }
}
